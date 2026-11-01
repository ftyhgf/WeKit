package io.ge.wawo.loader.startup

import android.content.Context
import io.ge.wawo.constants.PackageNames
import io.ge.wawo.dexkit.cache.DexCacheManager
import io.ge.wawo.features.core.FeaturesLoader
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.loader.utils.ActivityProxy
import io.ge.wawo.loader.utils.ParcelableFixer
import io.ge.wawo.loader.utils.ResourcesInjector
import io.ge.wawo.utils.HostInfo
import io.ge.wawo.utils.RuntimeConfig
import io.ge.wawo.utils.TargetProcesses
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.fs.KnownPaths

object WeLauncher {

    fun init(context: Context) {
        WeLogger.d(TAG, "loading in process name=${TargetProcesses.currentName}, type=${TargetProcesses.currentType}")

        ParcelableFixer.init()

        DexCacheManager.init(
            KnownPaths.hostVersionKey()
        )

        val appContext = context.applicationContext ?: context
        ResourcesInjector.injectModuleRes(appContext.resources)
        WeKitLocaleController.initializeInjectedHost(HostInfo.application)

        if (TargetProcesses.isInMain) {
            ActivityProxy.init(appContext)

            val prefs =
                context.getSharedPreferences("${PackageNames.WECHAT}_preferences", Context.MODE_PRIVATE)
            RuntimeConfig.mmPrefs = prefs
        }

        runCatching {
            FeaturesLoader.loadFeatures()
        }.onFailure { WeLogger.e(TAG, "failed to load features", it) }
    }

    private const val TAG = "WeLauncher"
}
