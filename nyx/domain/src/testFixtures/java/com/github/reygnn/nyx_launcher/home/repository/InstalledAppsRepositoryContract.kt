package com.github.reygnn.nyx_launcher.home.repository

/*
 * ============================================================================
 * INSTALLED APPS REPOSITORY — NO CONTRACT TEST (ADR)
 * ============================================================================
 *
 * NO CONTRACT TEST (ADR) — canonical marker read by
 * `./gradlew :nyx:app:checkConventions` (which reuses kolibri's shared
 * tools/check-contract-triple.sh via CONTRACT_REPO_ROOT). Exempts the whole
 * triple: neither a fake- nor an impl-contract test is required for
 * [InstalledAppsRepository]. This file is documentation only — no executable
 * test — which is the intended shape for a justified Rule 2 gap.
 *
 * Rationale:
 *
 *  - System-API wrapper. The impl (InstalledAppsRepositoryImpl) is a thin
 *    wrapper over LauncherApps / PackageManager app enumeration — not honestly
 *    JVM-instantiable, so an impl-contract test would be mock theatre. Its
 *    logic is covered directly by InstalledAppsRepositoryImplTest (JVM, MockK).
 *
 *  - Tautological fake. FakeInstalledAppsRepository is a trivial in-memory
 *    double whose loadInstalledApps() returns a preset AppLoadResult; a
 *    fake-contract test would only assert that preset back to itself and catch
 *    no drift — there is no shared, non-trivial behaviour to pin between the
 *    hand-set fake and the system-driven impl.
 *
 * The one behavioural guarantee that matters — a failed enumeration surfaces as
 * AppLoadResult.Failure, never as an empty device (RHL-INV-1) — is pinned where
 * it actually lives: in InstalledAppsRepositoryImplTest and in the tests of the
 * use cases that consume the result.
 */
