package io.ge.wawo.features.items.official_accounts

import android.content.Intent
import io.ge.wawo.R
import io.ge.wawo.features.api.ui.WeStartActivityApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.utils.HookParam
import io.ge.wawo.utils.WeLogger

object UseMultiWebViewForOfficialAccounts : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override val technicalId = "允许公众号网页多开"
    override val nameRes = R.string.feature_use_multi_web_view_for_official_accounts_name
    override val categoryIds = listOf(FeatureCategoryIds.OFFICIAL_ACCOUNTS)
    override val descriptionRes = R.string.feature_use_multi_web_view_for_official_accounts_description

    private const val tag = "UseMultiWebViewForOfficialAccounts"

    override fun onEnable() {
        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className ?: return
        if (!className.endsWith(".ui.timeline.preload.ui.TmplWebViewMMUI")) return

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
        intent.addFlags(Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        WeLogger.d(tag, "enabled multi webview for $className")
    }
}
