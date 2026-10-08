package com.github.reygnn.launcher.feature.wallpaper

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.reygnn.launcher.common.data.safePurge
import com.github.reygnn.launcher.core.AppConstants
import com.github.reygnn.launcher.core.OwnsSettingsStoreKeys
import com.github.reygnn.launcher.core.Purgeable
import com.github.reygnn.launcher.core.SettingsStore
import com.github.reygnn.launcher.core.TimberWrapper
import com.github.reygnn.launcher.core.wallpaper.WallpaperBackdrop
import com.github.reygnn.launcher.core.wallpaper.WallpaperDisplaySettings
import com.github.reygnn.launcher.core.wallpaper.WallpaperSurfaceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The key names of the wallpaper display settings in an app's settings store (3a-4, D3). They
 * stay per app because unifying them would need a data migration: Kolibri keeps its old surface
 * name `app_drawer_mode`, Nyx uses `wallpaper_surface_mode`.
 */
data class WallpaperDisplayKeys(
    val scrimAlpha: String,
    val backdrop: String,
    val surfaceMode: String,
)

/**
 * The wallpaper display settings — scrim, backdrop, surface mode — in the app's settings store
 * (SPEC_NYX_REWRITE 3a-4, Ue7 moved out of Kolibri's `SettingsRepositoryImpl`). One instance per
 * app (`@Singleton`): Kolibri binds `WallpaperDisplaySettings` to it AND delegates its
 * `SettingsRepository` members to it, so both reach the same object.
 *
 * Reads (D5, decided 03.10. for both apps — values on Home's start path, the named exception in
 * DATASTORE_READ_SPEC): no read of a display value can throw into a collector.
 *  - **Upstream:** a read error that is an [Exception] (not a cancellation) is logged with
 *    `silentError` and falls back to the defaults (Kolibri's policy, now for both apps).
 *  - **Per value:** each value is read TYPED with a safe cast (`asMap()[key] as? T`), never through
 *    the unchecked `prefs[key]`, whose `ClassCastException` would only surface wherever the value
 *    is used. A value of a foreign type under a key (an old install, say) is reported once with
 *    `silentError` and read as the default. `silentError` is loud in DEBUG — an old value of the
 *    wrong type crashes a debug build on purpose (house rule); release reads the default.
 *  Why: these flows are collected via `stateIn` on Home's start path; an exception there would
 *  end the process on every start (O6 tracks that wider question).
 *  - **Writes** swallow their failure after logging (D4) — in both apps since 3b-2.
 *  - **Purge** rethrows (F1), so the reset reports a partial failure. Who purges it differs per
 *    app: Kolibri through `SettingsRepositoryImpl`, Nyx directly in its `ResetRepositoryImpl`
 *    (Nyx has no `SettingsRepository`). Never both — the store would be purged twice.
 *
 * Keep-list (D6): the store owns its three keys ([ownedExactKeys]). The key properties are
 * UPPER_CASE on purpose, so the keep-list gate checks each of them for registration like a
 * constant key. Known limit: the names themselves come from [WallpaperDisplayKeys], not as
 * literals here, so the gate cannot see the strings — a unit test and the
 * `ResetCompletenessContract` pin them.
 */
@Singleton
class WallpaperDisplaySettingsStore @Inject constructor(
    @param:SettingsStore private val dataStore: DataStore<Preferences>,
    keys: WallpaperDisplayKeys,
) : WallpaperDisplaySettings, Purgeable, OwnsSettingsStoreKeys {

    private val SCRIM_ALPHA = floatPreferencesKey(keys.scrimAlpha)
    private val BACKDROP = stringPreferencesKey(keys.backdrop)
    private val SURFACE_MODE = stringPreferencesKey(keys.surfaceMode)

    override val wallpaperScrimAlphaStateFlow: Flow<Float> =
        safeData.map { it.typedOrNull<Float>(SCRIM_ALPHA) ?: AppConstants.DEFAULT_WALLPAPER_SCRIM_ALPHA }

    override suspend fun setWallpaperScrimAlpha(alpha: Float) = safeEdit { it[SCRIM_ALPHA] = alpha }

    override val wallpaperSurfaceModeFlow: Flow<WallpaperSurfaceMode> =
        safeData.map { it.typedOrNull<String>(SURFACE_MODE).toEnumOr(WallpaperSurfaceMode.AUTO) }

    override suspend fun setWallpaperSurfaceMode(mode: WallpaperSurfaceMode) = safeEdit { it[SURFACE_MODE] = mode.name }

    override val wallpaperBackdropFlow: Flow<WallpaperBackdrop> =
        safeData.map { it.typedOrNull<String>(BACKDROP).toEnumOr(WallpaperBackdrop.SYSTEM_WALLPAPER) }

    /**
     * Writes the backdrop (settings, restore, any other writer) — and keeps the toggle's memory
     * honest (3a-9d): under [backdropToggleLock], [lastWrittenBackdrop] becomes the written value
     * if the write landed, else null ("unknown — read the persisted value on the next toggle").
     * Outward error behaviour unchanged: swallowed after `silentError` (D4).
     */
    override suspend fun setWallpaperBackdrop(backdrop: WallpaperBackdrop) {
        backdropToggleLock.withLock {
            lastWrittenBackdrop = if (tryEdit { it[BACKDROP] = backdrop.name }) backdrop else null
        }
    }

    override fun ownedExactKeys(): Set<String> = setOf(SCRIM_ALPHA.name, BACKDROP.name, SURFACE_MODE.name)

    /**
     * Factory reset: removes the three keys; a failure is rethrown by `safePurge` (F1). The
     * toggle's memory is forgotten under [backdropToggleLock] in any case (3a-9d), also when the
     * purge throws — the next toggle reads the persisted value.
     */
    override suspend fun purgeRepository() {
        backdropToggleLock.withLock {
            try {
                dataStore.safePurge("WallpaperDisplaySettingsStore") { preferences ->
                    preferences.remove(SCRIM_ALPHA)
                    preferences.remove(BACKDROP)
                    preferences.remove(SURFACE_MODE)
                }
            } finally {
                lastWrittenBackdrop = null
            }
        }
    }

    /**
     * The value under [key] if it has the expected type [T]; null when absent — and null, reported
     * once per key, when a value of a foreign type sits there (D5, see the class KDoc).
     */
    private inline fun <reified T : Any> Preferences.typedOrNull(key: Preferences.Key<T>): T? {
        val raw = asMap()[key] ?: return null
        val typed = raw as? T
        if (typed == null && reportedForeignTypes.add(key.name)) {
            TimberWrapper.silentError(
                IllegalStateException("${key.name} holds a ${raw::class.java.simpleName}"),
                "Wallpaper display settings: value of a foreign type, reading the default",
            )
        }
        return typed
    }

    /** Keys already reported for a foreign type — once per key and process, not per emission. */
    private val reportedForeignTypes: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The upstream read policy for both apps: an upstream [Exception] falls back to the defaults. */
    private val safeData: Flow<Preferences>
        get() = dataStore.data.catch { e ->
            if (e is CancellationException || e !is Exception) throw e
            TimberWrapper.silentError(e, "Wallpaper display settings: read error, falling back to defaults")
            emit(emptyPreferences())
        }

    /** The setters' write: swallows its failure after logging (D4). */
    private suspend fun safeEdit(block: (MutablePreferences) -> Unit) {
        tryEdit(block)
    }

    /**
     * A write that also reports whether it landed (3a-9b), for [toggleBackdrop]. Same outward
     * error behaviour as [safeEdit]: logged with `silentError`, never thrown.
     */
    private suspend fun tryEdit(block: (MutablePreferences) -> Unit): Boolean =
        try {
            dataStore.edit(block)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            // Catch kept (Expected error, four-category frame): a setter swallows its failure like
            // Kolibri's settings did (D4); OOM extends Error → Throwable.
            TimberWrapper.silentError(e, "Wallpaper display settings: write error")
            false
        }

    // ---- backdrop toggle (3a-9b, K3) ----

    /**
     * Serializes every touch of the backdrop memory: [toggleBackdrop] (read-modify-write),
     * [setWallpaperBackdrop] and [purgeRepository]. **Never taken together with any other lock**
     * (no lock inside it, and it is not taken under another one); kotlinx `Mutex` is not
     * reentrant, so nothing inside it calls those three again.
     */
    private val backdropToggleLock = Mutex()

    /**
     * The backdrop value last actually PERSISTED by this store (toggle or setter), or null when
     * unknown (nothing written yet, a failed setter write, a purge). A rapid double-tap flips from
     * it instead of the write→read-lagged flow, so two taps net to a no-op. The toggle advances it
     * only after a write that landed, so a failed toggle leaves the next one aiming at the same
     * target again; every other writer keeps it in step (3a-9d), so it never goes stale.
     */
    private var lastWrittenBackdrop: WallpaperBackdrop? = null

    /**
     * Flips the backdrop between the system wallpaper and black and persists it (3a-9b, moved from
     * Kolibri's delegate; Nyx's coordinator follows in 3b). Reads the PERSISTED value
     * (`lastWritten ?: first()`), not a `stateIn` value that is the default while nobody
     * subscribes. Error behaviour as the setters: a failed write is logged, never thrown — and a
     * failed toggle leaves [lastWrittenBackdrop] as it was, so the next tap aims at the same
     * target again (a failed setter write resets it instead, see [setWallpaperBackdrop]). Before 3a-9b that
     * "only after a successful write" rule relied on a throw that never came in production (the
     * setters have swallowed since before 3a-4), so it silently did not hold.
     */
    suspend fun toggleBackdrop() {
        backdropToggleLock.withLock {
            val current = lastWrittenBackdrop ?: wallpaperBackdropFlow.first()
            val next = when (current) {
                WallpaperBackdrop.SYSTEM_WALLPAPER -> WallpaperBackdrop.BLACK
                WallpaperBackdrop.BLACK -> WallpaperBackdrop.SYSTEM_WALLPAPER
            }
            if (tryEdit { it[BACKDROP] = next.name }) lastWrittenBackdrop = next
        }
    }
}

private inline fun <reified E : Enum<E>> String?.toEnumOr(default: E): E =
    // No suspension point — enum parse of a stored string; an unknown name falls back.
    if (this == null) default else enumValues<E>().firstOrNull { it.name == this } ?: default
