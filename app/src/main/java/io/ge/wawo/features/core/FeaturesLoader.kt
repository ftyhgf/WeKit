package io.ge.wawo.features.core

import com.tencent.mm.ui.LauncherUI
import io.ge.wawo.R
import io.ge.wawo.constants.Preferences
import io.ge.wawo.dexkit.abc.IResolveDex
import io.ge.wawo.dexkit.cache.DexCacheManager
import io.ge.wawo.features.items.beautify.BeautifyConversationList
import io.ge.wawo.features.items.chat.ConversationGrouping
import io.ge.wawo.features.items.system.SafeMode
import io.ge.wawo.i18n.LocaleResourceMode
import io.ge.wawo.i18n.LocalizedContextFactory
import io.ge.wawo.i18n.WeKitLocaleController
import io.ge.wawo.ui.content.DexResolver
import io.ge.wawo.ui.utils.showComposeDialog
import io.ge.wawo.utils.HostInfo
import io.ge.wawo.utils.TargetProcesses
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.android.showToast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.measureTime

object FeaturesLoader {

    private const val TAG = "FeaturesLoader"

    fun loadFeatures() {
        val allFeatures = FeaturesProvider.ALL_FEATURES
        allFeatures.filterIsInstance<SwitchFeature>().forEach(SwitchFeature::loadPersistedState)
        if (TargetProcesses.isInMain) {
            // Migrate even when grouping is disabled or its Dex cache needs rebuilding. Reading
            // legacy style later could mistake a new beauty toggle for the user's pre-upgrade choice.
            ConversationGrouping.migrateTabStyle(BeautifyConversationList.isLayoutBeautificationEnabled)
        }

        val safeMode = SafeMode.isEnabled
        val featuresToStart = if (safeMode) {
            allFeatures.filterIsInstance<ApiFeature>()
        } else {
            allFeatures
        }
        if (safeMode) {
            WeLogger.i(
                TAG,
                "safe mode active: loading only ${featuresToStart.size} ApiFeature(s), " +
                    "skipping ${allFeatures.size - featuresToStart.size} feature(s)",
            )
        }
        val allDexItems = featuresToStart.filterIsInstance<IResolveDex>()

        val outdatedItems = DexCacheManager.getOutdatedItems(allDexItems)
        val validItems = allDexItems - outdatedItems.toSet()

        if (outdatedItems.isNotEmpty())
            WeLogger.i(TAG, "found ${validItems.size} valid items, ${outdatedItems.size} outdated items")

        // Load what we can from cache. Items with *some* missing keys are still partially loaded —
        // their valid delegates work immediately; only the item itself is queued for re-resolution.
        val cacheFailedItems = loadDescriptorsFromCache(validItems)
        val allBrokenItems = (outdatedItems + cacheFailedItems).distinct()

        if (allBrokenItems.isNotEmpty())
            handleBrokenItems(allBrokenItems)

        val elapsed = measureTime {
            featuresToStart.forEach { feature ->
                val isBroken = feature is IResolveDex && allBrokenItems.contains(feature)

                if (isBroken) {
                    WeLogger.w(TAG, "skipping ${feature.technicalId} — incomplete cache, awaiting re-resolution")
                    return@forEach
                }

                feature.startup()
            }
        }
        WeLogger.i(TAG, "loading all features took $elapsed")

        if (TargetProcesses.isInMain && Preferences.showStartupToast) {
            val context = LocalizedContextFactory.create(
                HostInfo.application,
                WeKitLocaleController.resolvedLocale,
                LocaleResourceMode.InjectedHost,
            )
            showToast(context, context.getString(R.string.noncompose_features_loaded))
        }
    }

    // ---------------------------------------------------------------------------

    /**
     * 逐委托从缓存恢复状态。
     *
     * - 某个委托的 key 缺失 → 其他委托不受影响，仍正常加载。
     * - 有任意 key 缺失的 item 加入返回列表，等待 DexKit 重新扫描。
     * - 缓存文件整体读取失败 → 删除损坏文件，整个 item 加入返回列表。
     */
    private fun loadDescriptorsFromCache(items: List<IResolveDex>): List<IResolveDex> {
        val failedItems = mutableListOf<IResolveDex>()

        for (item in items) {
            val path = (item as BaseFeature).technicalPath
            try {
                val cache = DexCacheManager.loadItemCache(item)
                if (cache == null) {
                    WeLogger.w(TAG, "cache missing for $path")
                    failedItems += item
                    continue
                }

                // loadFromCache 逐委托加载；返回未命中的 key 集合
                val missingKeys = item.loadFromCache(cache)
                if (missingKeys.isNotEmpty()) {
                    val total = item.dexDelegates.size
                    val loaded = total - missingKeys.size
                    WeLogger.w(TAG, "$path: loaded $loaded/$total delegates from cache, missing: $missingKeys")
                    failedItems += item
                    // 已命中的委托此时已经可用；hook 仍然跳过（见 loadFeatures），
                    // 等 DexKit 把缺失的部分补齐、cache 更新后下次启动即完整。
                }
            } catch (e: Exception) {
                WeLogger.e(TAG, "cache load failed for $path", e)
                runCatching { DexCacheManager.deleteCache((item as BaseFeature).technicalId) }
                failedItems += item
            }
        }

        return failedItems
    }

    private fun handleBrokenItems(brokenItems: List<IResolveDex>) {
        if (Preferences.noDexResolve) return
        if (!TargetProcesses.isInMain) return

        WeLogger.i(TAG, "launching background coroutine to repair ${brokenItems.size} items")

        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var activity = LauncherUI.getInstance()
            var waited = 0L
            while (activity == null && waited < 30_000L) {
                delay(1_000.milliseconds)
                waited += 1_000
                activity = LauncherUI.getInstance()
            }

            if (activity == null) {
                WeLogger.w(TAG, "no LauncherUI available for dex-repair dialog; skipping")
                return@launch
            }

            val boundActivity = activity
            withContext(Dispatchers.Main) {
                showComposeDialog(boundActivity, directlyDismissable = false) {
                    DexResolver(
                        boundActivity,
                        brokenItems,
                        MainScope(),
                        onDismiss
                    )
                }
            }
        }
    }
}
