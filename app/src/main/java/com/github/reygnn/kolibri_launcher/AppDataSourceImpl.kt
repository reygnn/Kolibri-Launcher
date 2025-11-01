package com.github.reygnn.kolibri_launcher

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppDataSourceImpl @Inject constructor(
    @param:ApplicationContext private val context: Context
) : AppDataSource {

    override suspend fun getInstalledComponents(): Set<String> {
        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null)
                .addCategory(Intent.CATEGORY_LAUNCHER)

            pm.queryIntentActivities(mainIntent, 0)
                .map { resolveInfo ->
                    val activityInfo = resolveInfo.activityInfo
                    val pkg = activityInfo.packageName
                    val cls = activityInfo.name // z.B. "com.app1.MainActivity"

                    // Wandle FQCN in ".MainActivity" um
                    val shortCls = if (cls.startsWith("$pkg.")) {
                        cls.substring(pkg.length) // Ergibt ".MainActivity"
                    } else {
                        cls // Fallback
                    }

                    "$pkg/$shortCls" // Ergibt "com.app1/.MainActivity"
                }
                .toSet()
        } catch (e: Exception) {
            TimberWrapper.silentError(e, "Error getting installed components")
            emptySet()
        }
    }
}