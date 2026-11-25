# --- kotlinx.serialization ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class com.simplechat.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.simplechat.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.simplechat.app.**$$serializer { *; }

# --- OkHttp ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Room ---
-keep class * extends androidx.room.RoomDatabase { <init>(); }

# ── ONNX Runtime ────────────────────────────────────────────
# JNI 侧按类名/方法名反查 JVM 对象，混淆后直接找不到。
-keep class ai.onnxruntime.** { *; }

# ── Rhino ───────────────────────────────────────────────────
# 模块脚本通过反射拿 host 对象，方法名被重命名后 requestLocalAction 就调不到了。
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-keep interface mychat.framework.hook.HostBridge { *; }
-keep class mychat.framework.hook.GatedHostBridge { public *; }
