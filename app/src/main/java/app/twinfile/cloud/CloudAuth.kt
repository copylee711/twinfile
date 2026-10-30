package app.twinfile.cloud

import android.accounts.Account
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import app.twinfile.BuildConfig
import app.twinfile.fs.FsException
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.ClearTokenRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.identity.RevokeAccessRequest
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Tasks
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IMultipleAccountPublicClientApplication
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Native public clients only: no web client secret or app-owned token server. */
class CloudLoginCancelled : FsException("已取消登录")

class CloudAuth(private val context: Context) {
    private val google = Identity.getAuthorizationClient(context)
    private val googleScopes = listOf(Scope("https://www.googleapis.com/auth/drive"))
    private val microsoftScopes = listOf("Files.ReadWrite")
    private val lastGoogleTokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    @Volatile private var msal: IMultipleAccountPublicClientApplication? = null

    fun enabled(provider: CloudProvider) = when (provider) {
        CloudProvider.GOOGLE -> BuildConfig.GOOGLE_DRIVE_ENABLED && BuildConfig.GOOGLE_ANDROID_CLIENT_ID.isNotBlank()
        CloudProvider.ONEDRIVE -> BuildConfig.MICROSOFT_CLIENT_ID.isNotBlank()
    }

    fun startGoogle(activity: Activity, consent: (android.app.PendingIntent) -> Unit, result: (Result<String>) -> Unit) {
        if (!enabled(CloudProvider.GOOGLE)) { result(Result.failure(FsException("此版本暂未开放 Google Drive 登录"))); return }
        // Authorization UI handles account choice. The selected account is identified by Drive /about.
        val request = AuthorizationRequest.builder().setRequestedScopes(googleScopes)
            .setPrompt(AuthorizationRequest.Prompt.SELECT_ACCOUNT).build()
        Identity.getAuthorizationClient(activity).authorize(request).addOnSuccessListener {
            if (it.hasResolution()) it.pendingIntent?.let(consent) ?: result(Result.failure(FsException("无法启动 Google 授权")))
            else result(Result.success(it.accessToken ?: return@addOnSuccessListener result(Result.failure(CloudAuthRequired()))))
        }.addOnFailureListener { result(Result.failure(FsException("Google 授权失败，请检查网络和 Google Play 服务"))) }
    }

    fun googleResult(intent: Intent?): String = google.getAuthorizationResultFromIntent(intent).accessToken ?: throw CloudAuthRequired()

    fun identifyGoogle(accessToken: String): CloudAccount {
        val http = CloudHttp("https://www.googleapis.com/drive/v3/".toHttpUrl(), { accessToken })
        val user = http.json(http.api("about").newBuilder().addQueryParameter("fields", "user(permissionId,emailAddress,displayName)").build()).getJSONObject("user")
        val account = CloudAccount(CloudProvider.GOOGLE, user.getString("permissionId"), user.getString("emailAddress"),
            user.optString("emailAddress").ifEmpty { user.optString("displayName", "Google Drive") })
        lastGoogleTokens[account.handle] = accessToken
        return account
    }

    /** Called on a worker; interactive resolutions are surfaced to the cloud account screen. */
    fun token(account: CloudAccount, force: Boolean): String {
        if (!enabled(account.provider)) throw CloudAuthRequired()
        try {
            if (account.provider == CloudProvider.GOOGLE) {
                if (force) lastGoogleTokens.remove(account.handle)?.let {
                    Tasks.await(google.clearToken(ClearTokenRequest.builder().setToken(it).build()), 30, TimeUnit.SECONDS)
                }
                val result = Tasks.await(google.authorize(AuthorizationRequest.builder().setRequestedScopes(googleScopes)
                    .setAccount(Account(account.handle, "com.google")).build()), 30, TimeUnit.SECONDS)
                if (result.hasResolution()) throw CloudAuthRequired()
                val token = result.accessToken ?: throw CloudAuthRequired()
                lastGoogleTokens[account.handle] = token
                return token
            }
            val pca = microsoft()
            val native = pca.getAccount(account.handle) ?: throw CloudAuthRequired()
            return pca.acquireTokenSilent(AcquireTokenSilentParameters.Builder().withScopes(microsoftScopes)
                .forAccount(native).fromAuthority(native.authority).forceRefresh(force).build()).accessToken
        } catch (e: FsException) { throw e }
        catch (_: com.microsoft.identity.client.exception.MsalUiRequiredException) { throw CloudAuthRequired() }
        catch (_: Exception) { throw FsException("无法获取云盘授权，请检查网络；若授权已撤销，请重新连接账号") }
    }

    @Synchronized
    fun microsoft(): IMultipleAccountPublicClientApplication {
        msal?.let { return it }
        if (!enabled(CloudProvider.ONEDRIVE)) throw FsException("此版本暂未开放 OneDrive 登录")
        val future = CompletableFuture<IMultipleAccountPublicClientApplication>()
        Handler(Looper.getMainLooper()).post {
            try {
                val config = File(context.filesDir, "msal-config.json")
                config.writeText(JSONObject().put("client_id", BuildConfig.MICROSOFT_CLIENT_ID)
                    .put("redirect_uri", redirectUri()).put("broker_redirect_uri_registered", false)
                    .put("authorization_user_agent", "BROWSER").put("account_mode", "MULTIPLE")
                    .put("authorities", JSONArray().put(JSONObject().put("type", "AAD").put("audience",
                        JSONObject().put("type", "AzureADandPersonalMicrosoftAccount").put("tenant_id", "common"))))
                    .put("logging", JSONObject().put("pii_enabled", false).put("log_level", "ERROR")).toString())
                PublicClientApplication.createMultipleAccountPublicClientApplication(context, config,
                    object : IPublicClientApplication.IMultipleAccountApplicationCreatedListener {
                        override fun onCreated(application: IMultipleAccountPublicClientApplication) { msal = application; future.complete(application) }
                        override fun onError(exception: MsalException) { future.completeExceptionally(FsException("无法初始化 OneDrive 登录，请检查应用授权配置")) }
                    })
            } catch (_: Exception) { future.completeExceptionally(FsException("无法初始化 OneDrive 登录")) }
        }
        return future.get(30, TimeUnit.SECONDS)
    }

    fun startMicrosoft(activity: Activity, client: IMultipleAccountPublicClientApplication, result: (Result<CloudAccount>) -> Unit) {
        client.acquireToken(AcquireTokenParameters.Builder().startAuthorizationFromActivity(activity).withScopes(microsoftScopes)
            .withCallback(object : AuthenticationCallback {
                override fun onSuccess(authenticationResult: IAuthenticationResult) {
                    val account = authenticationResult.account
                    result(Result.success(CloudAccount(CloudProvider.ONEDRIVE, account.id, account.id, account.username ?: "OneDrive")))
                }
                override fun onError(exception: MsalException) { result(Result.failure(FsException("OneDrive 授权失败，请检查网络、应用注册和账号策略"))) }
                override fun onCancel() { result(Result.failure(CloudLoginCancelled())) }
            }).build())
    }

    /** Remove only this application's authorization/cache, never the phone's system account. */
    fun signOut(account: CloudAccount) {
        if (account.provider == CloudProvider.GOOGLE) {
            lastGoogleTokens.remove(account.handle)
            Tasks.await(google.revokeAccess(RevokeAccessRequest.builder().setAccount(Account(account.handle, "com.google"))
                .setScopes(googleScopes).build()), 30, TimeUnit.SECONDS)
        } else microsoft().getAccount(account.handle)?.let { if (!microsoft().removeAccount(it)) throw FsException("退出 OneDrive 失败，请重试") }
    }

    @Suppress("DEPRECATION")
    fun redirectUri(): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo!!.apkContentsSigners
            else context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES).signatures!!
        val hash = Base64.encodeToString(MessageDigest.getInstance("SHA-1").digest(signatures.first().toByteArray()), Base64.NO_WRAP)
        return "msauth://${context.packageName}/${URLEncoder.encode(hash, "UTF-8")}"
    }
}
