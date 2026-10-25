package com.newoether.agora.ui.remote

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.repository.SettingsRepository
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.model.StableMessageList
import com.newoether.agora.remote.RemoteDelivery
import com.newoether.agora.remote.RemoteState
import com.newoether.agora.remote.RemoteViewModel
import com.newoether.agora.remote.displayTitle
import com.newoether.agora.ui.chat.ChatTopBar
import com.newoether.agora.ui.chat.MessageList
import com.newoether.agora.ui.chat.rememberConversationInteractionState
import com.newoether.agora.ui.chat.rememberScrollIsolatedMessages
import com.newoether.agora.ui.chat.rememberStreamingTailController
import com.newoether.agora.ui.chat.streamingTailAvailability
import com.newoether.agora.ui.components.AnimatedBlobBackground
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareModalBottomSheet
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.ThinkingControlPanel
import com.newoether.agora.ui.common.OpenAiServiceTierControlPanel
import com.newoether.agora.ui.components.DialogWindowEdgeToEdge
import com.newoether.agora.util.gradientBlur
import kotlinx.coroutines.flow.filterNotNull

/**
 * Superficie de conversación remota sobre los componentes de chat del fork (ruta B):
 * la lista reusa MessageList en modo lectura y el composer es propio de Remote —
 * el bottom bar nativo está atado a ChatViewModel, que una sesión remota no tiene.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RemoteConversation(
    state: RemoteState, vm: RemoteViewModel, settings: SettingsRepository, active: Boolean, onBack: () -> Unit,
    onSnackbarOffsetChanged: (androidx.compose.ui.unit.Dp) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    onMessage: (String, String?, (() -> Unit)?) -> Unit,
) {
    val owner = state.owner ?: return
    val session = state.session ?: return
    val density = LocalDensity.current
    val motion = LocalAgoraMotionPolicy.current
    val haptics = LocalAgoraHaptics.current
    val blur by settings.blurEffectsEnabled.collectAsState(initial = false)
    val amoled by settings.amoledEnabled.collectAsState(initial = false)
    val inlineMath by settings.parseInlineDollarMath.collectAsState(initial = false)
    val stickToBottom by settings.stickToBottom.collectAsState(initial = true)
    val toolCallDisplayMode by settings.toolCallDisplayMode.collectAsState()
    val thinkingSegmentDisplayMode by settings.thinkingSegmentDisplayMode.collectAsState()
    val autoExpandActiveGroup by settings.autoExpandActiveGroup.collectAsState()
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val field = remember(owner) { TextFieldState(state.drafts[owner].orEmpty()) }
    val focus = remember { FocusRequester() }
    val attempt = state.attempts[owner]
    val attachments = state.attachments[owner].orEmpty()
    val running = state.runtime?.isRunning == true
    val stopping = state.isStopping
    val ready = state.isDraft || state.runtime?.status in setOf("idle", "active", "ready")
    LaunchedEffect(owner, field) { snapshotFlow { field.text.toString() }.collect { vm.editDraft(owner, it) } }
    // Posicionamiento inicial: una sesión abierta aterriza al final; un draft queda arriba.
    var initiallyPositioned by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(owner, state.hydrationEnabled) {
        if (state.hydrationEnabled && !initiallyPositioned && state.messageGroups.isNotEmpty()) {
            listState.scrollToItem(state.messageGroups.lastIndex.coerceAtLeast(0))
            initiallyPositioned = true
        }
    }
    val switching = !initiallyPositioned
    val messages = remember(state.messageGroups) { state.messageGroups.map { it.stub } }
    val tail = messages.lastOrNull()?.takeIf {
        it.status in setOf(MessageStatus.SENDING, MessageStatus.THINKING, MessageStatus.TOOL_CALLING)
    }
    val generationVisible = tail != null
    val messageState = rememberUpdatedState(messages)
    val observe = remember(owner) { { id: String -> vm.observeMessage(owner, id) } }
    val searchMessages: suspend (String, List<String>) -> List<com.newoether.agora.model.ChatMessage> =
        remember(owner, state.hydrationRevision) { { _, ids -> vm.searchMessages(owner, ids) } }
    val interaction = rememberConversationInteractionState(owner, messageState, listState, searchMessages)
    val searchMatch = interaction.searchMatches.getOrNull(interaction.searchMatchIndex)
    BackHandler(active && interaction.searchActive) {
        interaction.dismissSearch(); focusManager.clearFocus()
    }
    var clearedAttempt by remember(owner) {
        mutableStateOf(attempt?.takeIf { it.delivery == RemoteDelivery.DELIVERED }?.clientId)
    }
    var shownBusyAttempt by remember(owner) { mutableStateOf(clearedAttempt) }
    val acceptedPendingClear = attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId
    val submitting = attempt?.delivery in setOf(RemoteDelivery.SUBMITTING, RemoteDelivery.ACCEPTED) || acceptedPendingClear
    LaunchedEffect(attempt, shownBusyAttempt) {
        if (attempt?.delivery == RemoteDelivery.DELIVERED && clearedAttempt != attempt.clientId && shownBusyAttempt == attempt.clientId) {
            clearedAttempt = attempt.clientId
            if (state.drafts[owner].isNullOrEmpty() && field.text.toString() == attempt.text) {
                field.edit { replace(0, length, "") }
            }
            if (!interaction.searchActive) haptics.confirm()
        }
    }
    // Focus inicial del composer cuando la sesión queda lista.
    LaunchedEffect(active, ready) {
        if (active && ready && state.isDraft) focus.requestFocus()
    }
    val renderMessages = rememberScrollIsolatedMessages(owner, messageState, listState,
        bypassScrollIsolation = generationVisible)
    val follow = streamingTailAvailability(
        generationActive = generationVisible,
        blocked = switching || interaction.searchActive || !motion.allowProgrammaticScrollMotion,
        programmaticHandoff = false,
    )
    val streaming = tail?.let { message ->
        key(owner, message.id) {
            val flow = remember(owner, message.id) { vm.observeMessage(owner, message.id).filterNotNull() }
            val payload by flow.collectAsState(initial = vm.cachedMessage(owner, message.id))
            payload
        }
    }
    val unknownDeliveryText = stringResource(R.string.remote_unknown)
    val checkedDeliveryText = stringResource(R.string.remote_check)
    val historyStartId = messages.firstOrNull()?.id
    val atHistoryBoundary by remember(listState, historyStartId) {
        derivedStateOf {
            historyStartId != null && !listState.canScrollBackward &&
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.key == historyStartId
        }
    }
    LaunchedEffect(owner, active, switching, interaction.searchActive, state.historyCursor, state.loadingMore, state.error, historyStartId) {
        if (!active || switching || interaction.searchActive || state.historyCursor == null ||
            state.loadingMore || state.error) return@LaunchedEffect
        snapshotFlow { atHistoryBoundary }.collect { atTop -> if (atTop) vm.loadMore() }
    }
    var showThinkingSheet by remember(owner) { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by remember(owner) { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) { showThinkingSheet = false; showOpenAiServiceTierSheet = false }
    }
    val thinkingEnabled = state.selectedEffort != null && state.selectedEffort != "none"
    val thinkingLevel = state.selectedEffort.orEmpty()
    val openAiServiceTierEnabled = state.selectedServiceTier != null
    val openAiServiceTier = state.selectedServiceTier.orEmpty()
    val settingsEnabled = active && state.canEditSettings
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).clearFocusOnTap()) {
        val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
        if (!amoled) AnimatedBlobBackground(centerAlpha = if (dark) 0.02f else 0f,
            quarterAlpha = if (dark) 0.01f else 0f, blurRadius = 40f, dark = dark,
            blurEnabled = blur, motionEnabled = false)
        @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
        Scaffold(containerColor = Color.Transparent, contentWindowInsets = WindowInsets(0, 0, 0, 0), topBar = {
            ChatTopBar(
                isNewChatMode = false, conversations = emptyList(),
                currentConversationId = session.id,
                currentConversationTitle = session.displayTitle(stringResource(R.string.new_chat)),
                totalTokens = state.runtime?.contextTokens ?: 0,
                contextTokenBudget = state.runtime?.contextWindow ?: 0,
                searchActive = interaction.searchActive, searchQuery = interaction.searchQuery,
                searchMatchIndex = interaction.searchMatchIndex, searchMatchCount = interaction.searchMatches.size,
                onSearchQueryChange = interaction::updateSearchQuery,
                onSearchPrevious = { if (interaction.previousSearchMatch()) haptics.selection() },
                onSearchNext = { if (interaction.nextSearchMatch()) haptics.selection() },
                onSearchDismiss = { interaction.dismissSearch(); focusManager.clearFocus() },
                onSearchClick = { interaction.activateSearch() },
                onNavigateBack = onBack, onOpenDrawer = onBack,
                onSystemPromptClick = {}, onNewChat = vm::newSession,
            )
        }) { _ ->
            Box(Modifier.fillMaxSize()) {
                MessageList(messages = StableMessageList(renderMessages.value),
                    allMessages = StableMessageList(messages), conversationId = owner,
                    state = listState, onMediaClick = onMediaClick, parseInlineDollarMath = inlineMath,
                    isLoading = generationVisible, isSwitching = switching, streamingMessage = streaming,
                    searchQuery = if (interaction.searchActive) interaction.searchQuery else "",
                    activeSearchMatch = searchMatch,
                    onSearchMatchDistance = interaction::recordSearchMatchDistance,
                    onSearchTurnsChanged = interaction::recordSearchTurns,
                    streamingAutoFollowEnabled = follow.enabled && stickToBottom,
                    streamingAutoFollowPaused = follow.paused,
                    streamingTailController = rememberStreamingTailController(owner),
                    toolCallDisplayMode = toolCallDisplayMode, thinkingSegmentDisplayMode = thinkingSegmentDisplayMode,
                    autoExpandActiveGroup = autoExpandActiveGroup,
                    observeMessage = observe,
                    modifier = Modifier.fillMaxSize().gradientBlur(blurAtTopDp = if (blur) 8f else 0f,
                        blurAtBottomDp = 0f, fadeHeightDp = 40f, bottomOverlayHeight = 0.dp),
                )
            }
        }
        // Composer propio de Remote: el ChatBottomBar nativo vive atado a ChatViewModel.
        Surface(
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            tonalElevation = 6.dp,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .navigationBarsPadding().imePadding(),
        ) {
            Column(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) {
                if (attachments.isNotEmpty()) Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    attachments.forEach { attachment ->
                        AssistChip(onClick = { vm.removeAttachment(owner, attachment.localId) },
                            label = { Text(attachment.fileName.orEmpty(), maxLines = 1) })
                    }
                }
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextField(state = field, modifier = Modifier.weight(1f),
                        placeholder = { Text(stringResource(R.string.ask_agora)) },
                        lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 4),
                        shape = MaterialTheme.shapes.extraLarge,
                        enabled = active && !submitting && !state.controlling)
                    val showStop = running && !stopping && field.text.isBlank() && attachments.isEmpty()
                    if (showStop) {
                        FilledIconButton(onClick = { vm.stop() }, modifier = Modifier.size(56.dp)) {
                            Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.send))
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                if (attempt?.delivery == RemoteDelivery.UNKNOWN) {
                                    onMessage(unknownDeliveryText, checkedDeliveryText) { vm.acknowledgeUnknown(owner) }
                                } else {
                                    vm.editDraft(owner, field.text.toString()); vm.send()
                                }
                            },
                            enabled = active && !stopping && !state.controlling && !submitting &&
                                (field.text.isNotBlank() || attachments.isNotEmpty()),
                            modifier = Modifier.size(56.dp),
                        ) { Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send)) }
                    }
                }
                // Menús de settings remotos: thinking y tier en la fila de controles.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showThinkingSheet = true },
                        enabled = settingsEnabled && state.selectedEffort != null) {
                        Text(stringResource(R.string.thinking))
                    }
                    TextButton(onClick = { showOpenAiServiceTierSheet = true },
                        enabled = settingsEnabled && state.selectedServiceTier != null) {
                        Text(stringResource(R.string.openai_service_tier_title))
                    }
                }
            }
        }
    }
    if (showThinkingSheet) {
        MotionAwareModalBottomSheet(onDismissRequest = { showThinkingSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            DialogWindowEdgeToEdge()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
                ThinkingControlPanel(enabled = thinkingEnabled, level = thinkingLevel,
                    budgetEnabled = false, budgetTokens = 4096,
                    onEnabledChange = {}, onLevelChange = vm::setThinkingLevel,
                    onBudgetEnabledChange = {}, onBudgetTokensChange = {}, showHeader = false)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
    if (showOpenAiServiceTierSheet) {
        MotionAwareModalBottomSheet(onDismissRequest = { showOpenAiServiceTierSheet = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer) {
            DialogWindowEdgeToEdge()
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 16.dp)) {
                OpenAiServiceTierControlPanel(enabled = openAiServiceTierEnabled, tier = openAiServiceTier,
                    onEnabledChange = {}, onTierChange = { vm.setServiceTier(it.takeIf(String::isNotEmpty)) },
                    showHeader = false)
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
