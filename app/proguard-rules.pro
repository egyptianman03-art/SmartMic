# Smart Mic – ProGuard Rules
# Keep FFmpegKit classes intact for release builds
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class com.arthenica.smartexception.** { *; }

# Keep Bluetooth / audio reflection targets
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
