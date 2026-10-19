# --- smbj ---
# Kerberos (GSS) authentication and mbassador's EL filters are optional and unused.
-dontwarn org.ietf.jgss.**
-dontwarn javax.el.**
# mbassador finds event handlers by annotation at runtime.
-keep class net.engio.mbassy.** { *; }
-keepclassmembers class * {
    @net.engio.mbassy.listener.Handler *;
}

# --- BouncyCastle (sshj, smbj) ---
# The JCA provider registers algorithm classes by name; keep the provider tables and SPIs.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-dontwarn javax.naming.**

# --- sshj ---
-dontwarn org.slf4j.**
-dontwarn net.i2p.crypto.eddsa.**

# --- commons-net ---
# Listing parsers can be created by class name for unusual server types.
-keep class org.apache.commons.net.ftp.parser.** { *; }
