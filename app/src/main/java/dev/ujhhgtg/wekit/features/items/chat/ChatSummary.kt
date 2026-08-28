package dev.ujhhgtg.wekit.features.items.chat

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.composables.icons.materialsymbols.MaterialSymbols
import com.composables.icons.materialsymbols.outlined.Summarize
import dev.ujhhgtg.wekit.R
import dev.ujhhgtg.wekit.agent.data.WeAgentRepository
import dev.ujhhgtg.wekit.agent.data.WeAgentSettings
import dev.ujhhgtg.wekit.agent.data.entity.ModelEntity
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
import dev.ujhhgtg.wekit.ui.content.m3.DropdownOption
import dev.ujhhgtg.wekit.ui.content.m3.ExpressiveOptionDropdown
import dev.ujhhgtg.wekit.ui.utils.ChatInfoIcon
import dev.ujhhgtg.wekit.ui.utils.showComposeDialog
import dev.ujhhgtg.wekit.utils.strings.isGroupChatWxId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.text.style.TextOverflow
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天自动总结：长按任意消息，从菜单发起对当前会话最近消息的总结。
 *
 * 读取最近 [MAX_MESSAGES] 条消息，本地统计各发言人消息条数（分析报告），再调用 WeAgent
 * 配置的模型（默认跟随全局默认模型，群聊可在弹窗内单独指定总结模型）生成结构化总结
 * （智能总结），最终以 Compose 弹窗卡片呈现。弹窗内分析与总结分为两个 Tab。
 */
object ChatSummary : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "聊天总结"
    override val nameRes = R.string.feature_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_summary_description

    private const val MAX_MESSAGES = 500

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
                        SummaryDialogContent(convId, onDismiss)
                    }
                }
            }
        )
    }

    /** 弹窗内部 Tab。 */
    private enum class SummaryTab { ANALYSIS, SUMMARY }

    /** 消息时间范围：durationMs 为 null 表示不设上限（全部消息）。 */
    private enum class TimeRange(val labelRes: Int, val durationMs: Long?) {
        LAST_1H(R.string.chat_summary_time_1h, 1 * 60 * 60 * 1000L),
        LAST_6H(R.string.chat_summary_time_6h, 6 * 60 * 60 * 1000L),
        LAST_12H(R.string.chat_summary_time_12h, 12 * 60 * 60 * 1000L),
        LAST_24H(R.string.chat_summary_time_24h, 24 * 60 * 60 * 1000L),
        LAST_3D(R.string.chat_summary_time_3d, 3 * 24 * 60 * 60 * 1000L),
        LAST_7D(R.string.chat_summary_time_7d, 7 * 24 * 60 * 60 * 1000L),
        ALL(R.string.chat_summary_time_all, null),
    }

    /** 智能总结状态：Idle=未生成 / Loading=生成中 / Success=成功 / Error=失败。 */
    private sealed interface SummaryState {
        data object Idle : SummaryState
        data object Loading : SummaryState
        data class Success(val summary: String, val generatedAt: String) : SummaryState
        data class Error(val message: String) : SummaryState
    }

    /** 本地分析结果（发言人统计 + 供模型使用的转写文本）。 */
    private data class ConversationAnalysis(
        val stats: List<Pair<String, Int>>,
        val total: Int,
        val transcript: String,
    )

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun SummaryDialogContent(convId: String, onDismiss: () -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val scope = rememberCoroutineScope()
        val isGroup = convId.isGroupChatWxId

        var analysis by remember { mutableStateOf<ConversationAnalysis?>(null) }
        var summaryState by remember { mutableStateOf<SummaryState>(SummaryState.Idle) }
        var selectedTab by remember { mutableStateOf(SummaryTab.ANALYSIS) }
        var focus by remember { mutableStateOf("") }
        // 群聊总结专用模型 id，null = 跟随全局默认模型。
        var summaryModelId by remember { mutableStateOf<String?>(null) }
        var modelOptions by remember { mutableStateOf<List<ModelEntity>>(emptyList()) }
        var modelMenuExpanded by remember { mutableStateOf(false) }
        // v3: 消息分析时间范围（默认最近 24 小时）
        var timeRange by remember { mutableStateOf(TimeRange.LAST_24H) }
        var timeMenuExpanded by remember { mutableStateOf(false) }

        LaunchedEffect(convId, timeRange) {
            analysis = withContext(Dispatchers.IO) {
                analyzeConversation(context, convId, timeRange)
            }
            val models = WeAgentRepository.getAllModelsOnce()
            modelOptions = models
            summaryModelId = WeAgentSettings.chatSummaryModelId()
        }

        fun requestSummary() {
            val a = analysis ?: return
            scope.launch {
                summaryState = SummaryState.Loading
                selectedTab = SummaryTab.SUMMARY
                summaryState = runCatching {
                    withContext(Dispatchers.IO) {
                        generateSummary(context, a.transcript, focus, summaryModelId)
                    }
                }.fold(
                    onSuccess = { text ->
                        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                            .format(Date())
                        SummaryState.Success(text, stamp)
                    },
                    onFailure = { e ->
                        SummaryState.Error(
                            e.message
                                ?: context.localizedChatString(R.string.chat_summary_failed, e.javaClass.simpleName)
                        )
                    },
                )
            }
        }

        AlertDialogContent(
            title = { Text(stringResource(R.string.chat_summary_title)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    TabRow(
                        selected = selectedTab,
                        onSelect = { selectedTab = it },
                    )
                    Spacer(Modifier.height(10.dp))

                    if (isGroup) {
                        ModelPickerRow(
                            modelOptions = modelOptions,
                            selectedModelId = summaryModelId,
                            expanded = modelMenuExpanded,
                            onExpandedChange = { modelMenuExpanded = it },
                            onSelect = { id ->
                                summaryModelId = id
                                modelMenuExpanded = false
                                scope.launch { WeAgentSettings.setChatSummaryModelId(id) }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }

                    TimeRangeRow(
                        selected = timeRange,
                        expanded = timeMenuExpanded,
                        onExpandedChange = { timeMenuExpanded = it },
                        onSelect = { timeRange = it },
                    )
                    Spacer(Modifier.height(8.dp))

                    if (selectedTab == SummaryTab.SUMMARY) {
                        OutlinedTextField(
                            value = focus,
                            onValueChange = { focus = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = {
                                Text(
                                    context.localizedChatString(R.string.chat_summary_focus_example)
                                )
                            },
                            label = { Text(context.localizedChatString(R.string.chat_summary_focus_label)) },
                            singleLine = false,
                            minLines = 1,
                            maxLines = 2,
                        )
                        Spacer(Modifier.height(8.dp))
                        PresetPromptRow { focus = it }
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { requestSummary() },
                            enabled = summaryState !is SummaryState.Loading,
                        ) {
                            Text(
                                context.localizedChatString(
                                    if (summaryState is SummaryState.Success) R.string.chat_summary_regenerate
                                    else R.string.chat_summary_generate
                                )
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                    }

                    when (val tab = selectedTab) {
                        SummaryTab.ANALYSIS -> AnalysisContent(analysis)
                        SummaryTab.SUMMARY -> SummaryContent(summaryState)
                    }
                }
            },
            confirmButton = { Button(onDismiss) { Text(stringResource(R.string.dialog_close)) } }
        )
    }

    /** 顶部「分析报告 / 智能总结」Tab 切换条。 */
    @Composable
    private fun TabRow(selected: SummaryTab, onSelect: (SummaryTab) -> Unit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TabItem(
                label = stringResource(R.string.chat_summary_tab_analysis),
                selected = selected == SummaryTab.ANALYSIS,
                onClick = { onSelect(SummaryTab.ANALYSIS) },
                modifier = Modifier.weight(1f),
            )
            TabItem(
                label = stringResource(R.string.chat_summary_tab_summary),
                selected = selected == SummaryTab.SUMMARY,
                onClick = { onSelect(SummaryTab.SUMMARY) },
                modifier = Modifier.weight(1f),
            )
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.RowScope.TabItem(
        label: String,
        selected: Boolean,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val shape = RoundedCornerShape(10.dp)
        val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
        val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
        Box(
            modifier = modifier
                .background(bg, shape)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = fg,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }

    /** 群聊总结专用模型选择下拉（需求：群聊总结单独设置模型）。 */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun ModelPickerRow(
        modelOptions: List<ModelEntity>,
        selectedModelId: String?,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onSelect: (String?) -> Unit,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val options = buildList<DropdownOption<String?>> {
            add(DropdownOption<String?>(null, context.localizedChatString(R.string.chat_summary_follow_default)))
            modelOptions.forEach { m ->
                add(DropdownOption<String?>(m.id, m.displayName.ifBlank { m.modelIdRemote }))
            }
        }
        val currentLabel = if (selectedModelId == null) {
            context.localizedChatString(R.string.chat_summary_follow_default)
        } else {
            modelOptions.firstOrNull { it.id == selectedModelId }
                ?.let { it.displayName.ifBlank { it.modelIdRemote } }
                ?: context.localizedChatString(R.string.chat_summary_follow_default)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.localizedChatString(R.string.chat_summary_summary_model),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                Text(
                    text = "▼ $currentLabel",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onExpandedChange(!expanded) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                ExpressiveOptionDropdown(
                    expanded = expanded,
                    value = selectedModelId,
                    options = options,
                    onDismissRequest = { onExpandedChange(false) },
                    onValueChange = { onSelect(it) },
                )
            }
        }
    }

    /** 分析报告 Tab：发言人统计列表。 */
    @Composable
    private fun AnalysisContent(analysis: ConversationAnalysis?) {
        val context = androidx.compose.ui.platform.LocalContext.current
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            if (analysis == null) {
                item { Text(stringResource(R.string.chat_summary_loading)) }
                return@LazyColumn
            }
            item {
                Text(
                    text = stringResource(R.string.chat_summary_analyzed, analysis.total),
                    modifier = Modifier.padding(bottom = 6.dp),
                )
            }
            if (analysis.stats.isEmpty()) {
                item {
                    Text(
                        text = context.localizedChatString(R.string.chat_summary_no_messages),
                    )
                }
                return@LazyColumn
            }
            val maxCount = analysis.stats.maxOf { it.second }.coerceAtLeast(1)
            items(analysis.stats.size) { i ->
                val (speaker, count) = analysis.stats[i]
                SpeakerBarRow(speaker, count, maxCount)
            }
        }
    }

    /** 发言人横向柱状图单行（v3：占比可视化）。 */
    @Composable
    private fun SpeakerBarRow(speaker: String, count: Int, maxCount: Int) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = speaker,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(96.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(14.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(7.dp)),
            ) {
                val fraction = count.toFloat() / maxCount
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(7.dp)),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "\u00d7 $count",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** 智能总结 Tab：总结正文 + 生成时间。 */
    @Composable
    private fun SummaryContent(state: SummaryState) {
        val context = androidx.compose.ui.platform.LocalContext.current
        LazyColumn(Modifier.heightIn(max = 340.dp)) {
            when (state) {
                SummaryState.Idle -> {
                    item {
                        Text(context.localizedChatString(R.string.chat_summary_not_generated))
                    }
                }
                SummaryState.Loading -> {
                    item { Text(stringResource(R.string.chat_summary_loading)) }
                }
                is SummaryState.Error -> {
                    item { Text(state.message) }
                }
                is SummaryState.Success -> {
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                                .padding(12.dp),
                        ) {
                            Text(
                                text = state.summary,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = context.localizedChatString(R.string.chat_summary_generated_at, state.generatedAt),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    item {
                        var copied by remember { mutableStateOf(false) }
                        Button(
                            onClick = {
                                copyToClipboard(context, state.summary)
                                copied = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                context.localizedChatString(
                                    if (copied) R.string.chat_summary_copied else R.string.chat_summary_copy
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    /** 读取最近消息并本地统计发言人，构建分析与转写文本（不调用模型）。 */
    private suspend fun analyzeConversation(
        context: Context,
        convId: String,
        timeRange: TimeRange,
    ): ConversationAnalysis {
        val since = timeRange.durationMs?.let { System.currentTimeMillis() - it } ?: 0L
        val messages = WeDatabaseApi.getMessagesSince(convId, since, MAX_MESSAGES)
        if (messages.isEmpty()) {
            return ConversationAnalysis(emptyList(), 0, "")
        }
        val stats = LinkedHashMap<String, Int>()
        val transcript = StringBuilder()
        val isGroup = convId.isGroupChatWxId
        for (msg in messages) {
            val speaker = resolveSpeaker(context, msg, convId, isGroup)
            stats[speaker] = (stats[speaker] ?: 0) + 1
            transcript.append(speaker).append(": ").append(displayText(context, msg, isGroup)).append('\n')
        }
        val sorted = stats.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return ConversationAnalysis(sorted, messages.size, transcript.toString())
    }

    /**
     * 发言人展示逻辑（v3：备注 > 昵称 > 不显示 id）：
     *  - 自己发的消息 →「我」
     *  - 单聊对方 →「对方」
     *  - 群聊 → 优先群内备注（群昵称），其次联系人备注，再回退联系人昵称，兜底显示「未知」。
     * 绝不展示原始 wxid。
     */
    private fun resolveSpeaker(context: Context, msg: WeMessage, convId: String, isGroup: Boolean): String {
        if (msg.isSend != 0) return context.localizedChatString(R.string.chat_summary_self)
        if (!isGroup) return context.localizedChatString(R.string.chat_summary_peer)
        // 群聊消息存储格式：发送者 wxid 后跟冒号换行，再接正文。
        val senderId = msg.content.substringBefore(':')
            .takeIf { it.isNotBlank() && it.length <= 64 }
        if (senderId.isNullOrBlank()) {
            return context.localizedChatString(R.string.chat_summary_unknown)
        }
        // 1) 群内备注（群昵称）优先
        runCatching {
            val memberName = WeDatabaseApi.getGroupMemberDisplayName(convId, senderId)
            if (!memberName.isNullOrBlank()) return memberName
        }
        // 2) 联系人备注 → 3) 联系人昵称；绝不回退到 wxid
        runCatching {
            WeDatabaseApi.getFriend(senderId)?.let { friend ->
                if (friend.remarkName.isNotBlank()) return friend.remarkName
                if (friend.nickname.isNotBlank()) return friend.nickname
            }
        }
        return context.localizedChatString(R.string.chat_summary_unknown)
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

    /**
     * 调用模型生成结构化总结。模型选择：群聊总结专用模型（[summaryModelId]）优先，
     * 未指定时回退全局默认模型，再回退到已配置的第一个模型。
     */
    private suspend fun generateSummary(
        context: Context,
        transcript: String,
        focus: String,
        summaryModelId: String?,
    ): String {
        val modelId = summaryModelId
            ?: WeAgentSettings.defaultModelId()
            ?: WeAgentRepository.firstModelId()
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
        val userMessage = if (focus.isNotBlank()) {
            transcript + "\n\n（请特别关注以下要点：$focus）"
        } else {
            transcript
        }
        val request = ModelProviderManager.buildRequest(
            model = model,
            messages = listOf(
                LlmMessage(role = LlmRole.SYSTEM, content = systemPrompt),
                LlmMessage(role = LlmRole.USER, content = userMessage),
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

    /** 消息时间范围选择行（v3：自定义分析范围）。 */
    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun TimeRangeRow(
        selected: TimeRange,
        expanded: Boolean,
        onExpandedChange: (Boolean) -> Unit,
        onSelect: (TimeRange) -> Unit,
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val options = TimeRange.entries.map { range ->
            DropdownOption<TimeRange>(range, context.localizedChatString(range.labelRes))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.localizedChatString(R.string.chat_summary_time_range),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(8.dp))
            Box {
                Text(
                    text = "\u25bc " + context.localizedChatString(selected.labelRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clickable { onExpandedChange(!expanded) }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
                ExpressiveOptionDropdown(
                    expanded = expanded,
                    value = selected,
                    options = options,
                    onDismissRequest = { onExpandedChange(false) },
                    onValueChange = { onSelect(it) },
                )
            }
        }
    }

    /** 预设提示词快捷入口（v3：一键填入关注点）。 */
    @Composable
    private fun PresetPromptRow(onPick: (String) -> Unit) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val presets = listOf(
            context.localizedChatString(R.string.chat_summary_preset_topic),
            context.localizedChatString(R.string.chat_summary_preset_action),
            context.localizedChatString(R.string.chat_summary_preset_dispute),
            context.localizedChatString(R.string.chat_summary_preset_progress),
        )
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = context.localizedChatString(R.string.chat_summary_preset_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(presets) { preset ->
                    Box(
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                            .clickable { onPick(preset) }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Text(
                            text = preset,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
