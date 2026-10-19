import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Release signing lives outside the repo (default ~/.twinfile/keystore.properties, or the
// file named by TWINFILE_SIGNING). Without it, release builds fall back to the debug key.
val releaseSigning = Properties().apply {
    val f = file(System.getenv("TWINFILE_SIGNING") ?: "${System.getProperty("user.home")}/.twinfile/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
if (releaseSigning.isEmpty() && System.getenv("TWINFILE_REQUIRE_SIGNING") == "1") {
    throw GradleException("Release signing is required but no keystore.properties was found")
}

android {
    namespace = "app.twinfile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "app.twinfile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (releaseSigning.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseSigning.getProperty("storeFile"))
                storePassword = releaseSigning.getProperty("storePassword")
                keyAlias = releaseSigning.getProperty("keyAlias")
                keyPassword = releaseSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    testOptions {
        // android.util.Log and friends return defaults instead of throwing in JVM tests.
        unitTests.isReturnDefaultValues = true
    }
    packaging {
        resources.excludes += setOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    // Network locations; see README for why each library and its size cost.
    implementation(libs.commons.net)
    implementation(libs.sshj)
    implementation(libs.bcprov)
    implementation(libs.smbj)
    implementation(libs.okhttp)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    // Real servers for the network-source contract tests; test classpath only.
    testImplementation(libs.sshd.sftp)
    testImplementation(libs.ftpserver)
    testImplementation(libs.mockwebserver)
    testImplementation(libs.kxml2)
}

// sshj and smbj pull different BouncyCastle releases whose jars overlap; use one for all.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") useVersion(libs.versions.bouncycastle.get())
    }
}
