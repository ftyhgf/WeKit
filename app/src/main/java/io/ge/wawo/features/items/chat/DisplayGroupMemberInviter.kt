package io.ge.wawo.features.items.chat

import android.app.Activity
import io.ge.wawo.R
import io.ge.wawo.features.api.core.WeDatabaseApi
import io.ge.wawo.features.api.ui.WeContactPrefsScreenApi
import io.ge.wawo.features.api.ui.WeContactPrefsScreenApi.IContactInfoProvider
import io.ge.wawo.features.api.ui.WeContactPrefsScreenApi.PreferenceItem
import io.ge.wawo.features.api.ui.WeCurrentConversationApi
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.features.core.SwitchFeature
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.android.currentWxId
import io.ge.wawo.utils.android.showToast
import io.ge.wawo.utils.android.showToastSuspend
import io.ge.wawo.utils.strings.isGroupChatWxId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DisplayGroupMemberInviter : SwitchFeature(), IContactInfoProvider {

    override val technicalId = "查看群成员邀请者"
    override val nameRes = R.string.feature_display_group_member_inviter_name
    override val categoryIds = listOf(FeatureCategoryIds.CONTACTS_GROUPS)
    override val descriptionRes = R.string.feature_display_group_member_inviter_description

    private const val TAG = "DisplayGroupMemberInviter"

    override fun onEnable() {
        WeContactPrefsScreenApi.addProvider(this)
    }

    override fun onDisable() {
        WeContactPrefsScreenApi.removeProvider(this)
    }

    // ── IContactInfoProvider ──────────────────────────────────────────────────

    /** Only shown for an individual group member (not the group itself), inside a group chat. */
    override fun getContactInfoItem(activity: Activity): List<PreferenceItem> {
        if (!WeCurrentConversationApi.value.isGroupChatWxId) return emptyList()
        val memberId = activity.currentWxId ?: return emptyList()
        if (memberId.isGroupChatWxId) return emptyList()

        return listOf(
            PreferenceItem(
                title = activity.localizedChatString(R.string.chat_member_inviter_title),
                summary = activity.localizedChatString(R.string.chat_contact_tap_to_view),
                position = 1,
                onClick = onClick@{ activity ->
                    val groupId = WeCurrentConversationApi.value.takeIf { it.isGroupChatWxId } ?: return@onClick
                    val clickedMemberId = activity.currentWxId ?: return@onClick

                    showToast(activity, activity.localizedChatString(R.string.chat_member_inviter_querying))
                    CoroutineScope(Dispatchers.IO).launch {
                        val inviterId = runCatching { WeDatabaseApi.getGroupMemberInviter(groupId, clickedMemberId) }
                            .onFailure { WeLogger.e(TAG, "failed to resolve inviter for $clickedMemberId in $groupId", it) }
                            .getOrDefault("")

                        val message = when {
                            inviterId.isEmpty() -> activity.localizedChatString(R.string.chat_member_inviter_no_record)
                            inviterId == clickedMemberId -> activity.localizedChatString(R.string.chat_member_inviter_self_joined)
                            else -> {
                                val inviterName = runCatching { WeDatabaseApi.getDisplayName(inviterId) }
                                    .getOrDefault(inviterId)
                                val groupNick = runCatching {
                                    WeDatabaseApi.getGroupMemberDisplayName(groupId, inviterId)
                                }.getOrDefault("")

                                val nameLabel = if (groupNick.isNotBlank() && groupNick != inviterName) {
                                    "$inviterName ($groupNick)"
                                } else {
                                    inviterName
                                }
                                activity.localizedChatString(R.string.chat_member_inviter_result, nameLabel)
                            }
                        }
                        showToastSuspend(activity, message)
                    }
                },
            )
        )
    }

}
