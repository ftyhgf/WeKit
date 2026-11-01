package io.ge.wawo.features.items.chat

import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Edit
import io.ge.wawo.R
import io.ge.wawo.features.api.core.WeMessageApi
import io.ge.wawo.features.api.ui.WeChatMessageContextMenuApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.ui.utils.UndoIcon
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.android.showToast

object BatchRevoke : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "批量撤回"
    override val nameRes = R.string.feature_batch_revoke_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_batch_revoke_description

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777023, localizedChatString(R.string.chat_batch_revoke_menu), UndoIcon, MaterialSymbols.Outlined.Edit,
                isSupported = { false },
                // revokes then loads one message's text into the input box; single-message only
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Adapted(
                    isSupported = { true },
                    onClick = { view, _, msgs ->
                        // yeah i know this is very cursed; this is the sequelae of writing too much python
                        val succeeded = msgs.sumOf { msg ->
                            runCatching { WeMessageApi.revokeMsg(msg); 1 }
                                .getOrElse {
                                    WeLogger.e("BatchRevoke", "failed to revoke msgId=${msg.id}", it); 0
                                }
                        }
                        showToast(
                            view.context,
                            view.context.localizedChatQuantity(
                                R.plurals.chat_batch_revoke_result,
                                msgs.size,
                                succeeded,
                                msgs.size,
                            ),
                        )
                    }
                )
            ) { _, _, _ ->
            }
        )
    }
}
