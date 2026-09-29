#!/usr/bin/env bash
# =============================================================================
# Regression test for tools/check-build-parity.awk (A10). Same shape as the sibling
# *-test.sh: NOT wired into Gradle; rerun when the detector changes.
# Exit 0 ok, 1 regression.
# =============================================================================
set -u
d="$(cd "$(dirname "$0")" && pwd)"
tmp=$(mktemp -d) || exit 2; trap 'rm -rf "$tmp"' EXIT; fail=0
check() { if [ "$2" != "$3" ]; then echo "FAIL $1: expected [$3], got [$2]"; awk -f "$d/check-build-parity.awk" "$4"; fail=1; else echo "ok   $1"; fi; }
lines() { awk -f "$d/check-build-parity.awk" "$1" | cut -d: -f2 | tr '\n' ' ' | sed 's/ $//'; }

cat > "$tmp/good.gradle.kts" <<'KT'
// :x — a path like domain/core/* in a line comment opens no block
/** One-line KDoc. */
plugins {
    id("launcher.android.library") // SDK, Java/Kotlin 21 (build-logic)
    alias(libs.plugins.ksp)
}
android {
    namespace = "com.example.x"
    defaultConfig {
        consumerProguardFiles("consumer-rules.pro")   // allowed: library-specific
    }
}
// compileSdk = 37 in a comment is fine
KT
check "A10 clean module" "$(lines "$tmp/good.gradle.kts")" "" "$tmp/good.gradle.kts"

cat > "$tmp/bad.gradle.kts" <<'KT'
plugins {
    alias(libs.plugins.android.library)
}
android {
    compileSdk = 37
    defaultConfig { minSdk = 36 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_21 }
    lint {
        abortOnError = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildTypes { release { proguardFiles("proguard-rules.pro") } }
}
kotlin {
    jvmToolchain(21)
}
KT
check "A10 violating module" "$(lines "$tmp/bad.gradle.kts")" "2 5 6 7 7 8 11 12 14 16 17 1" "$tmp/bad.gradle.kts"

[ "$fail" -eq 0 ] && echo "✓ A10 build-parity detector OK" || exit 1
