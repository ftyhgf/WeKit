package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Summarize
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelProviderType
import dev.ujhhgtg.wekit.agent.model.LlmMessage
import dev.ujhhgtg.wekit.agent.model.LlmRole
import dev.ujhhgtg.wekit.agent.model.LlmStreamEvent
import dev.ujhhgtg.wekit.agent.model.ModelProviderManager
import dev.ujhhgtg.wekit.agent.model.local.LOCAL_LLAMA_MIN_CONTEXT_WINDOW
import dev.ujhhgtg.wekit.agent.model.local.LocalLlama
import dev.ujhhgtg.wekit.agent.model.local.LocalLlamaModels
import dev.ujhhgtg.wekit.features.api.core.WeDatabaseApi
import dev.ujhhgtg.wekit.features.api.core.models.MessageType
import dev.ujhhgtg.wekit.features.api.core.models.WeMessage
import dev.ujhhgtg.wekit.features.api.ui.WeChatMessageContextMenuApi
import dev.ujhhgtg.wekit.features.core.FeatureCategoryIds
import dev.ujhhgtg.wekit.features.core.SwitchFeature
import dev.ujhhgtg.wekit.ui.content.AlertDialogContent
import dev.ujhhgtg.wekit.ui.content.Button
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext

/**
 * 聊天自动总结：长按任意消息，从菜单发起对当前会话最近消息的总结。
 *
 * 读取最近 [MAX_MESSAGES] 条消息，本地统计各发言人消息条数，再调用 WeAgent 配置的
 * 默认模型（云端或本地均可）生成一段内容总结，最终以 Compose 弹窗卡片呈现。
 */
object ChatSummary : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "聊天总结"
    override val nameRes = R.string.feature_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_summary_description

    private const val MAX_MESSAGES = 50

    override fun onEnable() {
        WeChatMessageContextMenuApi.addProvider(this)
    }

    override fun onDisable() {
        WeChatMessageContextMenuApi.removeProvider(this)
    }

    override fun getMenuItems(): List<WeChatMessageContextMenuApi.MenuItem> {
        return listOf(
            WeChatMessageContextMenuApi.MenuItem(
                777018,
                localizedChatString(R.string.chat_summary_menu),
                ChatInfoIcon,
                MaterialSymbols.Outlined.Summarize,
                { _ -> true },
                multiSelect = WeChatMessageContextMenuApi.MultiSelectSupport.Unsupported
            ) { view, _, msgInfo ->
                val convId = msgInfo.talker
                if (convId.isNotEmpty()) {
                    showComposeDialog(view.context) {
                        SummaryDialogContent(convId)
                    }
                }
            }
        )
    }

    private sealed interface SummaryUiState {
        data object Loading : SummaryUiState
        data class Success(
            val stats: List<Pair<String, Int>>,
            val total: Int,
            val summary: String,
        ) : SummaryUiState

        data class Error(val message: String) : SummaryUiState
    }

    @Composable
    private fun SummaryDialogContent(convId: String) {
        val context = androidx.compose.ui.platform.LocalContext.current
        var state by remember { mutableStateOf<SummaryUiState>(SummaryUiState.Loading) }
        LaunchedEffect(convId) {
            state = runCatching {
                withContext(Dispatchers.IO) { buildSummary(context, convId) }
            }.fold(
                onSuccess = { it },
                onFailure = { e ->
                    SummaryUiState.Error(e.message ?: context.localizedChatString(R.string.chat_summary_failed, e.javaClass.simpleName))
                }
            )
        }
        AlertDialogContent(
            title = { Text(stringResource(R.string.chat_summary_title)) },
            text = {
                when (val s = state) {
                    SummaryUiState.Loading -> Text(stringResource(R.string.chat_summary_loading))
                    is SummaryUiState.Error -> Text(s.message)
                    is SummaryUiState.Success -> SummaryContent(s)
                }
            },
            confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
        )
    }

    @Composable
    private fun SummaryContent(state: SummaryUiState.Success) {
        LazyColumn {
            item {
                Text(
                    text = stringResource(R.string.chat_summary_analyzed, state.total),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            item {
                Text(
                    text = stringResource(R.string.chat_summary_speakers),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(state.stats.size) { i ->
                val (speaker, count) = state.stats[i]
                Text(text = "\u2022 $speaker  \u00d7 $count")
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_summary_content),
                    modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                )
                Text(text = state.summary)
            }
        }
    }

    private suspend fun buildSummary(context: Context, convId: String): SummaryUiState.Success {
        val messages = WeDatabaseApi.getMessages(convId, 1, MAX_MESSAGES)
        if (messages.isEmpty()) {
            return SummaryUiState.Success(
                stats = emptyList(),
                total = 0,
                summary = context.localizedChatString(R.string.chat_summary_no_messages),
            )
        }
        val stats = LinkedHashMap<String, Int>()
        val transcript = StringBuilder()
        val isGroup = convId.isGroupChatWxId
        for (msg in messages) {
            val speaker = resolveSpeaker(context, msg, convId, isGroup)
            stats[speaker] = (stats[speaker] ?: 0) + 1
            transcript.append(speaker).append(": ").append(displayText(context, msg, isGroup)).append('\n')
        }
        val summary = generateSummary(context, transcript.toString())
        return SummaryUiState.Success(
            stats = stats.entries.map { it.key to it.value },
            total = messages.size,
            summary = summary,
        )
    }

    private fun resolveSpeaker(context: Context, msg: WeMessage, convId: String, isGroup: Boolean): String {
        if (msg.isSend != 0) return context.localizedChatString(R.string.chat_summary_self)
        if (!isGroup) return context.localizedChatString(R.string.chat_summary_peer)
        // 群聊消息存储格式：发送者 wxid 后跟冒号换行，再接正文。
        val prefix = msg.content.substringBefore(':')
        return prefix.takeIf { it.isNotBlank() && it.length <= 64 }
            ?: context.localizedChatString(R.string.chat_summary_unknown)
    }

    private fun displayText(context: Context, msg: WeMessage, isGroup: Boolean): String {
        val type = msg.type
        if (type?.isText == true) {
            return if (isGroup) {
                msg.content.substringAfter('\n').trim().ifEmpty { msg.content }
            } else {
                msg.content
            }
        }
        return "[${type?.let { context.localizedChatString(it.displayNameRes) } ?: "?"}]"
    }

    private suspend fun generateSummary(context: Context, transcript: String): String {
        val modelId = WeAgentSettings.defaultModelId() ?: WeAgentRepository.firstModelId()
        val model = modelId?.let { WeAgentRepository.getModel(it) }
        val provider = model?.let { WeAgentRepository.getModelProvider(it.providerId) }
        if (model == null || provider == null) {
            throw IllegalStateException(context.localizedChatString(R.string.chat_summary_no_model))
        }

        val client = if (provider.type == ModelProviderType.LOCAL_LLAMA) {
            val nCtx = LocalLlamaModels.defaultContextWindow(model.modelIdRemote)
                ?: LOCAL_LLAMA_MIN_CONTEXT_WINDOW
            ModelProviderManager.localClientFor(provider, model.modelIdRemote, nCtx, LocalLlama.BACKENDS.first())
        } else {
            ModelProviderManager.clientFor(provider)
        }

        val systemPrompt = context.localizedChatString(R.string.chat_summary_system_prompt)
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = listOf(
                LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
                LlmMessage(role = LlmRole.USER, content = transcript),
            ),
            tools = emptyList(),
            stream = false,
        )
        val events = client.stream(request).toList()
        val failed = events.lastOrNull { it is LlmStreamEvent.Failed } as? LlmStreamEvent.Failed
        val text = (events.lastOrNull { it is LlmStreamEvent.Completed } as? LlmStreamEvent.Completed)
            ?.message?.content?.trim()
        if (failed != null) throw failed.error
        if (text.isNullOrEmpty()) {
            throw IllegalStateException(context.localizedChatString(R.string.chat_summary_empty))
        }
        return text
    }
}
