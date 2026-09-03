# Kolibri Launcher — app-specific R8 rules.
#
# Rewritten 2026-09-03. The previous file kept every app class verbatim
# (`-keep class com.github.reygnn.kolibri_launcher.** { *; }`) plus all of
# `org.acra.**`, which switched shrinking and obfuscation off for ~1.900 of
# the ~4.150 release classes and inflated the release DEX to 4.6 MB.
#
# Everything a library needs for itself ships as consumer rules inside its
# own AAR/JAR and is merged automatically — do NOT duplicate it here:
#   - Hilt/Dagger: entry points + @HiltViewModel keys (LazyClassKey via
#     -identifiernamestring, obfuscation-safe)
#   - kotlinx-serialization: serializer()/Companion/INSTANCE of @Serializable
#   - androidx.fragment: no-arg <init> of every Fragment (FragmentFactory)
#   - ACRA: Plugin ServiceLoader impls, org.acra enums, Configuration ctors
#   - AGP defaults (proguard-android-optimize.txt): enum values()/valueOf(),
#     Parcelable CREATOR, View setters/getters, annotation attributes
# Classes referenced from the manifest, layouts, nav graph and preference XML
# are kept by the aapt2-generated rules.
# The merged result is written to
# app/build/outputs/mapping/release/configuration.txt — audit there.

# ---- Stack traces ---------------------------------------------------------
# Keep line numbers; original file names are not needed (the mapping uploaded
# by :app:uploadProguardMapping retraces both).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---- ACRA -----------------------------------------------------------------
# ReportCarrier.buildAcraReportThrowable() embeds the cause's simple class name
# in the LoggedThrowable *message* for server-side grouping. Messages are plain
# text and are not retraced, so app-defined Throwables keep their names
# (name-only: shrinking and optimization still apply).
-keepnames class com.github.reygnn.kolibri_launcher.** extends java.lang.Throwable

# ACRA reads the BuildConfig constants reflectively (ReportField.BUILD_CONFIG)
# through the class handed over as `buildConfigClass` in CrashReportingBootstrap.
-keep class com.github.reygnn.kolibri_launcher.BuildConfig { *; }

# ACRA instantiates the classes named in its configuration via
# Class.newInstance() (InstanceCreator): retryPolicyClass, keyStoreFactoryClass,
# attachmentUriProvider, … Its consumer rules only keep `Plugin` implementations
# (senders, collectors, administrators), NOT these — so R8 stripped the unused
# no-arg constructors of NoKeyStoreFactory / DefaultRetryPolicy /
# DefaultAttachmentProvider and every send failed with
# "has no zero argument constructor" (found by the -PdailyDriver device test,
# 2026-09-03). Keep the no-arg ctor of any implementation (covers app-provided
# ones too) plus, as a safety net for reflective sites not listed here, of every
# live org.acra class — constructors only, no class is force-kept.
-keepclassmembers class * implements org.acra.config.RetryPolicy { public <init>(); }
-keepclassmembers class * implements org.acra.security.KeyStoreFactory { public <init>(); }
-keepclassmembers class * implements org.acra.attachment.AttachmentUriProvider { public <init>(); }
-keepclassmembers class org.acra.** { public <init>(); }

# ---- Enums persisted by name ----------------------------------------------
# Enum constants are stored as their NAME (DataStore settings, backup JSON,
# savedInstanceState) and restored via valueOf()/enumValueOf<>(). Keep the
# constant names so stored values survive a rebuild — obfuscation would
# rename them between releases and silently reset every stored enum setting
# on update. Name-only rule; the classes themselves are not force-kept.
-keepclassmembernames enum com.github.reygnn.kolibri_launcher.** { <fields>; }

# ---- ACRA packaging wart --------------------------------------------------
# acra-core/acra-http declare `com.google.auto.service:auto-service` (the
# annotation PROCESSOR, not just the annotations) as a runtime dependency, so
# AutoServiceProcessor sits on the release classpath and references the JDK
# javax.annotation.processing API that Android does not have. It is unreachable
# and R8 strips it; the warnings merely have to be silenced.
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions

# ACRA logs under `ACRA::class.java.simpleName` — without this the release
# logcat tag collapses to "a". Name-only, one class.
-keepnames class org.acra.ACRA
