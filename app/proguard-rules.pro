# sshj and BouncyCastle load algorithms reflectively by name.
-keep class net.schmizz.** { *; }
-keep class com.hierynomus.** { *; }
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn net.schmizz.**
-dontwarn com.hierynomus.**
-dontwarn org.slf4j.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# smbj (and its event bus) plus the DCE/RPC share listing resolve handlers and packets reflectively.
-keep class com.hierynomus.smbj.** { *; }
-keep class com.rapid7.client.dcerpc.** { *; }
-keep class net.engio.mbassy.** { *; }

# Optional codecs and Java EE pieces the libraries can use but we never call.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn javax.el.**
-dontwarn java.rmi.**
