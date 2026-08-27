package dev.ujhhgtg.wekit.features.items.chat

import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.AutoAwesome
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.features.api.core.WeApi
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.WeMessageApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageInfo
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.Feature
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ExtensionIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.WeLogger
import dev.ujhhgtg.wekit.utils.android.showToast
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 聊天自动总结：在微信消息长按菜单中新增"自动总结"入口。
 *
 * 点击后对该会话最近的 [DEFAULT_MESSAGE_COUNT] 条消息做两件事：
 *  1. 本地统计发言人条数（不依赖模型，稳定可靠）；
 *  2. 复用 WeAgent 配置的默认模型（ModelProviderManager + WeAgentSettings）流式生成内容总结。
 * 结果通过 Compose 弹窗卡片呈现。
 */
@Feature(
    id = "聊天自动总结",
    nameRes = "feature_chat_auto_summary_name",
    categoryIds = [FeatureCategoryIds.CHAT],
    descriptionRes = "feature_chat_auto_summary_description",
)
object ChatAutoSummary : SwitchFeature(), WeChatMessageContextMenuApi.IMenuItemsProvider {

    private const val TAG = "ChatAutoSummary"
    private const val MENU_ITEM_ID = 777020
    private const val DEFAULT_MESSAGE_COUNT = 100

    private class NoModelException(message: String) : Exception(message)

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                MENU_ITEM_ID,
                localizedChatString(R.string.chat_auto_summary_menu),
                ExtensionIcon,
                MaterialSymbols.Outlined.AutoAwesome,
                isSupported = { true },
                // 总结面向整个会话，多选场景无意义，从多选菜单中隐藏
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported,
            ) { view, _, msgInfo ->
                showSummaryDialog(view, msgInfo.talker)
            }
        )
    }

    private fun showSummaryDialog(view: View, talker: String) {
        if (talker.isBlank()) {
            showToast(view.context, view.context.localizedChatString(R.string.chat_auto_summary_no_message))
            return
        }
        showComposeDialog(view.context) {
            ChatSummaryDialog(talker = talker)
        }
    }

    // ------------------------------------------------------------------
    // UI
    // ------------------------------------------------------------------

    private sealed interface Phase {
        data object Analyzing : Phase
        data object Summarizing : Phase
        data class Failed(val message: String) : Phase
        data object Done : Phase
    }

    private data class Analysis(
        val chatType: String,
        val messageCount: Int,
        val speakerCounts: List<Pair<String, Int>>,
        val transcript: String,
    )

    @Composable
    private fun ChatSummaryDialog(talker: String) {
        var phase by remember { mutableStateOf<Phase>(Phase.Analyzing) }
        var analysis by remember { mutableStateOf<Analysis?>(null) }
        var summary by remember { mutableStateOf("") }

        LaunchedEffect(talker) {
            phase = Phase.Analyzing
            val loaded = withContext(Dispatchers.IO) { buildAnalysis(talker) }
            if (loaded == null) {
                phase = Phase.Failed(localizedChatString(R.string.chat_auto_summary_no_message))
                return@LaunchedEffect
            }
            analysis = loaded
            phase = Phase.Summarizing
            val sb = StringBuilder()
            try {
                generateSummary(loaded) { delta ->
                    sb.append(delta)
                    summary = sb.toString()
                }
                if (summary.isBlank()) summary = localizedChatString(R.string.chat_auto_summary_empty_result)
                phase = Phase.Done
            } catch (e: NoModelException) {
                phase = Phase.Failed(localizedChatString(R.string.chat_auto_summary_no_model))
            } catch (e: Throwable) {
                WeLogger.e(TAG, "summary generation failed", e)
                phase = Phase.Failed(localizedChatString(R.string.chat_auto_summary_failed, e.message ?: "unknown"))
            }
        }

        AlertDialogContent(
            title = { Text(localizedChatString(R.string.chat_auto_summary_title)) },
            text = {
                when (val p = phase) {
                    Phase.Analyzing, Phase.Summarizing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.padding(vertical = 8.dp)
                        ) {
                            CircularProgressIndicator()
                            Text(
                                localizedChatString(
                                    if (p == Phase.Analyzing) R.string.chat_auto_summary_analyzing
                                    else R.string.chat_auto_summary_summarizing
                                )
                            )
                        }
                    }
                    is Phase.Failed -> {
                        Text(
                            p.message,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    Phase.Done -> {
                        analysis?.let { a -> ResultContent(a, summary) }
                    }
                }
            },
            confirmButton = {
                Button(onDismiss) { Text(localizedChatString(R.string.dialog_close)) }
            }
        )
    }

    @Composable
    private fun ResultContent(analysis: Analysis, summary: String) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 发言统计
            Text(
                localizedChatString(R.string.chat_auto_summary_statistics_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            Text(
                localizedChatString(
                    R.string.chat_auto_summary_messages_count,
                    analysis.messageCount,
                    analysis.speakerCounts.size,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 160.dp)) {
                items(analysis.speakerCounts) { (name, count) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            localizedChatString(R.string.chat_auto_summary_speaker_count, count),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // 内容总结
            Text(
                localizedChatString(R.string.chat_auto_summary_summary_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                item {
                    Text(
                        summary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // 分析逻辑（IO 线程执行）
    // ------------------------------------------------------------------

    private fun buildAnalysis(talker: String): Analysis? {
        val messages = queryRecentMessages(talker, DEFAULT_MESSAGE_COUNT)
        if (messages.isEmpty()) return null

        val counts = LinkedHashMap<String, Int>()
        messages.forEach { msg ->
            val sender = msg.sender.ifEmpty { "unknown" }
            counts[sender] = (counts[sender] ?: 0) + 1
        }

        val chatType = if (talker.isGroupChatWxId) {
            localizedChatString(R.string.chat_auto_summary_chat_group)
        } else {
            localizedChatString(R.string.chat_auto_summary_chat_private)
        }

        val speakerCounts = counts.entries
            .sortedByDescending { it.value }
            .map { (wxId, count) -> displayName(wxId) to count }

        val transcript = buildTranscript(messages)
        return Analysis(chatType, messages.size, speakerCounts, transcript)
    }

    private fun queryRecentMessages(talker: String, limit: Int): List<MessageInfo> {
        val result = ArrayList<MessageInfo>()
        WeDatabaseApi.rawQuery(
            "SELECT * FROM message WHERE talker=? ORDER BY createTime DESC LIMIT ?",
            arrayOf(talker, limit),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(MessageInfo(WeMessageApi.convertMsgInfoInstanceFromCursor(cursor)))
            }
        }
        result.reverse()
        return result
    }

    private fun buildTranscript(messages: List<MessageInfo>): String {
        val sb = StringBuilder()
        messages.forEach { msg ->
            val name = displayName(msg.sender.ifEmpty { "unknown" })
            sb.append('[').append(name).append("] ").append(msg.humanReadableRepr).append('\n')
        }
        return sb.toString()
    }

    private fun displayName(wxId: String): String {
        if (wxId == WeApi.selfWxId) return localizedChatString(R.string.chat_auto_summary_self)
        return runCatching { WeDatabaseApi.getDisplayName(wxId) }
            .getOrDefault(wxId)
            .ifBlank { wxId }
    }

    // ------------------------------------------------------------------
    // LLM 生成（复用 WeAgent 方案）
    // ------------------------------------------------------------------

    private suspend fun generateSummary(analysis: Analysis, onDelta: (String) -> Unit) {
        val modelId = WeAgentSettings.defaultModelId() ?: WeAgentSettings.smallModelId()
        if (modelId.isNullOrBlank()) throw NoModelException("no default model")

        val model = WeAgentRepository.getModel(modelId) ?: throw NoModelException("model missing")
        val provider = WeAgentRepository.getModelProvider(model.providerId) ?: throw NoModelException("provider missing")
        val client = ModelProviderManager.clientFor(provider)

        val system = localizedChatString(R.string.chat_auto_summary_system_prompt)
        val user = buildUserPrompt(analysis)
        val request = ModelProviderManager.buildRequest(
            model,
            listOf(
                LlmMessage(role = LlmRole.SYSTEM, content = system),
                LlmMessage(role = LlmRole.USER, content = user),
            ),
            emptyList(),
        )

        client.stream(request).collect { ev ->
            when (ev) {
                is LlmStreamEvent.TextDelta -> onDelta(ev.text)
                is LlmStreamEvent.Failed -> throw ev.error
                is LlmStreamEvent.Completed -> Unit
                is LlmStreamEvent.ReasoningDelta -> Unit
            }
        }
    }

    private fun buildUserPrompt(analysis: Analysis): String {
        val header = localizedChatString(
            R.string.chat_auto_summary_prompt_header,
            analysis.chatType,
            analysis.messageCount,
            analysis.speakerCounts.size,
        )
        return header + "\n\n" + analysis.transcript
    }
}
