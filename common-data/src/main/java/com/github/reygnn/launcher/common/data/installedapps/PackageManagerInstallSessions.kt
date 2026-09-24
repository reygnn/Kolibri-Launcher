package com.github.reygnn.launcher.common.data.installedapps

import android.content.pm.PackageManager
import com.github.reygnn.launcher.core.InstallSessionInspector
import com.github.reygnn.launcher.core.IoDispatcher
import com.github.reygnn.launcher.core.TimberWrapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [InstallSessionInspector] over `PackageManager.getPackageInstaller()` (AUDIT-1 F7 review,
 * fix 3). A package counts as mid-install/restore iff a live install/restore session targets
 * it — the same signal AOSP Launcher3 consults before it keeps a placement as a promise icon.
 *
 * **Deliberately NOT filtered by `SessionInfo.isActive`** (AUDIT-1 F7 branch review): `isActive`
 * reflects only momentary forward progress (a stream in flight / a commit running), so a
 * committed-but-installing or queued restore session reports `isActive == false` for most of its
 * life. During a multi-app device restore that is the majority of sessions at any instant.
 * Filtering on it would omit exactly those pending-restore packages from the returned set — and
 * because a mid-restore app is genuinely not installed yet, the [AppPresence] arm cannot rescue it
 * either (its KDoc says so), so the candidate would be BOTH absent AND (apparently) session-less
 * and get pruned permanently: the exact F7 loss this gate exists to prevent. Launcher3's
 * `PackageInstallerCompat` keys sessions on a non-null `appPackageName` and does NOT filter on
 * `isActive`; this impl matches that (keep-biased), so every session with a readable target
 * package counts.
 *
 * Uses `getAllSessions()` (not `getMySessions()`): a restore/install session is created by
 * the installer or the restore agent, not by this launcher, so we must see sessions we do
 * not own. Foreign-session visibility (and a non-null `appPackageName`) is granted to the
 * active default home app; when the consuming launcher (nyx or kolibri) is not the current
 * default this may return nothing, in which case the presence check and its fail-safe still
 * govern.
 *
 * **Fail-safe:** any failure resolves to `null` ("undetermined"), which the caller treats as
 * keep-every-candidate — the per-package keep contract expressed once for the whole set
 * (AUDIT-1 F7 review point 5; was `true` before the batch migration). If sessions cannot be
 * read, the conservative choice is to not prune — a lingering dead placement self-heals on the next
 * reconcile once the query works, whereas a wrongly-pruned placement during restore is
 * unrecoverable. The broad `Throwable` catch is the sanctioned system-API-boundary form;
 * `CancellationException` still propagates. Runs on [dispatcher] (the query is blocking);
 * consulted at most ONCE per reconcile pass (only when some candidate is absent from presence),
 * then membership-tested per candidate in the use-case — never re-enumerated per key.
 */
@Singleton
class PackageManagerInstallSessions @Inject constructor(
    private val packageManager: PackageManager,
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : InstallSessionInspector {

    override suspend fun activeSessionPackages(): Set<String>? = withContext(dispatcher) {
        try {
            // ONE enumeration per reconcile pass (point 5): map every live session to its
            // target package — deliberately NOT filtered by isActive (see the KDoc: that would
            // drop pending/committed restore sessions and re-open F7). A session with no readable
            // appPackageName (foreign session with no name granted, e.g. the consuming launcher is
            // not the current default) contributes nothing.
            packageManager.packageInstaller.allSessions
                .asSequence()
                .mapNotNull { it.appPackageName }
                .toHashSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Fail-safe: sessions could not be read → null ("undetermined"), so the caller keeps
            // every candidate. A lingering dead placement self-heals on the next reconcile once
            // the query works; a wrongly-pruned restore is unrecoverable.
            // reportToAcra, NOT silentError: the "resolves to null" fail-safe must hold in EVERY
            // build — a silentError DEBUG throw would escape instead of returning null (and land
            // mislabeled in the reconcile's STORE_FAILED catch). The RELEASE signal is kept via ACRA.
            TimberWrapper.reportToAcra(e, "Install-session read failed; failing safe to keep (undetermined)")
            null
        }
    }
}
