package io.ge.wawo.features.items.home_screen_menu

import com.tencent.mm.ui.LauncherUI
import io.ge.wawo.R
import io.ge.wawo.BuildConfig
import io.ge.wawo.features.api.ui.WeHomeScreenPopupMenuApi
import io.ge.wawo.features.api.ui.WeSettingsInjector
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.ui.utils.ExtensionIcon
import io.ge.wawo.utils.HookParam

object ModuleSettings : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "模块设置"
    override val nameRes = R.string.feature_module_settings_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_module_settings_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> =
        listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                0, BuildConfig.TAG, ExtensionIcon
            ) { WeSettingsInjector.openSettingsDialog(LauncherUI.getInstance()!!) }
        )
}
