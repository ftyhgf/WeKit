package io.ge.wawo.features.items.official_accounts

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import dev.ujhhgtg.reflekt.utils.toClassOrNull
import io.ge.wawo.R
import io.ge.wawo.constants.PackageNames
import io.ge.wawo.features.api.ui.WeStartActivityApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.i18n.HostLocalizedStrings
import io.ge.wawo.ui.content.AlertDialogContent
import io.ge.wawo.ui.content.TextButton
import io.ge.wawo.ui.utils.showComposeDialog
import io.ge.wawo.utils.HookParam
import io.ge.wawo.utils.HostInfo
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.android.showToast

object UseLegacyOfficialAccountsView : SwitchFeature(), WeStartActivityApi.IStartActivityListener {

    override val technicalId = "恢复旧版公众号列表"
    override val nameRes = R.string.feature_use_legacy_official_accounts_view_name
    override val categoryIds = listOf(FeatureCategoryIds.OFFICIAL_ACCOUNTS)
    override val descriptionRes = R.string.feature_use_legacy_official_accounts_view_description

    private const val LEGACY_OFFICIAL_ACCOUNTS_UI =
        "${PackageNames.WECHAT}.ui.conversation.NewBizConversationUI"

    private fun hasLegacyOfficialAccountsUI(): Boolean = LEGACY_OFFICIAL_ACCOUNTS_UI.toClassOrNull() != null

    override fun onEnable() {
        if (!hasLegacyOfficialAccountsUI()) {
            showToast(HostLocalizedStrings.get(R.string.official_accounts_legacy_ui_missing))
            applyToggle(false)
            return
        }

        WeStartActivityApi.addListener(this)
    }

    override fun onDisable() {
        WeStartActivityApi.removeListener(this)
    }

    override fun onBeforeToggle(newState: Boolean, context: Context): Boolean {
        if (newState && !hasLegacyOfficialAccountsUI()) {
            showComposeDialog(context) {
                AlertDialogContent(
                    title = { Text(stringResource(R.string.error)) },
                    text = { Text(stringResource(R.string.official_accounts_legacy_ui_missing)) },
                    confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
                )
            }
            return false
        }

        return true
    }

    override fun onStartActivity(param: HookParam, intent: Intent) {
        val className = intent.component?.className
        if (className == "${PackageNames.WECHAT}.plugin.brandservice.ui.flutter.BizFlutterTLFlutterViewActivity" ||
            className == "${PackageNames.WECHAT}.plugin.brandservice.ui.timeline.BizTimeLineUI"
        ) {
            WeLogger.d("UseLegacyOfficialAccountsView", "redirected $className")
            intent.component = ComponentName(HostInfo.packageName, LEGACY_OFFICIAL_ACCOUNTS_UI)
        }
    }
}
