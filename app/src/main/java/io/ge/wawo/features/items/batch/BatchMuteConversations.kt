package io.ge.wawo.features.items.batch

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import io.ge.wawo.ui.utils.ListItem
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import io.ge.wawo.R
import androidx.compose.ui.Modifier
import io.ge.wawo.features.api.core.WeConversationApi
import io.ge.wawo.features.api.core.WeDatabaseApi
import io.ge.wawo.features.core.ClickableFeature
import io.ge.wawo.features.core.FeatureCategoryIds
import io.ge.wawo.ui.content.AlertDialogContent
import io.ge.wawo.ui.content.ContactsSelector
import io.ge.wawo.ui.content.DefaultColumn
import io.ge.wawo.ui.content.TextButton
import io.ge.wawo.ui.utils.showComposeDialog
import io.ge.wawo.utils.WeLogger
import io.ge.wawo.utils.android.showToast
import io.ge.wawo.utils.android.showToastSuspend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

object BatchMuteConversations : ClickableFeature() {

    override val technicalId = "批量免打扰"
    override val nameRes = R.string.feature_batch_mute_conversations_name
    override val categoryIds = listOf(FeatureCategoryIds.BATCH)
    override val descriptionRes = R.string.feature_batch_mute_conversations_description

    private const val TAG = "BatchMuteConversations"

    override val noSwitchWidget = true

    override fun onClick(context: ComponentActivity) {
        showComposeDialog(context) {
            AlertDialogContent(
                title = { Text(stringResource(R.string.batch_mute_title)) },
                text = {
                    DefaultColumn {
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                pickAndApply(context, mute = true)
                            },
                            supportingContent = { Text(stringResource(R.string.batch_mute_enable_description)) },
                            content = { Text(stringResource(R.string.batch_mute_enable)) },
                        )
                        ListItem(
                            modifier = Modifier.clickable {
                                onDismiss()
                                pickAndApply(context, mute = false)
                            },
                            supportingContent = { Text(stringResource(R.string.batch_mute_disable_description)) },
                            content = { Text(stringResource(R.string.batch_mute_disable)) },
                        )
                    }
                },
                dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.dialog_cancel)) } }
            )
        }
    }

    private fun pickAndApply(context: Context, mute: Boolean) {
        val contacts = WeDatabaseApi.getFriends() + WeDatabaseApi.getGroups()

        showComposeDialog(context) {
            ContactsSelector(
                title = context.localizedBatchString(
                    if (mute) R.string.batch_mute_select_enable else R.string.batch_mute_select_disable,
                ),
                contacts = contacts,
                initialSelectedWxIds = emptySet(),
                onDismiss = onDismiss,
                onConfirm = { selectedWxIds ->
                    if (selectedWxIds.isEmpty()) {
                        showToast(context.localizedBatchString(R.string.batch_select_at_least_one_conversation))
                        return@ContactsSelector
                    }

                    onDismiss()
                    apply(selectedWxIds, mute)
                }
            )
        }
    }

    private fun apply(wxIds: Set<String>, mute: Boolean) {
        CoroutineScope(Dispatchers.IO).launch {
            showToastSuspend(
                localizedBatchQuantity(R.plurals.batch_mute_progress, wxIds.size, wxIds.size),
            )
            wxIds.forEach { wxId ->
                runCatching { WeConversationApi.setDnd(wxId, mute) }
                    .onFailure { WeLogger.e(TAG, "failed to set mute=$mute for $wxId", it) }
                delay(100.milliseconds)
            }
            WeConversationApi.reloadConversations()
            showToastSuspend(
                localizedBatchQuantity(
                    if (mute) R.plurals.batch_mute_enabled else R.plurals.batch_mute_disabled,
                    wxIds.size,
                    wxIds.size,
                )
            )
        }
    }
}
