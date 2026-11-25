package io.github.oinzc.flamecontractor.execution.login

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build

class DouyinAppLauncher(context: Context) {

    private val appContext: Context = context.applicationContext ?: context

    fun detectInstalledVariant(): DouyinClientVariant? {
        val pm = appContext.packageManager
        val installed = LinkedHashSet<String>()
        for (variant in DouyinClientVariant.entries) {
            val present = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    pm.getPackageInfo(
                        variant.packageName,
                        PackageManager.PackageInfoFlags.of(0),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    pm.getPackageInfo(variant.packageName, 0)
                }
                true
            }.getOrDefault(false)
            if (present) {
                installed += variant.packageName
            }
        }
        val byPkg = DouyinSchemeBuilder.resolvePreferredDouyinClient(installed)
        if (byPkg != null) return byPkg

        // 兜底：按 Scheme 探测是否存在可处理的抖音客户端
        for (variant in DouyinClientVariant.entries) {
            val probeIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(DouyinSchemeBuilder.buildScanCameraDeepLink(variant)),
            )
            val resolved = runCatching {
                pm.resolveActivity(probeIntent, 0) != null
            }.getOrDefault(false)
            if (resolved) return variant
        }
        return null
    }

    fun launchConfirmLogin(
        variant: DouyinClientVariant,
        validatedIndexUrl: String,
    ): Boolean {
        val safeUrl = LoginQrParser.validateQrIndexUrl(validatedIndexUrl) ?: return false
        val deepLink = DouyinSchemeBuilder.buildConfirmLoginDeepLink(variant, safeUrl)
        return launchDeepLink(deepLink, preferredPackage = variant.packageName)
    }

    fun launchScanCamera(variant: DouyinClientVariant): Boolean {
        val deepLink = DouyinSchemeBuilder.buildScanCameraDeepLink(variant)
        return launchDeepLink(deepLink, preferredPackage = variant.packageName)
    }

    private fun launchDeepLink(deepLink: String, preferredPackage: String): Boolean {
        val uri = Uri.parse(deepLink)
        val targeted = Intent(Intent.ACTION_VIEW, uri).apply {
            setPackage(preferredPackage)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            appContext.startActivity(targeted)
            return true
        } catch (_: ActivityNotFoundException) {
            // 部分改包或分身环境包名不匹配时回退隐式 Scheme 唤起
        } catch (_: SecurityException) {
            return false
        }

        val implicit = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            appContext.startActivity(implicit)
            true
        } catch (_: Throwable) {
            false
        }
    }
}
