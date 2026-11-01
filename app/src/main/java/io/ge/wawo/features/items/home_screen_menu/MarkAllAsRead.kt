package io.ge.wawo.features.items.home_screen_menu

import io.ge.wawo.R
import io.ge.wawo.features.api.core.WeConversationApi
import io.ge.wawo.features.api.ui.WeHomeScreenPopupMenuApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.ui.utils.MarkChatReadIcon
import io.ge.wawo.utils.HookParam
import io.ge.wawo.utils.android.showToast

object MarkAllAsRead : SwitchFeature(), WeHomeScreenPopupMenuApi.IMenuItemsProvider {

    override val technicalId = "清空未读"
    override val nameRes = R.string.feature_mark_all_as_read_name
    override val categoryIds = listOf(FeatureCategoryIds.HOME_SCREEN_MENU)
    override val descriptionRes = R.string.feature_mark_all_as_read_description

    override fun onEnable() {
        WeHomeScreenPopupMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeHomeScreenPopupMenuApi.removeProvider(this)
    }

    override fun getMenuItems(param: HookParam): List<WeHomeScreenPopupMenuApi.MenuItem> {
        return listOf(
            WeHomeScreenPopupMenuApi.MenuItem(
                777012, localizedHomeMenuString(R.string.home_menu_mark_all_read), MarkChatReadIcon
            ) {
                WeConversationApi.markAllAsRead()
                showToast(localizedHomeMenuString(R.string.home_menu_all_marked_read))
            }
        )
    }
}
