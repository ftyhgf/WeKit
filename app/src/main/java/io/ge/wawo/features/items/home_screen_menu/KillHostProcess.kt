package io.ge.wawo.features.items.home_screen_menu

import io.ge.wawo.R
import io.ge.wawo.features.api.ui.WeHomeScreenPopupMenuApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.ui.utils.CancelIcon
import io.ge.wawo.utils.HookParam
import io.ge.wawo.utils.killHost

object KillHostProcess : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "强行停止"
    override val nameRes = R.string.feature_kill_host_process_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_kill_host_process_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777015, localizedHomeMenuString(R.string.home_menu_force_stop), CancelIcon
            ) {
                killHost()
            }
        )
    }
}
