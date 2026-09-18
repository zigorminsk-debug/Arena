# Мост между JS и Android: методы с @JavascriptInterface должны выжить после R8.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

-keepclassmembers class ai.arena.mobile.** {
    public *;
}

# WebView / JS
-keepattributes JavascriptInterface
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AndroidX / Material
-dontwarn androidx.**
-dontwarn org.chromium.**
-dontwarn javax.annotation.**
-keep class androidx.appcompat.widget.** { *; }
