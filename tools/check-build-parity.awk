# =============================================================================
# A10 — build parity (SPEC_NYX_REWRITE A10): modules do not set build-wide values
# =============================================================================
# Per-file scan of a MODULE build script (<module>/build.gradle.kts). The numbers and
# shared setup live once in build-logic (launcher.* convention plugins); a module that
# sets them again reopens the drift 1b removed. Flags, in code (comments ignored):
#   - SDK levels, Java/Kotlin version, toolchains, bytecode target
#   - lint {} / testOptions {} / unit-test flags / proguardFiles(...)
#   - kotlin { } blocks, a bare kotlin("jvm"), an alias(libs.plugins.android.*)
#   - a module that applies no launcher.* convention plugin at all
# consumerProguardFiles(...) stays allowed: consumer rules are library-specific.
# Output: path:line: reason: code
# =============================================================================
function flag(reason, text) { printf "%s:%d: %s: %s\n", FILENAME, FNR, reason, text }
function endfile() { if (file != "" && !launcher) printf "%s:1: A10 module applies no launcher.* convention plugin\n", file }
FNR == 1 { endfile(); inblock = 0; launcher = 0; file = FILENAME }
{
  orig = $0; line = $0
  if (inblock) { if (line ~ /\*\//) { sub(/^.*\*\//, "", line); inblock = 0 } else next }
  sub(/\/\/.*/, "", line)             # line comments first: a "/*" inside one opens no block
  while (match(line, /\/\*/)) {       # block comments: drop closed ones, open the rest
    rest = substr(line, RSTART + 2)
    if (match(rest, /\*\//)) { line = substr(line, 1, index(line, "/*") - 1) substr(rest, RSTART + 2) }
    else { line = substr(line, 1, index(line, "/*") - 1); inblock = 1; break }
  }
  if (line ~ /^[ \t]*\*/) next
  if (line ~ /id\("launcher\.[a-z.]+"\)/) launcher = 1
  if (line ~ /(^|[^A-Za-z])(compileSdk|minSdk|targetSdk)[ \t]*=/)        flag("A10 SDK level set in the module (build-logic LauncherBuild)", orig)
  if (line ~ /jvmToolchain\(|JavaLanguageVersion|JavaVersion\.VERSION_|JvmTarget\./) flag("A10 Java/Kotlin version or toolchain set in the module (build-logic)", orig)
  if (line ~ /(source|target)Compatibility[ \t]*=/)                       flag("A10 compileOptions set in the module (build-logic)", orig)
  if (line ~ /^[ \t]*(lint|testOptions|kotlin)[ \t]*\{/)                   flag("A10 shared block configured in the module (build-logic)", orig)
  if (line ~ /isReturnDefaultValues|isIncludeAndroidResources/)           flag("A10 unit-test flag set in the module (build-logic)", orig)
  if (line ~ /(^|[^A-Za-z])proguardFiles?\(/)                             flag("A10 proguardFiles set in the module (build-logic ReleaseRules)", orig)
  if (line ~ /kotlin\("jvm"\)|alias\(libs\.plugins\.android\.(application|library|test)\)/) flag("A10 base plugin applied directly — use the launcher.* convention plugin", orig)
}
END { endfile() }
