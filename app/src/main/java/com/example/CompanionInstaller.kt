package com.example

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

object CompanionInstaller {

    const val COMPANION_PACKAGE_NAME = "com.SensorsOff.tile"
    const val TILE_SERVICE_CLASS_NAME = "com.example.tile.SensorsOffTileService"
    private const val ASSET_FILE_NAME = "tile-companion.apk"
    private const val TAG = "CompanionInstaller"

    sealed class CompanionValidationResult {
        object NotInstalled : CompanionValidationResult()
        data class Damaged(val reason: String) : CompanionValidationResult()
        data class Valid(val versionName: String) : CompanionValidationResult()

        val isInstalled: Boolean get() = this is Valid
    }

    /**
     * Authoritative package validation:
     * 1. Package existence
     * 2. TileService declared & accessible
     * 3. Log ContentProvider registered
     * 4. APK Signature matches main app
     */
    fun validateCompanionPackage(context: Context): CompanionValidationResult {
        val pm = context.packageManager

        // 1. Verify package exists
        val packageInfo = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(
                    COMPANION_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(COMPANION_PACKAGE_NAME, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            return CompanionValidationResult.NotInstalled
        } catch (e: Exception) {
            return CompanionValidationResult.Damaged("Package query error: ${e.message}")
        }

        // 2. Verify TileService exists
        val serviceComponent = ComponentName(COMPANION_PACKAGE_NAME, TILE_SERVICE_CLASS_NAME)
        val serviceInfo = try {
            pm.getServiceInfo(serviceComponent, 0)
        } catch (e: Exception) {
            null
        }
        if (serviceInfo == null) {
            return CompanionValidationResult.Damaged("Quick Settings TileService is missing or disabled in companion APK")
        }

        // 3. Verify Log ContentProvider exists
        val providerInfo = pm.resolveContentProvider(TilePluginLog.LOG_PROVIDER_AUTHORITY, 0)
        if (providerInfo == null) {
            return CompanionValidationResult.Damaged("Telemetry LogProvider is missing or unregistered in companion APK")
        }

        // 4. Verify cryptographic signature match
        val sigMatch = pm.checkSignatures(context.packageName, COMPANION_PACKAGE_NAME)
        if (sigMatch != PackageManager.SIGNATURE_MATCH) {
            Log.w(TAG, "Signature check result: $sigMatch (expected SIGNATURE_MATCH 0)")
            // If debug certs match or signatures match
            if (sigMatch != PackageManager.SIGNATURE_MATCH) {
                return CompanionValidationResult.Damaged("Companion APK signature does not match main app signature ($sigMatch)")
            }
        }

        return CompanionValidationResult.Valid(packageInfo.versionName ?: "2.8.9")
    }

    /**
     * Determines whether the Quick Tile Companion APK is installed using authoritative PackageManager queries.
     * Does NOT use a saved preference.
     */
    fun isCompanionInstalled(context: Context): Boolean {
        return validateCompanionPackage(context).isInstalled
    }

    /**
     * Returns the version name of the installed companion APK, or null if not installed.
     */
    fun getInstalledCompanionVersion(context: Context): String? {
        return when (val result = validateCompanionPackage(context)) {
            is CompanionValidationResult.Valid -> result.versionName
            else -> null
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
     * Extracts the bundled signed companion APK from assets to the scoped cache directory
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
     * Launches the official user-confirmed Android package uninstallation flow targeting
     * EXCLUSIVELY "com.SensorsOff.tile". Never targets the main package.
     */
    fun launchCompanionUninstallFlow(context: Context): Result<Unit> {
        return runCatching {
            val packageUri = Uri.parse("package:$COMPANION_PACKAGE_NAME")
            val uninstallIntent = Intent(Intent.ACTION_DELETE, packageUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(uninstallIntent)
        }.onFailure { e ->
            Log.e(TAG, "Failed to launch companion uninstall flow", e)
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
