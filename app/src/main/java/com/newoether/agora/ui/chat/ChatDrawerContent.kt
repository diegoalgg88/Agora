package com.newoether.agora.ui.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.replaceCustomProviderIdsForDisplay
import com.newoether.agora.ui.chat.search.DrawerSearchBar
import com.newoether.agora.ui.chat.search.SearchResultItem
import com.newoether.agora.ui.chat.search.rememberDrawerSearchState
import com.newoether.agora.ui.components.clearFocusOnTap
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.verticalEdgeFade
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class DrawerConversationIndicator {
    NONE,
    GENERATING,
    UNREAD,
}

internal fun resolveDrawerConversationIndicator(
    isGenerating: Boolean,
    isSelected: Boolean,
    hasUnreadGeneration: Boolean,
): DrawerConversationIndicator = when {
    isGenerating -> DrawerConversationIndicator.GENERATING
    hasUnreadGeneration && !isSelected -> DrawerConversationIndicator.UNREAD
    else -> DrawerConversationIndicator.NONE
}

internal val DrawerEdgeFadeTolerance = 2.dp

internal fun isDrawerListAtTop(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffsetPx: Int,
    tolerancePx: Int,
): Boolean =
    firstVisibleItemIndex == 0 &&
        firstVisibleItemScrollOffsetPx <= tolerancePx.coerceAtLeast(0)

internal fun isDrawerListAtBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    lastVisibleItemEndOffsetPx: Int?,
    viewportEndOffsetPx: Int,
    tolerancePx: Int,
): Boolean {
    if (totalItemsCount == 0) return true
    return lastVisibleItemIndex == totalItemsCount - 1 &&
        lastVisibleItemEndOffsetPx != null &&
        lastVisibleItemEndOffsetPx <= viewportEndOffsetPx + tolerancePx.coerceAtLeast(0)
}

/**
 * The conversation navigation drawer: search box, new-chat button, projects and conversation
 * list with per-item context menu, multi-selection actions, and the settings button. Reads its
 * own flows from [viewModel]; shared host state (drawer slide progress, settings-button
 * position, dialog requests) is written back through callbacks so [ChatApp] keeps owning it.
 *
 * Selection and project dialogs are drawer-local UI state. Durable changes always go through
 * `viewModel.projects` (organization only) or `viewModel.deleteConversation` (the existing
 * guarded delete path), never a parallel pipeline.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatDrawerContent(
    viewModel: ChatViewModel,
    drawerWidth: Dp,
    scope: CoroutineScope,
    inputFocusRequester: FocusRequester,
    onRequestClose: suspend () -> Unit,
    onSettingsButtonTop: (Float) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTasks: () -> Unit,
    onOpenRemote: () -> Unit,
    onRequestRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
) {
    val haptics = LocalAgoraHaptics.current
    val motionPolicy = LocalAgoraMotionPolicy.current
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val windowHeightPx = LocalWindowInfo.current.containerSize.height.toFloat()

    val conversationList by viewModel.conversations.collectAsState()
    val conversations = conversationList.orEmpty()
    val isConversationListLoading = conversationList == null
    val currentConversationId by viewModel.currentConversationId.collectAsState()
    val isSwitching by viewModel.isSwitching.collectAsState()
    val generatingConversationIds by viewModel.generatingConversationIds.collectAsState()
    val customProviders by viewModel.settings.customProviders.collectAsState()
    val projects by viewModel.projects.projects.collectAsState()
    val memberships by viewModel.projects.memberships.collectAsState()
    val rows = remember(conversations, projects, memberships) {
        buildDrawerRows(conversations, projects, memberships)
    }
    val search = rememberDrawerSearchState(viewModel)

    var selectedIds by remember { mutableStateOf(emptySet<String>()) }
    var projectDialog by remember { mutableStateOf<ProjectDialog?>(null) }
    val selectionActive = selectedIds.isNotEmpty()
    val allConversationIds = remember(conversations) { conversations.map { it.id } }
    // Drop ids of chats that were deleted (or never existed) so the count cannot go stale.
    LaunchedEffect(allConversationIds) {
        val pruned = selectedIds.intersect(allConversationIds.toSet())
        if (pruned != selectedIds) selectedIds = pruned
    }
    LaunchedEffect(search.isActive) {
        if (search.isActive) selectedIds = emptySet()
    }
    BackHandler(enabled = selectionActive) { selectedIds = emptySet() }

    ModalDrawerSheet(
        drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerTonalElevation = 1.dp,
        modifier = Modifier
            .width(drawerWidth)
            .graphicsLayer {
                clip = true
            }
    ) {
        val conversationListState = rememberLazyListState()
        val searchListState = rememberLazyListState()
        val activeListState = if (search.isActive) searchListState else conversationListState
        val edgeFadeTolerancePx = with(density) { DrawerEdgeFadeTolerance.roundToPx() }
        val latestRows by rememberUpdatedState(rows)
        val latestMotionPolicy by rememberUpdatedState(motionPolicy)
        val submittingConversationIds by viewModel.conversationComposerSubmission
            .activeOwnerIds
            .collectAsState()
        LaunchedEffect(viewModel, conversationListState) {
            viewModel.firstMessageCommitted.collect { conversationId ->
                if (viewModel.currentConversationId.value != conversationId) return@collect
                snapshotFlow {
                    val currentRows = latestRows
                    currentRows.any {
                        it is DrawerRow.Chat && it.conversation.id == conversationId
                    } && conversationListState.layoutInfo.totalItemsCount == currentRows.size
                }.first { ready -> ready }
                if (viewModel.currentConversationId.value != conversationId) return@collect
                val targetIndex = latestRows.indexOfFirst {
                    it is DrawerRow.Chat && it.conversation.id == conversationId
                }.coerceAtLeast(0)
                if (targetIndex > 0) {
                    // Projects sit above ungrouped chats: bring the new chat itself into view.
                    if (latestMotionPolicy.allowProgrammaticScrollMotion) {
                        conversationListState.animateScrollToItem(targetIndex)
                    } else {
                        conversationListState.scrollToItem(targetIndex)
                    }
                } else if (latestMotionPolicy.allowProgrammaticScrollMotion) {
                    conversationListState.animateToAbsoluteTop(
                        estimatedItemSizePx = with(density) { 44.dp.toPx() },
                        minimumStepPx = with(density) { 2.dp.toPx() },
                        feedbackSpec = SendFeedbackScrollSpec,
                    )
                } else {
                    conversationListState.scrollToItem(0)
                }
            }
        }
        SideEffect {
            val firstVisibleIndex = conversationListState.firstVisibleItemIndex
            val firstVisibleConversationId = conversationListState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == firstVisibleIndex }
                ?.key
                ?.toString()
                ?.removePrefix("conversation:")
            val indexedConversationId =
                (rows.getOrNull(firstVisibleIndex) as? DrawerRow.Chat)?.conversation?.id
            if (
                !search.isActive &&
                conversationListState.layoutInfo.totalItemsCount == rows.size &&
                firstVisibleConversationId != null &&
                indexedConversationId != null &&
                indexedConversationId != firstVisibleConversationId &&
                conversations.any { it.id == firstVisibleConversationId }
            ) {
                conversationListState.requestScrollToItem(
                    firstVisibleIndex,
                    conversationListState.firstVisibleItemScrollOffset,
                )
            }
        }
        val atTop by remember(activeListState, edgeFadeTolerancePx) {
            derivedStateOf {
                isDrawerListAtTop(
                    firstVisibleItemIndex = activeListState.firstVisibleItemIndex,
                    firstVisibleItemScrollOffsetPx =
                        activeListState.firstVisibleItemScrollOffset,
                    tolerancePx = edgeFadeTolerancePx,
                )
            }
        }
        val atBottom by remember(activeListState, edgeFadeTolerancePx) {
            derivedStateOf {
                val layoutInfo = activeListState.layoutInfo
                val lastVisibleItem = layoutInfo.visibleItemsInfo.maxByOrNull { it.index }
                isDrawerListAtBottom(
                    totalItemsCount = layoutInfo.totalItemsCount,
                    lastVisibleItemIndex = lastVisibleItem?.index,
                    lastVisibleItemEndOffsetPx = lastVisibleItem?.let { it.offset + it.size },
                    viewportEndOffsetPx = layoutInfo.viewportEndOffset,
                    tolerancePx = edgeFadeTolerancePx,
                )
            }
        }
        val stw by animateFloatAsState(if (atTop) 0f else 1f, tween(200))
        val sbw by animateFloatAsState(if (atBottom) 0f else 1f, tween(200))
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .clearFocusOnTap()
        ) {
            Text(stringResource(R.string.conversations), style = ChatType.conversationsTitle)
            Spacer(modifier = Modifier.height(12.dp))

            DrawerSearchBar(
                query = search.query,
                onQueryChange = { search.query = it },
                searching = search.isSearching,
            )
            Spacer(modifier = Modifier.height(12.dp))

            Crossfade(
                targetState = search.results.takeIf { search.isActive },
                animationSpec = tween(180),
                label = "DrawerConversationContentTransition",
                modifier = Modifier.weight(1f),
            ) { targetResults ->
                if (targetResults == null) {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Crossfade(
                            targetState = selectionActive,
                            animationSpec = tween(180),
                            label = "DrawerActionsTransition",
                            modifier = Modifier.animateContentSize(tween(200)),
                        ) { selecting ->
                            if (selecting) {
                                DrawerSelectionBar(
                                    selectedCount = selectedIds.size,
                                    allSelected = selectedIds.size >= allConversationIds.size,
                                    canRemoveFromProject = selectedIds.any { it in memberships },
                                    onClose = { selectedIds = emptySet() },
                                    onSelectAll = { selectedIds = allConversationIds.toSet() },
                                    onMove = { projectDialog = ProjectDialog.Move(selectedIds) },
                                    onRemoveFromProject = {
                                        viewModel.projects.unassign(selectedIds)
                                        selectedIds = emptySet()
                                    },
                                    onDelete = { projectDialog = ProjectDialog.BulkDelete },
                                )
                            } else {
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        FilledTonalButton(
                                            onClick = {
                                                focusManager.clearFocus()
                                                onOpenTasks()
                                                scope.launch { onRequestClose() }
                                            },
                                            modifier = Modifier.weight(1f).height(42.dp),
                                            shape = CircleShape
                                        ) {
                                            Icon(Icons.Default.Repeat, null, modifier = Modifier.size(20.dp))
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(stringResource(R.string.tasks), style = ChatType.drawerButton)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        FilledTonalIconButton(
                                            onClick = { projectDialog = ProjectDialog.Create(emptySet()) },
                                            modifier = Modifier.size(42.dp),
                                        ) {
                                            Icon(
                                                Icons.Default.CreateNewFolder,
                                                contentDescription = stringResource(R.string.project_new),
                                                modifier = Modifier.size(20.dp),
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(2.dp))

                                    // Remote: debajo de Tasks, mismo grupo del drawer (contrato remote-sessions.md).
                                    FilledTonalButton(
                                        onClick = {
                                            focusManager.clearFocus()
                                            onOpenRemote()
                                            scope.launch { onRequestClose() }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(46.dp),
                                        shape = RoundedCornerShape(
                                            topStart = 5.dp, topEnd = 5.dp,
                                            bottomStart = 24.dp, bottomEnd = 24.dp,
                                        ),
                                    ) {
                                        Icon(Icons.Default.Devices, null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.remote_title), style = ChatType.drawerButton)
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    val newChatDisabled = isSwitching
                                    val newChatContainer by animateColorAsState(
                                        if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.primary,
                                        tween(300), label = "newChatContainer"
                                    )
                                    val newChatContent by animateColorAsState(
                                        if (newChatDisabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                                        else MaterialTheme.colorScheme.onPrimary,
                                        tween(300), label = "newChatContent"
                                    )
                                    Button(
                                        onClick = {
                                            if (!newChatDisabled) {
                                                viewModel.createNewChat()
                                                scope.launch {
                                                    onRequestClose()
                                                    inputFocusRequester.requestFocus()
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth().height(42.dp),
                                        enabled = true,
                                        shape = CircleShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = newChatContainer,
                                            contentColor = newChatContent
                                        )
                                    ) {
                                        Icon(Icons.Default.Add, null, modifier = Modifier.size(20.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(stringResource(R.string.new_chat), style = ChatType.drawerButton)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Box(modifier = Modifier.weight(1f)) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = !isConversationListLoading,
                                modifier = Modifier.fillMaxSize(),
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(180)),
                            ) {
                                LazyColumn(
                                    state = conversationListState,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .verticalEdgeFade(
                                            edgeFadeDp = 40f,
                                            topWeight = stw,
                                            bottomWeight = sbw,
                                        ),
                                ) {
                                    items(rows, key = { it.key }) { row ->
                                        val itemModifier = Modifier.animateItem(
                                            fadeInSpec = null,
                                            placementSpec = if (
                                                motionPolicy.allowSpatialTransitions
                                            ) {
                                                tween(400)
                                            } else {
                                                null
                                            },
                                            fadeOutSpec = tween(180),
                                        )
                                        when (row) {
                                            is DrawerRow.Section -> {
                                                Text(
                                                    stringResource(if (row.pinned) {
                                                        R.string.pinned_conversations
                                                    } else {
                                                        R.string.conversations
                                                    }),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier
                                                        // El grupo Conversations lleva espacio extra arriba para separarse del Pinned.
                                                        .padding(
                                                            start = 16.dp,
                                                            end = 16.dp,
                                                            top = if (row.pinned) 8.dp else 12.dp,
                                                            bottom = 8.dp,
                                                        )
                                                        .semantics { heading() },
                                                )
                                            }

                                            is DrawerRow.Project -> DrawerProjectRow(
                                                name = row.project.name,
                                                chatCount = row.chatIds.size,
                                                collapsed = row.project.collapsed,
                                                selectionActive = selectionActive,
                                                allSelected = row.chatIds.isNotEmpty() &&
                                                    selectedIds.containsAll(row.chatIds),
                                                onClick = {
                                                    if (selectionActive) {
                                                        selectedIds =
                                                            if (selectedIds.containsAll(row.chatIds)) {
                                                                selectedIds - row.chatIds.toSet()
                                                            } else {
                                                                selectedIds + row.chatIds
                                                            }
                                                    } else {
                                                        viewModel.projects.setCollapsed(
                                                            row.project.id,
                                                            !row.project.collapsed,
                                                        )
                                                    }
                                                },
                                                onRename = {
                                                    projectDialog = ProjectDialog.Rename(
                                                        row.project.id,
                                                        row.project.name,
                                                    )
                                                },
                                                onDelete = {
                                                    projectDialog = ProjectDialog.Delete(
                                                        row.project.id,
                                                        row.project.name,
                                                    )
                                                },
                                                modifier = itemModifier,
                                            )

                                            is DrawerRow.Chat -> {
                                                val conversation = row.conversation
                                                val isGenerating =
                                                    conversation.id in generatingConversationIds
                                                DrawerConversationRow(
                                                    title = replaceCustomProviderIdsForDisplay(
                                                        conversation.title,
                                                        customProviders,
                                                    ),
                                                    isCurrent = conversation.id == currentConversationId,
                                                    isGenerating = isGenerating,
                                                    hasUnreadGeneration = conversation.hasUnreadGeneration,
                                                    isSwitching = isSwitching,
                                                    isPinned = conversation.isPinned,
                                                    deleteEnabled = !isSwitching && !isGenerating &&
                                                        conversation.id !in submittingConversationIds,
                                                    inProject = row.projectId != null,
                                                    indented = row.projectId != null,
                                                    selectionActive = selectionActive,
                                                    checked = conversation.id in selectedIds,
                                                    onOpen = {
                                                        viewModel.selectConversation(conversation.id)
                                                        scope.launch { onRequestClose() }
                                                    },
                                                    onToggleSelect = {
                                                        selectedIds =
                                                            if (conversation.id in selectedIds) {
                                                                selectedIds - conversation.id
                                                            } else {
                                                                selectedIds + conversation.id
                                                            }
                                                    },
                                                    onStartSelection = {
                                                        selectedIds = selectedIds + conversation.id
                                                    },
                                                    onTogglePinned = {
                                                        viewModel.setConversationPinned(
                                                            conversation.id,
                                                            !conversation.isPinned,
                                                        )
                                                    },
                                                    onGenerateTitle = {
                                                        viewModel.generateTitle(conversation.id)
                                                    },
                                                    onRename = {
                                                        onRequestRename(conversation.id, conversation.title)
                                                    },
                                                    onMove = {
                                                        projectDialog =
                                                            ProjectDialog.Move(setOf(conversation.id))
                                                    },
                                                    onRemoveFromProject = {
                                                        viewModel.projects.unassign(setOf(conversation.id))
                                                    },
                                                    onDelete = { onRequestDelete(conversation.id) },
                                                    modifier = itemModifier,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            androidx.compose.animation.AnimatedVisibility(
                                visible = isConversationListLoading,
                                modifier = Modifier.fillMaxSize(),
                                enter = fadeIn(tween(180)),
                                exit = fadeOut(tween(180)),
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        strokeWidth = 3.dp,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (targetResults.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.TopCenter,
                            ) {
                                Text(
                                    stringResource(R.string.search_no_results),
                                    modifier = Modifier.padding(vertical = 24.dp),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        } else {
                            val grouped = targetResults.groupBy { it.first.conversationId }
                            val titleMap = conversations.associate { it.id to it.title }
                            LazyColumn(
                                state = searchListState,
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalEdgeFade(
                                        edgeFadeDp = 40f,
                                        topWeight = stw,
                                        bottomWeight = sbw,
                                    ),
                            ) {
                                items(
                                    grouped.entries.toList(),
                                    key = { "search:${it.key}" },
                                ) { (convId, entries) ->
                                    val bestScore = entries.maxOfOrNull { it.second } ?: 0f
                                    Box(modifier = Modifier.fillMaxWidth()) {
                                        SearchResultItem(
                                            title = titleMap[convId]
                                                ?: stringResource(R.string.unknown),
                                            messages = entries.map { it.first },
                                            score = bestScore,
                                            query = search.query,
                                            customProviders = customProviders,
                                            onClick = {
                                                viewModel.selectConversation(convId)
                                                scope.launch {
                                                    onRequestClose()
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            FilledTonalButton(
                onClick = {
                    focusManager.clearFocus()
                    onOpenSettings()
                    scope.launch { onRequestClose() }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .onGloballyPositioned { coords ->
                        val buttonTopPx = coords.positionInWindow().y
                        onSettingsButtonTop((windowHeightPx - buttonTopPx) / density.density)
                    },
                shape = CircleShape
            ) {
                Icon(Icons.Default.Settings, null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.settings), style = ChatType.drawerButton)
            }
        }

        when (val dialog = projectDialog) {
            null -> Unit
            is ProjectDialog.Create -> ProjectNameDialog(
                titleRes = R.string.project_new,
                confirmRes = R.string.project_create,
                initialName = "",
                onSave = { name ->
                    viewModel.projects.create(name, dialog.assignIds)
                    if (dialog.assignIds.isNotEmpty()) selectedIds = emptySet()
                    projectDialog = null
                },
                onDismiss = { projectDialog = null },
            )

            is ProjectDialog.Rename -> ProjectNameDialog(
                titleRes = R.string.project_rename_title,
                confirmRes = R.string.save,
                initialName = dialog.name,
                onSave = { name ->
                    viewModel.projects.rename(dialog.id, name)
                    projectDialog = null
                },
                onDismiss = { projectDialog = null },
            )

            is ProjectDialog.Delete -> ProjectDeleteDialog(
                projectName = dialog.name,
                onConfirm = {
                    viewModel.projects.delete(dialog.id)
                    projectDialog = null
                },
                onDismiss = { projectDialog = null },
            )

            is ProjectDialog.Move -> MoveToProjectDialog(
                projects = projects,
                onPick = { projectId ->
                    viewModel.projects.assign(projectId, dialog.ids)
                    selectedIds = emptySet()
                    projectDialog = null
                },
                onCreateNew = { projectDialog = ProjectDialog.Create(dialog.ids) },
                onDismiss = { projectDialog = null },
            )

            ProjectDialog.BulkDelete -> {
                // Same guards as the single-delete path: generating or submitting chats are skipped.
                val deletable = selectedIds.filter {
                    it !in generatingConversationIds &&
                        it !in submittingConversationIds &&
                        !viewModel.isConversationDeleteLocked(it)
                }
                BulkDeleteDialog(
                    count = deletable.size,
                    onConfirm = {
                        deletable.forEach { viewModel.deleteConversation(it) }
                        haptics.destructiveConfirmed()
                        selectedIds = emptySet()
                        projectDialog = null
                    },
                    onDismiss = { projectDialog = null },
                )
            }
        }
    }
}
