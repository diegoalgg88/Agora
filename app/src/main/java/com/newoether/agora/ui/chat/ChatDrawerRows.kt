package com.newoether.agora.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import com.newoether.agora.R
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType

private val DrawerRowHeight = 44.dp

/** Leading check mark shown on every row while selection mode is active. */
@Composable
private fun SelectionMark(checked: Boolean, selected: Boolean) {
    val tint = when {
        checked -> MaterialTheme.colorScheme.primary
        selected -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Icon(
        imageVector = if (checked) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
        contentDescription = null,
        tint = tint,
        modifier = Modifier.size(20.dp),
    )
}

@Composable
private fun SelectionMarkSlot(visible: Boolean, checked: Boolean, highlighted: Boolean) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(180)) + expandHorizontally(tween(180)),
        exit = fadeOut(tween(180)) + shrinkHorizontally(tween(180)),
    ) {
        Row {
            SelectionMark(checked = checked, selected = highlighted)
            Spacer(modifier = Modifier.width(12.dp))
        }
    }
}

/**
 * One conversation row of the drawer. Pure presentation: every action is a callback, so the
 * drawer keeps the single owner of selection and delete decisions.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrawerConversationRow(
    title: String,
    isCurrent: Boolean,
    isGenerating: Boolean,
    hasUnreadGeneration: Boolean,
    isSwitching: Boolean,
    deleteEnabled: Boolean,
    inProject: Boolean,
    indented: Boolean,
    selectionActive: Boolean,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggleSelect: () -> Unit,
    onStartSelection: () -> Unit,
    onGenerateTitle: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onRemoveFromProject: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAgoraHaptics.current
    val density = LocalDensity.current
    val indicator = resolveDrawerConversationIndicator(
        isGenerating = isGenerating,
        isSelected = isCurrent,
        hasUnreadGeneration = hasUnreadGeneration,
    )
    val menuEnabled = !isSwitching && !isGenerating
    val unreadDescription = stringResource(R.string.conversation_unread_generation)
    val highlighted = if (selectionActive) checked else isCurrent
    var showMenu by remember { mutableStateOf(false) }
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var lastPosition by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }

    Box(modifier = modifier.padding(start = if (indented) 16.dp else 0.dp)) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(DrawerRowHeight)
                .padding(vertical = 2.dp)
                .clip(CircleShape)
                .semantics { if (selectionActive) selected = checked }
                .pointerInput(showMenu) {
                    if (!showMenu) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                lastPosition = event.changes.first().position
                            }
                        }
                    }
                }
                .combinedClickable(
                    enabled = selectionActive || !isSwitching,
                    hapticFeedbackEnabled = false,
                    onClick = {
                        if (selectionActive) {
                            haptics.selection()
                            onToggleSelect()
                        } else {
                            onOpen()
                        }
                    },
                    onLongClick = {
                        haptics.longPress()
                        if (selectionActive) {
                            onToggleSelect()
                        } else {
                            pressOffset = with(density) {
                                val x = lastPosition.x.toDp().coerceIn(16.dp, 200.dp)
                                DpOffset(x, lastPosition.y.toDp() - 28.dp)
                            }
                            showMenu = true
                        }
                    },
                ),
            color = if (highlighted) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionMarkSlot(selectionActive, checked, highlighted)
                Crossfade(
                    targetState = title,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    modifier = Modifier.weight(1f),
                    label = "DrawerConversationTitleCrossfade",
                ) { text ->
                    Text(
                        text = text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (highlighted) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    // Calificado al top-level: dentro del Box el nombre simple resolvería a la
                    // extensión RowScope.AnimatedVisibility del Row exterior vía implicit
                    // receiver, lo cual Kotlin prohíbe en esta cadena de receivers.
                    androidx.compose.animation.AnimatedVisibility(
                        visible = indicator == DrawerConversationIndicator.GENERATING,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = if (highlighted) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                        )
                    }
                    androidx.compose.animation.AnimatedVisibility(
                        visible = indicator == DrawerConversationIndicator.UNREAD,
                        enter = fadeIn(tween(200)),
                        exit = fadeOut(tween(200)),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(MaterialTheme.colorScheme.primary, CircleShape)
                                .semantics { contentDescription = unreadDescription },
                        )
                    }
                }
            }
        }

        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 16.dp,
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = pressOffset,
            shape = RoundedCornerShape(12.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.drawer_select)) },
                leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onStartSelection()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.generate_title)) },
                leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) },
                enabled = menuEnabled,
                onClick = {
                    showMenu = false
                    onGenerateTitle()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                enabled = menuEnabled,
                onClick = {
                    showMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.project_move_to)) },
                leadingIcon = { Icon(Icons.Default.Folder, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onMove()
                },
            )
            if (inProject) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.project_remove_from)) },
                    leadingIcon = { Icon(Icons.Default.FolderOff, contentDescription = null) },
                    onClick = {
                        showMenu = false
                        onRemoveFromProject()
                    },
                )
            }
            val errorColor = MaterialTheme.colorScheme.error.copy(alpha = if (deleteEnabled) 1f else 0.5f)
            DropdownMenuItem(
                text = { Text(stringResource(R.string.delete), color = errorColor) },
                leadingIcon = {
                    Icon(Icons.Default.Delete, contentDescription = null, tint = errorColor)
                },
                enabled = deleteEnabled,
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}

/** Header row of a project: tap folds/unfolds (or toggles its chats in selection mode). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun DrawerProjectRow(
    name: String,
    chatCount: Int,
    collapsed: Boolean,
    selectionActive: Boolean,
    allSelected: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalAgoraHaptics.current
    var showMenu by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (collapsed) -90f else 0f,
        animationSpec = tween(200),
        label = "DrawerProjectChevron",
    )
    val toggleDescription = stringResource(
        if (collapsed) R.string.project_expand else R.string.project_collapse,
    )
    Box(modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(DrawerRowHeight)
                .padding(vertical = 2.dp)
                .clip(CircleShape)
                .semantics { if (!selectionActive) contentDescription = toggleDescription }
                .combinedClickable(
                    hapticFeedbackEnabled = false,
                    onClick = {
                        if (selectionActive) haptics.selection()
                        onClick()
                    },
                    onLongClick = {
                        if (!selectionActive) {
                            haptics.longPress()
                            showMenu = true
                        }
                    },
                ),
            color = Color.Transparent,
            shape = CircleShape,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SelectionMarkSlot(selectionActive, allSelected, highlighted = false)
                Icon(
                    Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = chatCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = rotation },
                )
            }
        }
        DropdownMenu(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            tonalElevation = 16.dp,
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            offset = DpOffset(16.dp, 0.dp),
            shape = RoundedCornerShape(12.dp),
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.rename)) },
                leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                onClick = {
                    showMenu = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        stringResource(R.string.project_delete_title),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    showMenu = false
                    onDelete()
                },
            )
        }
    }
}

/** Replaces the Tasks / New chat buttons while at least one chat is selected. */
@Composable
internal fun DrawerSelectionBar(
    selectedCount: Int,
    allSelected: Boolean,
    canRemoveFromProject: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onMove: () -> Unit,
    onRemoveFromProject: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().height(52.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.drawer_cancel_selection),
                )
            }
            Text(
                text = stringResource(R.string.drawer_selected_count, selectedCount),
                style = ChatType.drawerButton,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            )
            IconButton(onClick = onSelectAll, enabled = !allSelected, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.SelectAll,
                    contentDescription = stringResource(R.string.drawer_select_all),
                )
            }
            IconButton(onClick = onMove, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = stringResource(R.string.project_move_to),
                )
            }
            if (canRemoveFromProject) {
                IconButton(onClick = onRemoveFromProject, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.FolderOff,
                        contentDescription = stringResource(R.string.project_remove_from),
                    )
                }
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
