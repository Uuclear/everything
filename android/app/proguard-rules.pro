# Keep Moshi 反射/生成类与 Retrofit 接口
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep class com.everything.eve.api.** { *; }

# JNA / libsodium 原生绑定
-keep class com.sun.jna.** { *; }
-keep class com.goterl.** { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**
