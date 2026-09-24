package com.example

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object CompanionInstaller {

    const val COMPANION_PACKAGE_NAME = "com.SensorsOff.tile"
    private const val ASSET_FILE_NAME = "tile-companion.apk"
    private const val TAG = "CompanionInstaller"

    /**
     * Determines whether the Quick Tile Companion APK is installed using authoritative PackageManager queries.
     * Does NOT use a saved preference.
     */
    fun isCompanionInstalled(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    COMPANION_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(COMPANION_PACKAGE_NAME, 0)
            }
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "Error checking companion installation", e)
            false
        }
    }

    /**
     * Returns the version name of the installed companion APK, or null if not installed.
     */
    fun getInstalledCompanionVersion(context: Context): String? {
        return try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    COMPANION_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(COMPANION_PACKAGE_NAME, 0)
            }
            packageInfo.versionName
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Diagnostic data holding resolved ContentProvider information.
     */
    data class ProviderResolutionInfo(
        val isRegistered: Boolean,
        val authority: String,
        val packageName: String?,
        val className: String?,
        val details: String
    )

    /**
     * Resolves the companion ContentProvider authority via PackageManager.
     */
    fun resolveCompanionLogProvider(context: Context): ProviderResolutionInfo {
        val authority = TilePluginLog.LOG_PROVIDER_AUTHORITY
        return try {
            val providerInfo = context.packageManager.resolveContentProvider(
                authority,
                PackageManager.GET_META_DATA
            )
            if (providerInfo != null) {
                ProviderResolutionInfo(
                    isRegistered = true,
                    authority = authority,
                    packageName = providerInfo.packageName,
                    className = providerInfo.name,
                    details = "Resolved: ${providerInfo.packageName}/${providerInfo.name}"
                )
            } else {
                ProviderResolutionInfo(
                    isRegistered = false,
                    authority = authority,
                    packageName = null,
                    className = null,
                    details = "Companion provider NOT registered"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error resolving companion ContentProvider: ${e.message}")
            ProviderResolutionInfo(
                isRegistered = false,
                authority = authority,
                packageName = null,
                className = null,
                details = "Companion provider NOT registered (Error: ${e.message})"
            )
        }
    }

    /**
     * Extracts the bundled companion APK from assets to the scoped cache directory
     * and invokes the Android Package Installer via a secure FileProvider content:// URI.
     */
    fun launchCompanionInstallFlow(context: Context): Result<Unit> {
        return runCatching {
            val apkDir = File(context.cacheDir, "apks")
            if (!apkDir.exists()) {
                apkDir.mkdirs()
            }
            val apkFile = File(apkDir, "tile-companion.apk")

            context.assets.open(ASSET_FILE_NAME).use { input ->
                FileOutputStream(apkFile).use { output ->
                    input.copyTo(output)
                }
            }

            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(installIntent)
        }.onFailure { e ->
            Log.e(TAG, "Failed to launch companion installation flow", e)
        }
    }

    /**
     * Attempts to open the Android Quick Settings shade or settings fallback.
     */
    fun openQuickSettings(context: Context) {
        try {
            val sbm = context.getSystemService(Context.STATUS_BAR_SERVICE)
            val expandMethod = sbm?.javaClass?.getMethod("expandSettingsPanel")
            expandMethod?.invoke(sbm)
        } catch (e: Exception) {
            Log.d(TAG, "StatusBarManager expandSettingsPanel not available, launching Settings fallback", e)
            try {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (ignored: Exception) {
                Log.w(TAG, "Could not launch Settings fallback", ignored)
            }
        }
    }
}
