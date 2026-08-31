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
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import dev.ujhhgtg.wekit.utils.android.copyToClipboard
import dev.ujhhgtg.wekit.utils.serialization.DefaultJson
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 聊天自动总结：长按任意消息，从菜单发起对当前会话消息的总结。
 *
 * 读取该会话（时间范围内）的【全部】消息（不设条数上限，数据库有多少取多少），本地统计
 * 各发言人消息条数（分析报告），再调用 WeAgent 配置的模型（默认跟随全局默认模型，群聊可
 * 在弹窗内单独指定总结模型）生成结构化总结（智能总结），最终以 Compose 弹窗卡片呈现。
 * 弹窗内分析与总结分为两个 Tab。
 */
object ChatSummary : SwitchFeature(),
    WeChatMessageContextMenuApi.IMenuItemsProvider {

    override val technicalId = "聊天总结"
    override val nameRes = R.string.feature_chat_summary_name
    override val categoryIds = listOf(FeatureCategoryIds.CHAT)
    override val descriptionRes = R.string.feature_chat_summary_description

    // v4：消息条数不设上限——getMessagesSince 传 limit<=0 时读取数据库该会话的全部消息。
    // 移除 v3 的 MAX_MESSAGES=500 固定上限，数据库有多少就分析多少。

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
        data class Success(
            val summary: String,
            val report: ChatReport?,
            val generatedAt: String,
        ) : SummaryState
        data class Error(val message: String) : SummaryState
    }

    /** 本地分析结果（发言人统计 + 供模型使用的转写文本）。 */
    private data class ConversationAnalysis(
        val stats: List<Pair<String, Int>>,
        val total: Int,
        val transcript: String,
    )

    /** 结构化总结（v5）：analysis=图1 分析报告，summary=图2 智能总结。 */
    @Serializable
    private data class ChatReport(
        val analysis: AnalysisData? = null,
        val summary: SummaryData? = null,
    )

    @Serializable
    private data class AnalysisData(
        val overview: String = "",
        val metrics: MetricsData? = null,
        val topSpeakers: List<SpeakerStat> = emptyList(),
        val keywords: List<String> = emptyList(),
        val timeSlots: List<TimeSlotData> = emptyList(),
        val emotions: List<EmotionData> = emptyList(),
        val insights: List<String> = emptyList(),
    )

    @Serializable
    private data class MetricsData(
        val participants: Int = 0,
        val messages: Int = 0,
        val historyMessages: Int = 0,
    )

    @Serializable
    private data class SpeakerStat(val name: String = "", val count: Int = 0)

    @Serializable
    private data class TimeSlotData(val label: String = "", val name: String = "", val percent: Int = 0)

    @Serializable
    private data class EmotionData(val label: String = "", val value: Int = 0)

    @Serializable
    private data class SummaryData(
        val keywords: List<String> = emptyList(),
        val language: String = "",
        val participants: String = "",
        val structure: String = "",
        val duration: String = "",
        val messageDensity: String = "",
        val emotion: String = "",
        val activeUsers: List<SpeakerStat> = emptyList(),
        val topics: List<TopicData> = emptyList(),
    )

    @Serializable
    private data class TopicData(val title: String = "", val points: List<String> = emptyList())

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
                        SummaryState.Success(text, parseChatReport(text), stamp)
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
                        SummaryTab.ANALYSIS -> AnalysisContent(analysis, summaryState)
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

    /** 分析报告 Tab：优先渲染图1 紫色分析卡片（模型 JSON），未生成/无结构时回退本地发言人统计。 */
    @Composable
    private fun AnalysisContent(analysis: ConversationAnalysis?, state: SummaryState) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val report = (state as? SummaryState.Success)?.report?.analysis
        if (report != null && (
                report.metrics != null || report.topSpeakers.isNotEmpty() || report.keywords.isNotEmpty()
                || report.timeSlots.isNotEmpty() || report.emotions.isNotEmpty() || report.insights.isNotEmpty()
                )) {
            LazyColumn(Modifier.heightIn(max = 340.dp)) {
                item { AnalysisPurpleCard(report) }
            }
            return
        }
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
                    val summaryData = state.report?.summary
                    if (summaryData != null) {
                        item { SummaryReportCard(state, summaryData) }
                    } else {
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

    /** 读取时间范围内的全部消息并本地统计发言人，构建分析与转写文本（不调用模型）。 */
    private suspend fun analyzeConversation(
        context: Context,
        convId: String,
        timeRange: TimeRange,
    ): ConversationAnalysis {
        val since = timeRange.durationMs?.let { System.currentTimeMillis() - it } ?: 0L
        // v4：limit<=0 表示不限条数，数据库有多少取多少
        val messages = WeDatabaseApi.getMessagesSince(convId, since, limit = -1)
        if (messages.isEmpty()) {
            return ConversationAnalysis(emptyList(), 0, "")
        }
        val stats = LinkedHashMap<String, Int>()
        val transcript = StringBuilder()
        val isGroup = convId.isGroupChatWxId
        // v4：群聊一次性预加载「群成员 -> 显示名」映射（含备注/昵称，无好友过滤），
        // 避免逐条查询联系人导致大量成员解析为「未知」。
        val speakerMap = if (isGroup) {
            WeDatabaseApi.getGroupDisplayNameMap(convId)
        } else {
            emptyMap()
        }
        for (msg in messages) {
            val speaker = resolveSpeaker(context, msg, isGroup, speakerMap)
            stats[speaker] = (stats[speaker] ?: 0) + 1
            transcript.append(speaker).append(": ").append(displayText(context, msg, isGroup)).append('\n')
        }
        val sorted = stats.entries.sortedByDescending { it.value }.map { it.key to it.value }
        return ConversationAnalysis(sorted, messages.size, transcript.toString())
    }

    /**
     * 发言人展示逻辑（v4：群成员映射直查）：
     *  - 自己发的消息 →「我」
     *  - 单聊对方 →「对方」
     *  - 群聊 → 从消息前缀提取发送者 wxid/微信号，在【全量群成员映射】（备注优先、昵称兜底，
     *    同时覆盖 wxid 与自定义微信号 alias，不受"是否好友"限制）中查显示名，查不到才兜底「未知」。
     * 绝不展示原始 wxid。
     */
    private fun resolveSpeaker(
        context: Context,
        msg: WeMessage,
        isGroup: Boolean,
        speakerMap: Map<String, String>,
    ): String {
        if (msg.isSend != 0) return context.localizedChatString(R.string.chat_summary_self)
        if (!isGroup) return context.localizedChatString(R.string.chat_summary_peer)
        // 群聊消息存储格式：发送者 wxid/微信号 后跟冒号换行，再接正文。
        val senderId = msg.content.substringBefore(':')
            .trim()
            .takeIf { it.isNotBlank() && it.length <= 64 }
        if (senderId.isNullOrBlank()) {
            return context.localizedChatString(R.string.chat_summary_unknown)
        }
        return speakerMap[senderId] ?: context.localizedChatString(R.string.chat_summary_unknown)
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

    /** 解析模型返回的结构化 JSON（v5：图1 分析报告 + 图2 智能总结），失败返回 null 交由 UI 兜底。 */
    private fun parseChatReport(raw: String): ChatReport? {
        val text = raw.trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val json = text.substring(start, end + 1)
        return runCatching { DefaultJson.decodeFromString<ChatReport>(json) }.getOrNull()
    }

    /** 图1 分析报告紫色卡片（核心指标/活跃排行/词云/时段画像/情绪指数/智能洞察）。 */
    @Composable
    private fun AnalysisPurpleCard(report: AnalysisData) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val purpleBrush = Brush.verticalGradient(listOf(Color(0xFF7C4DFF), Color(0xFF9C6BFF)))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(purpleBrush, RoundedCornerShape(16.dp))
                .padding(14.dp),
        ) {
            Text(
                text = context.localizedChatString(R.string.chat_summary_tab_analysis),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            if (report.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = report.overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
            // 核心指标
            report.metrics?.let { m ->
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PurpleMetricCell(m.participants.toString(), context.localizedChatString(R.string.chat_summary_metric_participants), Modifier.weight(1f))
                    PurpleMetricCell(m.messages.toString(), context.localizedChatString(R.string.chat_summary_metric_messages), Modifier.weight(1f))
                    PurpleMetricCell(m.historyMessages.toString(), context.localizedChatString(R.string.chat_summary_metric_history), Modifier.weight(1f))
                }
            }
            // 活跃排行
            if (report.topSpeakers.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PurpleSectionTitle(context.localizedChatString(R.string.chat_summary_card_top_speakers))
                val maxCount = report.topSpeakers.maxOf { it.count }.coerceAtLeast(1)
                report.topSpeakers.forEach { s ->
                    PurpleBarRow(s.name, s.count, maxCount)
                }
            }
            // 词云
            if (report.keywords.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PurpleSectionTitle(context.localizedChatString(R.string.chat_summary_card_wordcloud))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    report.keywords.forEach { kw ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color.White.copy(alpha = 0.22f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(text = kw, style = MaterialTheme.typography.labelMedium, color = Color.White)
                        }
                    }
                }
            }
            // 时段画像
            if (report.timeSlots.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PurpleSectionTitle(context.localizedChatString(R.string.chat_summary_card_time_slots))
                report.timeSlots.forEach { slot ->
                    PurplePercentRow("${slot.label} ${slot.name}", slot.percent)
                }
            }
            // 情绪指数
            if (report.emotions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PurpleSectionTitle(context.localizedChatString(R.string.chat_summary_card_emotions))
                report.emotions.forEach { e ->
                    PurplePercentRow(e.label, e.value)
                }
            }
            // 智能洞察
            if (report.insights.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                PurpleSectionTitle(context.localizedChatString(R.string.chat_summary_card_insights))
                report.insights.forEach { insight ->
                    Text(
                        text = "\u2022 $insight",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
        }
    }

    /** 紫色卡片小标题。 */
    @Composable
    private fun PurpleSectionTitle(text: String) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White.copy(alpha = 0.85f),
            modifier = Modifier.padding(bottom = 6.dp),
        )
    }

    /** 紫色卡片核心指标格子。 */
    @Composable
    private fun PurpleMetricCell(value: String, label: String, modifier: Modifier = Modifier) {
        Column(
            modifier = modifier
                .clip(RoundedCornerShape(10.dp))
                .background(Color.White.copy(alpha = 0.16f))
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(text = value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        }
    }

    /** 紫色卡片活跃排行进度条。 */
    @Composable
    private fun PurpleBarRow(speaker: String, count: Int, maxCount: Int) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = speaker,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier.width(88.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                val fraction = count.toFloat() / maxCount
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "\u00d7 $count", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
        }
    }

    /** 紫色卡片时段/情绪进度条（带百分比/分值）。 */
    @Composable
    private fun PurplePercentRow(label: String, value: Int) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White,
                modifier = Modifier.width(108.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.White.copy(alpha = 0.18f)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth((value.coerceIn(0, 100) / 100f))
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "$value", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.9f))
        }
    }

    /** 图2 智能总结卡片（快速摘要/活跃用户/按话题分类）。 */
    @Composable
    private fun SummaryReportCard(state: SummaryState.Success, data: SummaryData) {
        val context = androidx.compose.ui.platform.LocalContext.current
        Column(Modifier.fillMaxWidth()) {
            // 快速摘要
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(12.dp),
            ) {
                Text(
                    text = context.localizedChatString(R.string.chat_summary_card_quick_summary),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                if (data.keywords.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        data.keywords.forEach { kw ->
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(text = kw, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_language), data.language)
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_participants), data.participants)
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_structure), data.structure)
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_duration), data.duration)
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_density), data.messageDensity)
                SummaryKeyValueRow(context.localizedChatString(R.string.chat_summary_emotion), data.emotion)
            }
            // 活跃用户
            if (data.activeUsers.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = context.localizedChatString(R.string.chat_summary_card_active_users),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                val maxCount = data.activeUsers.maxOf { it.count }.coerceAtLeast(1)
                data.activeUsers.forEach { u ->
                    ActiveUserBar(u.name, u.count, maxCount)
                }
            }
            // 按话题分类
            if (data.topics.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = context.localizedChatString(R.string.chat_summary_card_topics),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(4.dp))
                data.topics.forEach { topic ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))
                            .padding(10.dp)
                            .padding(bottom = 6.dp),
                    ) {
                        Text(
                            text = topic.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        topic.points.forEach { point ->
                            Text(
                                text = "\u2022 $point",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.localizedChatString(R.string.chat_summary_generated_at, state.generatedAt),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    /** 智能总结键值行。 */
    @Composable
    private fun SummaryKeyValueRow(label: String, value: String) {
        if (value.isBlank()) return
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(76.dp),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }
    }

    /** 智能总结活跃用户进度条。 */
    @Composable
    private fun ActiveUserBar(name: String, count: Int, maxCount: Int) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(88.dp),
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            ) {
                val fraction = count.toFloat() / maxCount
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(text = "\u00d7 $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
