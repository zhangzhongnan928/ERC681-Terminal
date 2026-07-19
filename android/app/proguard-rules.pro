# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.openpasskey.terminal.data.model.** { *; }
-keep class com.openpasskey.terminal.chain.** { *; }

# Web3j request/response models are serialized by Jackson through reflection.
-keep class org.web3j.protocol.core.Request { *; }
-keep class org.web3j.protocol.core.Response { *; }
-keep class org.web3j.protocol.core.Response$* { *; }
-keep class org.web3j.protocol.core.methods.request.** { *; }
-keep class org.web3j.protocol.core.methods.response.** { *; }

# Optional OkHttp TLS providers and an optional SLF4J binding are not packaged on Android.
-dontwarn org.bouncycastle.jsse.BCSSLParameters
-dontwarn org.bouncycastle.jsse.BCSSLSocket
-dontwarn org.bouncycastle.jsse.provider.BouncyCastleJsseProvider
-dontwarn org.conscrypt.Conscrypt$Version
-dontwarn org.conscrypt.Conscrypt
-dontwarn org.conscrypt.ConscryptHostnameVerifier
-dontwarn org.openjsse.javax.net.ssl.SSLParameters
-dontwarn org.openjsse.javax.net.ssl.SSLSocket
-dontwarn org.openjsse.net.ssl.OpenJSSE
-dontwarn org.slf4j.impl.StaticLoggerBinder
