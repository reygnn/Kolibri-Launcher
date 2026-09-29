# :feature-crashreporting — consumer R8 rules, merged into every app that depends on this
# module (SPEC_NYX_REWRITE D1 end form). ACRA-specific: they belong to the library that
# brings ACRA, so no app can forget them — Nyx's release builds once shipped without them
# and could not send a single crash report (D1).
#
# Global options (-keepattributes, -renamesourcefileattribute) are NOT allowed in library
# consumer rules under AGP 9; they live in the rule file the app convention plugin
# generates (build-logic, launcher.android.application).

# ACRA instantiates these reflectively with a no-arg constructor; R8 would strip it and
# every send fails with "has no zero argument constructor" (found 2026-09-03 in Kolibri).
-keepclassmembers class * implements org.acra.config.RetryPolicy { public <init>(); }
-keepclassmembers class * implements org.acra.security.KeyStoreFactory { public <init>(); }
-keepclassmembers class * implements org.acra.attachment.AttachmentUriProvider { public <init>(); }
-keepclassmembers class org.acra.** { public <init>(); }
-keepnames class org.acra.ACRA

# Referenced by ACRA's annotation-processing artifacts, absent at runtime by design.
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions
