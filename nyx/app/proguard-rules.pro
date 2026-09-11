# Nyx Launcher — app-side keep rules.
# Hilt, kotlinx.serialization and DataStore ship their own consumer rules.

# ---- ACRA packaging wart --------------------------------------------------
# acra-core/acra-http declare `com.google.auto.service:auto-service` (the
# annotation PROCESSOR, not just the annotations) as a runtime dependency, so
# AutoServiceProcessor sits on the release classpath and references the JDK
# javax.annotation.processing API that Android does not have. It is unreachable
# and R8 strips it; the warnings merely have to be silenced. (Same rule as
# Kolibri-Launcher's proguard-rules.pro — Nyx pulls ACRA via :feature-crashreporting.)
-dontwarn javax.annotation.processing.AbstractProcessor
-dontwarn javax.annotation.processing.SupportedOptions
