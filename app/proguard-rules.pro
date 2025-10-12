# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# Keep launcher components
-keep class com.github.reygnn.kolibri_launcher.MainActivity
-keep class com.github.reygnn.kolibri_launcher.AppInfo
-keep class com.github.reygnn.kolibri_launcher.FavoritesManager
-keep class com.github.reygnn.kolibri_launcher.WallpaperManager
-keep class com.github.reygnn.kolibri_launcher.ThemeManager
-keep class com.github.reygnn.kolibri_launcher.AppUsageManager
-keep class com.github.reygnn.kolibri_launcher.AppVisibilityManager

# Keep fragments
-keep class com.github.reygnn.kolibri_launcher.HomeFragment
-keep class com.github.reygnn.kolibri_launcher.AppDrawerFragment

# Keep settings activity if you have one
-keep class com.github.reygnn.kolibri_launcher.SettingsActivity

# Keep accessibility service and related classes
-keep class com.github.reygnn.kolibri_launcher.ScreenLockAccessibilityService { *; }
-keep class com.github.reygnn.kolibri_launcher.ScreenLockManager { *; }

# Keep all accessibility services in general
-keep class * extends android.accessibilityservice.AccessibilityService { *; }

# Annotation processing (compile-time only, not needed at runtime)
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions


# ====== ACRA 5.11.4 ======
-keep class org.acra.** { *; }
-keep interface org.acra.** { *; }
-dontwarn org.acra.**

-keepclassmembers class * {
    @org.acra.annotation.* *;
}

# AutoService
-dontwarn com.google.auto.service.**
-dontwarn javax.annotation.processing.**

# Kotlin
-keep class kotlin.Metadata { *; }
-keepattributes *Annotation*
-keepattributes Signature

# ====== Hilt ======
-keepnames @dagger.hilt.android.lifecycle.HiltViewModel class * extends androidx.lifecycle.ViewModel

# ====== Debug Info ======
-keepattributes SourceFile,LineNumberTable