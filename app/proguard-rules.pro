# SpeedSync ProGuard / R8 Rules

# Preserve line numbers and source file attributes for crash stack traces
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# App entry points and components
-keep class com.memamun.speedsync.MainActivity { *; }
-keep class com.memamun.speedsync.service.SpeedMeterService { *; }
-keep class com.memamun.speedsync.service.BootReceiver { *; }

# Data models and enums
-keep class com.memamun.speedsync.model.** { *; }
-keepclassmembers enum com.memamun.speedsync.model.** { *; }

# Data usage repository and helper
-keep class com.memamun.speedsync.data.DataUsageRepository { *; }
-keep class com.memamun.speedsync.network.NetworkHelper** { *; }
-keep class com.memamun.speedsync.network.SpeedTestEngine { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# Jetpack Compose
-keep class androidx.compose.material.icons.** { *; }
-dontwarn androidx.compose.**
