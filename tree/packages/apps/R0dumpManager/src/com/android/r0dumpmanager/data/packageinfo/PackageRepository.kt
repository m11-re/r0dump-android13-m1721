package com.android.r0dumpmanager.data.packageinfo

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import kotlinx.coroutines.delay

data class TargetApplication(
    val packageName: String,
    val label: String,
    val launchable: Boolean,
    val icon: Drawable,
    val isSystem: Boolean,
)

class PackageRepository(
    private val context: Context,
) {
    private val packageManager = context.packageManager

    fun loadInstalledApplications(): List<TargetApplication> = packageManager
        .getInstalledApplications(PackageManager.MATCH_ALL)
        .mapNotNull { applicationInfo ->
            val packageName = applicationInfo.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            val launchable = packageManager.getLaunchIntentForPackage(packageName) != null
            TargetApplication(
                packageName = packageName,
                label = applicationInfo.loadLabel(packageManager)?.toString() ?: packageName,
                launchable = launchable,
                icon = applicationInfo.loadIcon(packageManager),
                isSystem = applicationInfo.flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0,
            )
        }
        .distinctBy { it.packageName }
        .sortedWith(
            compareByDescending<TargetApplication> { it.launchable }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.label },
        )

    fun launch(packageName: String): Result<Unit> = runCatching {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?: error("No launcher activity for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    suspend fun forceStopAndWait(packageName: String): Result<Unit> = runCatching {
        val activityManager = context.getSystemService(ActivityManager::class.java)
            ?: error("ActivityManager unavailable")
        activityManager.forceStopPackage(packageName)
        repeat(20) {
            if (activityManager.runningAppProcesses.orEmpty().none { packageName in it.pkgList.orEmpty() }) {
                return@runCatching
            }
            delay(100)
        }
        error("Target process did not exit")
    }
}
