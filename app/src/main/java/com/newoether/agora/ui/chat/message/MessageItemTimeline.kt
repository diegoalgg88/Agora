package com.newoether.agora.ui.chat.message

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.newoether.agora.R
import com.newoether.agora.model.ChatMessage
import com.newoether.agora.model.CitationRecord
import com.newoether.agora.model.MessageSegment
import com.newoether.agora.model.MessageStatus
import com.newoether.agora.ui.components.*
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView

// ── Timeline / segment rendering (extracted from MessageItem.kt) ──────────────
// Pure code-motion. Entry points used by MessageItem.kt are `internal`; the rest
// stay file-private. Behavior unchanged.

@Composable
private fun StartAnchoredHorizontalOverflowHost(
    content: @Composable BoxScope.() -> Unit,
) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .wrapContentWidth(Alignment.Start, unbounded = true),
    contentAlignment = Alignment.TopStart,
    content = content,
)

private enum class CompactSegmentIcon {
    LOADING,
    THINKING,
    TOOL,
    IMAGE,
}

internal fun compactSegmentHasActiveContent(
    segs: List<MessageSegment>,
    message: ChatMessage,
    useLiveStatus: Boolean,
    generationActive: Boolean = message.status == MessageStatus.SENDING ||
        message.status == MessageStatus.THINKING ||
        message.status == MessageStatus.TOOL_CALLING ||
        message.status == MessageStatus.TRANSCRIBING,
): Boolean {
    if (!generationActive) return false
    return segs.any { segment ->
        when (segment.type) {
            "tool" -> ToolPresentationResolver.resolve(segment).isActive
            "thought" -> useLiveStatus && message.status == MessageStatus.THINKING
            "transcription" -> useLiveStatus &&
                (message.status == MessageStatus.TRANSCRIBING ||
                    message.status == MessageStatus.TOOL_CALLING)
            else -> false
        }
    }
}

internal fun compactSegmentShowsLoading(
    hasActiveContent: Boolean,
    generationActive: Boolean,
    isCurrentCard: Boolean,
): Boolean = hasActiveContent || (generationActive && isCurrentCard)

@Composable
internal fun CompactSegmentBlock(
    segs: List<MessageSegment>,
    segmentIndices: List<Int>,
    message: ChatMessage,
    isStreaming: Boolean,
    useLiveStatus: Boolean,
    generationActive: Boolean,
    isCurrentCard: Boolean,
    expandedStates: SnapshotStateMap<String, Boolean>,
    expansionKey: String,
    cardAppearanceKey: String = "$expansionKey:card",
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    autoExpansionController: GroupedSegmentAutoExpansionController? = null,
    autoExpansionEnabled: Boolean = false,
    autoExpansionActive: Boolean = false,
    collapseForImageBoundary: Boolean = false,
    modifier: Modifier = Modifier,
    topPaddingExtra: Dp = 0.dp,
    bottomPaddingExtra: Dp = 6.dp,
    onSegmentClick: (Int) -> Unit,
    onHeaderClick: (() -> Unit)? = null,
    opensDetailSheet: Boolean = false,
    onExpansionStarted: (String) -> Unit = {},
    onExpansionSettled: (String) -> Unit = {},
    onBlockHeightChanged: (Int) -> Unit = {}
) {
    if (segs.isEmpty()) return
    val allowSpatialTransitions = LocalAgoraMotionPolicy.current.allowSpatialTransitions
    val containsToolSummary = segs.any { it.type == "tool" }
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAppearanceKey,
        isStreaming = isStreaming,
    )
    val cardAppearanceModifier = generationLifecycleAppearanceModifier(
        animationKey = cardAppearanceKey,
        animate = animateCardAppearance,
        durationMillis = SEGMENT_ENTER_DURATION_MS,
        initialScale = SEGMENT_ENTER_INITIAL_SCALE,
    )
    val collapseImageBoundaryOnAppearance =
        autoExpansionController?.shouldCollapseForImageBoundary(
            key = expansionKey,
            hasImageBoundary = collapseForImageBoundary,
        ) == true
    val initiallyAutoExpanded =
        autoExpansionController?.shouldPresentInitiallyExpanded(
            key = expansionKey,
            isActive = autoExpansionActive,
            enabled = autoExpansionEnabled,
        ) == true
    val isExpanded = groupedSegmentExpandedState(
        persistedExpanded = expandedStates[expansionKey],
        initiallyAutoExpanded = initiallyAutoExpanded,
        collapseForImageBoundary = collapseImageBoundaryOnAppearance,
    )
    val currentOnExpansionStarted by rememberUpdatedState(onExpansionStarted)
    val currentOnExpansionSettled by rememberUpdatedState(onExpansionSettled)
    LaunchedEffect(
        autoExpansionController,
        expansionKey,
        autoExpansionEnabled,
        autoExpansionActive,
        collapseImageBoundaryOnAppearance,
        collapseForImageBoundary,
    ) {
        if (collapseImageBoundaryOnAppearance) {
            val claimed = autoExpansionController.claimImageBoundaryCollapse(
                key = expansionKey,
                hasImageBoundary = collapseForImageBoundary,
            ) == true
            if (claimed && expandedStates[expansionKey] != false) {
                expandedStates[expansionKey] = false
            }
            return@LaunchedEffect
        }
        val targetExpanded = when (
            autoExpansionController?.update(
                key = expansionKey,
                isActive = autoExpansionActive,
                enabled = autoExpansionEnabled,
            )
        ) {
            GroupedSegmentAutoExpansionAction.EXPAND -> true
            GroupedSegmentAutoExpansionAction.COLLAPSE -> false
            GroupedSegmentAutoExpansionAction.NONE, null -> null
        }
        if (
            targetExpanded != null &&
            (expandedStates[expansionKey] ?: false) != targetExpanded
        ) {
            val alreadyPresentedAtTarget =
                targetExpanded && initiallyAutoExpanded
            if (!alreadyPresentedAtTarget) {
                currentOnExpansionStarted(expansionKey)
            }
            expandedStates[expansionKey] = targetExpanded
        }
    }
    val isThinking = useLiveStatus &&
        message.status == MessageStatus.THINKING &&
        segs.any { it.type == "thought" }
    val isTranscribing = useLiveStatus && message.status == MessageStatus.TRANSCRIBING
    val toolCount = segs.count { it.type == "tool" }
    val thoughtMs = thoughtDurationMs(segs, fallbackMs = message.thoughtTimeMs)
    val hasThought = thoughtMs != null && thoughtMs > 0
    val cardHasActiveContent = compactSegmentHasActiveContent(
        segs = segs,
        message = message,
        useLiveStatus = useLiveStatus,
        generationActive = generationActive,
    )
    val showLoading = compactSegmentShowsLoading(cardHasActiveContent, generationActive, isCurrentCard)
    val baseCollapsedTitle = compactSegmentDisplayTitle(
        segs = segs,
        message = message,
        useLiveStatus = useLiveStatus,
    )
    val collapsedTitle = if (!isThinking && !hasThought && toolCount > 1) {
        "$toolCount Tools · $baseCollapsedTitle"
    } else {
        baseCollapsedTitle
    }
    val collapsedIcon = when {
        showLoading -> CompactSegmentIcon.LOADING
        !isThinking && !hasThought && toolCount > 0 -> CompactSegmentIcon.TOOL
        isTranscribing || collapsedTitle == "Image Transcription" -> CompactSegmentIcon.IMAGE
        else -> CompactSegmentIcon.THINKING
    }
    val expansionTransition = updateTransition(
        targetState = isExpanded,
        label = "compactSegmentExpansion",
    )
    val mergedBottomPadding = if (collapseImageBoundaryOnAppearance) {
        GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
    } else if (allowSpatialTransitions) {
        val animatedPadding by expansionTransition.animateDp(
            transitionSpec = { tween(400) },
            label = "compactSegmentPad",
        ) { expanded ->
            if (expanded) {
                12.dp
            } else if (collapseForImageBoundary) {
                GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
            } else {
                4.dp
            }
        }
        animatedPadding
    } else if (
        retainExpandedLayoutDuringFade(
            currentExpanded = expansionTransition.currentState,
            targetExpanded = expansionTransition.targetState,
        )
    ) {
        12.dp
    } else if (collapseForImageBoundary) {
        GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
    } else {
        4.dp
    }
    LaunchedEffect(expansionTransition, expansionKey) {
        var observedRunning = false
        snapshotFlow { expansionTransition.isRunning }.collect { running ->
            if (running) {
                observedRunning = true
            } else if (observedRunning) {
                observedRunning = false
                currentOnExpansionSettled(expansionKey)
            }
        }
    }
    LaunchedEffect(isExpanded, allowSpatialTransitions, expansionKey) {
        if (!allowSpatialTransitions) {
            currentOnExpansionSettled(expansionKey)
        }
    }
    DisposableEffect(expansionKey) {
        onDispose { currentOnExpansionSettled(expansionKey) }
    }

    val compactTitleStyle = ChatType.body.copy(
        fontSize = 13.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
    )
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val useExpandedHeaderLayout =
        !collapseImageBoundaryOnAppearance &&
            retainExpandedLayoutDuringFade(
                currentExpanded = expansionTransition.currentState,
                targetExpanded = expansionTransition.targetState,
            )
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.TopStart,
    ) {
        val titleWidth = with(density) {
            textMeasurer.measure(
                text = AnnotatedString(collapsedTitle),
                style = compactTitleStyle,
                softWrap = false,
                maxLines = 1,
            ).size.width.toDp()
        }
        val collapsedHeaderWidth =
            12.dp + 18.dp + 8.dp + titleWidth + 4.dp + 18.dp + 12.dp +
                THINKING_COLLAPSED_WIDTH_ALLOWANCE_DP.dp
        val availableWidth = if (maxWidth.value.isFinite()) {
            maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp
        } else {
            collapsedHeaderWidth
        }
        val collapsedCardWidth = minOf(collapsedHeaderWidth, availableWidth)
        val cardWidth by expansionTransition.animateDp(
            transitionSpec = {
                if (collapseImageBoundaryOnAppearance || !allowSpatialTransitions) {
                    snap()
                } else {
                    tween(
                        durationMillis = 400,
                        easing = LinearOutSlowInEasing,
                    )
                }
            },
            label = "compactSegmentWidth",
        ) { expanded ->
            if (expanded) availableWidth else collapsedCardWidth
        }
        val contentLayoutWidth =
            if (useExpandedHeaderLayout) availableWidth else collapsedCardWidth
        val targetDisclosureRotation = when {
            opensDetailSheet -> -90f
            isExpanded -> 180f
            else -> 0f
        }
        val disclosureRotation by animateFloatAsState(
            targetValue = targetDisclosureRotation,
            animationSpec = if (
                allowSpatialTransitions && !collapseImageBoundaryOnAppearance
            ) {
                tween(durationMillis = 400, easing = LinearOutSlowInEasing)
            } else {
                snap()
            },
            label = "compactSegmentDisclosureRotation",
        )

        StartAnchoredHorizontalOverflowHost {
            Surface(
                tonalElevation = 2.dp,
                shape = RoundedCornerShape(18.dp),
            modifier = Modifier
                .offset(x = (-AUXILIARY_CARD_START_EXTENSION_DP).dp)
                .width(cardWidth)
                .padding(
                    top = 8.dp + topPaddingExtra,
                    bottom = mergedBottomPadding + bottomPaddingExtra,
                )
                .then(cardAppearanceModifier)
                .noOpBringIntoView()
                .onSizeChanged { onBlockHeightChanged(it.height) },
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .wrapContentSize(Alignment.TopStart, unbounded = true)
                        .requiredWidth(contentLayoutWidth),
                ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable {
                        if (onHeaderClick != null) {
                            onHeaderClick()
                        } else {
                            currentOnExpansionStarted(expansionKey)
                            expandedStates[expansionKey] = !isExpanded
                        }
                    }
                    .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
            ) {
                Crossfade(
                    targetState = collapsedIcon,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentIcon:$expansionKey",
                    modifier = Modifier.size(18.dp),
                ) { icon ->
                    when (icon) {
                        CompactSegmentIcon.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                            strokeWidth = 2.dp,
                        )
                        CompactSegmentIcon.TOOL -> Icon(
                            Icons.Default.Build,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.IMAGE -> Icon(
                            Icons.Filled.Image,
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                        CompactSegmentIcon.THINKING -> Icon(
                            androidx.compose.ui.res.painterResource(
                                id = com.newoether.agora.R.drawable.neurology_24,
                            ),
                            null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        )
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                Crossfade(
                    targetState = collapsedTitle,
                    animationSpec = tween(
                        durationMillis = STATUS_CROSSFADE_DURATION_MS,
                        easing = LinearEasing,
                    ),
                    label = "compactSegmentTitle:$expansionKey",
                    modifier = Modifier.weight(1f),
                ) { title ->
                    Text(
                        text = title,
                        style = compactTitleStyle,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(modifier = Modifier.width(26.dp))
            }
            Box(modifier = Modifier.fillMaxWidth()) {
            expansionTransition.AnimatedVisibility(
                visible = { it },
                enter = when {
                    collapseImageBoundaryOnAppearance -> EnterTransition.None
                    containsToolSummary && allowSpatialTransitions -> expandVertically(tween(400))
                    containsToolSummary -> EnterTransition.None
                    allowSpatialTransitions -> fadeIn(tween(400)) + expandVertically(tween(400))
                    else -> fadeIn(tween(400))
                },
                exit = when {
                    collapseImageBoundaryOnAppearance -> ExitTransition.None
                    containsToolSummary && allowSpatialTransitions -> shrinkVertically(tween(400))
                    containsToolSummary -> ExitTransition.None
                    allowSpatialTransitions -> fadeOut(tween(400)) + shrinkVertically(tween(400))
                    else -> fadeOut(tween(400))
                },
            ) {
                Column {
                    Spacer(modifier = Modifier.height(2.dp))
                    segs.forEachIndexed { idx, seg ->
                      val detailIndex = segmentIndices.getOrElse(idx) { idx }
                      AnimatedTimelineBlockAppearance(
                        animationKey = detailSegmentAppearanceKey(
                            message.id,
                            detailIndex,
                            seg,
                        ),
                        appearanceRegistry = segmentAppearanceRegistry,
                        isStreaming = isStreaming,
                        forceOpaque = seg.type == "tool",
                      ) {
                       Column {
                        if ((seg.type == "thought" && seg.content.isNotBlank()) || seg.type == "transcription") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    if (seg.type == "transcription") transcriptionLabel(segs, idx) else stringResource(R.string.tool_thinking),
                                    style = ChatType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (seg.content.isNotBlank()) {
                                    if (seg.type == "thought") {
                                        StreamingThoughtPreviewText(
                                            content = seg.content,
                                            streaming =
                                                isStreaming &&
                                                    useLiveStatus &&
                                                    idx == segs.lastIndex,
                                        )
                                    } else {
                                        StreamingMutedText(
                                            text = seg.content.replace('\n', ' '),
                                            streaming =
                                                isStreaming &&
                                                    useLiveStatus &&
                                                    idx == segs.lastIndex,
                                        )
                                    }
                                } else {
                                    Text(
                                        text = "Image transcription is empty.",
                                        style = ChatType.metaNormal,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                    )
                                }
                            }
                        } else if (seg.type == "tool") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(18.dp))
                                    .clickable {
                                        onSegmentClick(segmentIndices.getOrElse(idx) { idx })
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    toolDisplayName(seg),
                                    style = ChatType.meta,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.SemiBold
                                )
                                val presentation = ToolPresentationResolver.resolve(seg)
                                ToolSummaryText(
                                    presentation = presentation,
                                    streaming =
                                        isStreaming &&
                                            useLiveStatus &&
                                            idx == segs.lastIndex,
                                )
                            }
                        }
                        if (idx < segs.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = Color.Transparent,
                            )
                        }
                       }
                      }
                    }
                }
            }
            }
                }
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    null,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 10.dp, end = 8.dp)
                        .size(18.dp)
                        .graphicsLayer { rotationZ = disclosureRotation },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
        }
    }
}

/**
 * Reduced Motion keeps expanded layout space for the whole content fade.
 *
 * On expansion the target state reserves the final layout immediately. On collapse the current
 * state retains that layout until the exit fade finishes. AnimatedVisibility then removes the
 * content in the same transition settlement that releases the card's external spacing.
 */
internal fun retainExpandedLayoutDuringFade(
    currentExpanded: Boolean,
    targetExpanded: Boolean,
): Boolean = currentExpanded || targetExpanded

internal fun timelineInfoTopPaddingExtra(hasVisibleMessageAbove: Boolean): Dp =
    if (hasVisibleMessageAbove) 8.dp else 0.dp

internal const val SEGMENT_GROUP_GAP_DP = 2
private const val GENERATED_IMAGE_BOUNDARY_GAP_DP = 8

private fun segmentGroupTopPadding(
    position: SegmentGroupPosition,
    topPaddingExtra: Dp,
): Dp = when (position) {
    SegmentGroupPosition.MIDDLE, SegmentGroupPosition.LAST -> SEGMENT_GROUP_GAP_DP.dp
    else -> 8.dp + topPaddingExtra
}

private fun segmentGroupBottomPadding(position: SegmentGroupPosition): Dp =
    if (position == SegmentGroupPosition.SINGLE || position == SegmentGroupPosition.LAST) {
        6.dp
    } else {
        0.dp
    }

@Composable
internal fun TimelineSegmentsContent(
    segments: List<MessageSegment>,
    detailSegments: List<MessageSegment>,
    message: ChatMessage,
    isStreaming: Boolean,
    generationActive: Boolean,
    groupAdjacentBlocks: Boolean,
    autoExpandActiveGroup: Boolean,
    autoExpansionController: GroupedSegmentAutoExpansionController,
    expandedStates: SnapshotStateMap<String, Boolean>,
    renderContext: ChatMarkdownRenderContext,
    searchHighlight: SearchHighlightSpec?,
    citations: List<CitationRecord>,
    onCitationActivate: (List<CitationRecord>) -> Unit,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onLayoutMutationStarted: (String) -> Unit,
    onLayoutMutationSettled: (String) -> Unit,
    onMediaClick: (List<String>, Int) -> Unit,
    opensDetailSheet: Boolean = false,
    preserveInitialCompactIdentity: Boolean = false,
    onGroupHeaderClick: ((List<Int>) -> Unit)? = null,
    onSegmentClick: (List<Int>) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        var detailIndex = 0
        var answerOffset = 0
        var index = 0
        var previousVisibleWasAnswer = false
        val lastVisibleSegmentIndex = segments.indexOfLast { segment ->
            segment.isVisibleAnswerSegment() || segment.isInfoSegment()
        }
        while (index < segments.size) {
            val seg = segments[index]
            when (seg.type) {
                "answer" -> {
                    if (seg.content.isNotBlank()) {
                        val answerIsStreaming =
                            isStreaming && index == lastVisibleSegmentIndex
                        val citationProjection = citationMarkdownProjection(
                            answerText = seg.content,
                            citations = citationRecordsForAnswerSlice(
                                citations = citations,
                                sliceStart = answerOffset,
                                sliceText = seg.content,
                            ),
                            isStreaming = answerIsStreaming,
                        )
                        val answerSearchHighlight = searchHighlight?.forSourceSlice(
                            sliceStart = answerOffset,
                            sliceLength = seg.content.length,
                        )
                        val answerAppearanceKey =
                            "${segmentAppearanceKey(message.id, index, seg)}:timeline"
                        val answerFadeTracker =
                            segmentAppearanceRegistry.streamingFadeTracker("$answerAppearanceKey:fade")
                        AnimatedTimelineBlockAppearance(
                            animationKey = answerAppearanceKey,
                            appearanceRegistry = segmentAppearanceRegistry,
                            isStreaming = isStreaming,
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = if (index == 0) 0.dp else 6.dp)
                            ) {
                                CitationTerminalProjectionHost(
                                    animationKey = answerAppearanceKey,
                                    projection = citationProjection,
                                    isStreaming = answerIsStreaming,
                                    onLayoutMutationStarted = onLayoutMutationStarted,
                                    onLayoutMutationSettled = onLayoutMutationSettled,
                                    modifier = Modifier.fillMaxWidth(),
                                ) { presentedProjection, presentedIsStreaming ->
                                    val presentedContent =
                                        presentedProjection?.markdown ?: seg.content
                                    CitationInlineContentHost(
                                        projection = presentedProjection,
                                        onActivate = onCitationActivate,
                                    ) {
                                        CompositionLocalProvider(
                                            LocalSearchHighlightSpec provides answerSearchHighlight,
                                        ) {
                                            StreamingMarkdownMessage(
                                                content = presentedContent,
                                                isStreaming = presentedIsStreaming,
                                                renderContext = renderContext,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .noOpBringIntoView(),
                                                selectionEnabled = !presentedIsStreaming,
                                                textDeltas = seg.streamingTextDeltas,
                                                fadeTracker = answerFadeTracker,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        previousVisibleWasAnswer = true
                    }
                    answerOffset += seg.content.length
                    index++
                }
                "thought", "tool", "transcription" -> {
                    if (groupAdjacentBlocks) {
                        val blockSegments = mutableListOf<MessageSegment>()
                        val blockDetailIndices = mutableListOf<Int>()
                        val blockEnd = groupedInfoBlockEndExclusive(segments, index)
                        var blockCursor = index
                        while (blockCursor < blockEnd) {
                            val blockSeg = segments[blockCursor]
                            if (blockSeg.isInfoSegment()) {
                                blockSegments.add(blockSeg)
                                blockDetailIndices.add(detailIndex)
                                detailIndex++
                            }
                            blockCursor++
                        }
                        val imageBoundary =
                            blockSegments.lastOrNull()?.takeIf { it.isImageGenerationSegment() }
                        val imageDetailIndex =
                            blockDetailIndices.lastOrNull().takeIf { imageBoundary != null }
                        val firstDetailIndex = blockDetailIndices.firstOrNull() ?: index
                        val useInitialCompactIdentity =
                            preserveInitialCompactIdentity &&
                                blockDetailIndices.firstOrNull() == 0
                        val expansionKey = if (useInitialCompactIdentity) {
                            message.id
                        } else {
                            groupedSegmentBlockAppearanceKey(message.id, firstDetailIndex)
                        }
                        val cardAppearanceKey = if (useInitialCompactIdentity) {
                            "${compactSegmentBlockAppearanceKey(message.id)}:card"
                        } else {
                            "$expansionKey:card"
                        }
                        val blockTopPaddingExtra =
                            timelineInfoTopPaddingExtra(previousVisibleWasAnswer)
                        CompactSegmentBlock(
                            segs = blockSegments,
                            segmentIndices = blockDetailIndices,
                            message = message,
                            isStreaming = isStreaming,
                            useLiveStatus =
                                isStreaming &&
                                    blockDetailIndices.lastOrNull() == detailSegments.lastIndex,
                            generationActive = generationActive,
                            isCurrentCard = blockEnd > lastVisibleSegmentIndex,
                            expandedStates = expandedStates,
                            expansionKey = expansionKey,
                            cardAppearanceKey = cardAppearanceKey,
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            autoExpansionController = autoExpansionController,
                            autoExpansionEnabled = autoExpandActiveGroup,
                            autoExpansionActive = isStreaming && blockEnd == segments.size,
                            collapseForImageBoundary = imageBoundary != null,
                            topPaddingExtra = blockTopPaddingExtra,
                            bottomPaddingExtra = 0.dp,
                            onExpansionStarted = onLayoutMutationStarted,
                            onExpansionSettled = onLayoutMutationSettled,
                            onSegmentClick = { selectedDetailIndex ->
                                onSegmentClick(listOf(selectedDetailIndex))
                            },
                            onHeaderClick = if (opensDetailSheet) {
                                {
                                    (onGroupHeaderClick ?: onSegmentClick)(blockDetailIndices)
                                }
                            } else {
                                null
                            },
                            opensDetailSheet = opensDetailSheet,
                        )
                        if (imageBoundary != null && imageDetailIndex != null) {
                            GeneratedImageThumbnail(
                                segment = imageBoundary,
                                messageId = message.id,
                                detailIndex = imageDetailIndex,
                                isStreaming = isStreaming,
                                segmentAppearanceRegistry = segmentAppearanceRegistry,
                                onMediaClick = onMediaClick,
                            )
                        }
                        previousVisibleWasAnswer = false
                        index = blockEnd
                    } else {
                        val currentDetailIndex = detailIndex
                        detailIndex++
                        val cardTopPaddingExtra =
                            timelineInfoTopPaddingExtra(previousVisibleWasAnswer)
                        val timelineKey = detailSegmentAppearanceKey(
                            message.id,
                            currentDetailIndex,
                            seg,
                        )
                        TimelineInfoSegmentCard(
                            seg = seg,
                            detailSegments = detailSegments,
                            detailIndex = currentDetailIndex,
                            isStreamingContent =
                                isStreaming && index == lastVisibleSegmentIndex,
                            animateAppearance = isStreaming,
                            topPaddingExtra = cardTopPaddingExtra,
                            groupPosition = timelineSegmentGroupPosition(segments, index),
                            endsAtGeneratedImageBoundary = seg.isImageGenerationSegment(),
                            extendIntoMessageInsets = true,
                            cardAnimationKey = "$timelineKey:card",
                            segmentAppearanceRegistry = segmentAppearanceRegistry,
                            onClick = { onSegmentClick(listOf(currentDetailIndex)) },
                        )
                        if (seg.isImageGenerationSegment()) {
                            GeneratedImageThumbnail(
                                segment = seg,
                                messageId = message.id,
                                detailIndex = currentDetailIndex,
                                isStreaming = isStreaming,
                                segmentAppearanceRegistry = segmentAppearanceRegistry,
                                onMediaClick = onMediaClick,
                            )
                        }
                        previousVisibleWasAnswer = false
                        index++
                    }
                }
                else -> {
                    index++
                }
            }
        }
    }
}

@Composable
internal fun TimelineInfoSegmentCard(
    seg: MessageSegment,
    detailSegments: List<MessageSegment>,
    detailIndex: Int,
    isStreamingContent: Boolean,
    animateAppearance: Boolean,
    topPaddingExtra: Dp = 0.dp,
    groupPosition: SegmentGroupPosition = SegmentGroupPosition.SINGLE,
    endsAtGeneratedImageBoundary: Boolean = false,
    neutralPalette: Boolean = false,
    extendIntoMessageInsets: Boolean = false,
    cardAnimationKey: String,
    segmentAppearanceRegistry: SegmentAppearanceRegistry,
    onClick: () -> Unit
) {
    val animateCardAppearance = rememberSegmentAppearance(
        registry = segmentAppearanceRegistry,
        animationKey = cardAnimationKey,
        isStreaming = animateAppearance,
    )
    val groupShape = rememberAnimatedSegmentGroupShape(groupPosition)
    val cardColor = if (neutralPalette) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
    } else {
        MaterialTheme.colorScheme.surface
    }
    val iconTint = if (neutralPalette) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardAppearanceModifier = generationLifecycleAppearanceModifier(
            animationKey = cardAnimationKey,
            animate = animateCardAppearance,
            durationMillis = SEGMENT_ENTER_DURATION_MS,
            initialScale = SEGMENT_ENTER_INITIAL_SCALE,
            forceOpaque = seg.type == "tool",
        )
        val requestedCardWidth = if (extendIntoMessageInsets) {
            maxWidth + (AUXILIARY_CARD_START_EXTENSION_DP * 2).dp
        } else {
            maxWidth
        }
        val requestedCardOffset =
            if (extendIntoMessageInsets) (-AUXILIARY_CARD_START_EXTENSION_DP).dp else 0.dp
        StartAnchoredHorizontalOverflowHost {
            Surface(
                tonalElevation = if (neutralPalette) 1.dp else 2.dp,
                color = cardColor,
                shape = groupShape,
                modifier = Modifier
                    .offset(x = requestedCardOffset)
                    .width(requestedCardWidth)
            .padding(
                top = segmentGroupTopPadding(groupPosition, topPaddingExtra),
                bottom = if (endsAtGeneratedImageBoundary) {
                    GENERATED_IMAGE_BOUNDARY_GAP_DP.dp
                } else segmentGroupBottomPadding(groupPosition),
            )
            .then(cardAppearanceModifier)
            .clip(groupShape)
            .clickable {
                onClick()
            }
            .noOpBringIntoView()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp)
        ) {
            val isTool = seg.type == "tool"
            val isTranscription = seg.type == "transcription"
            if (isTool) {
                Icon(Icons.Default.Build, null, modifier = Modifier.size(16.dp), tint = iconTint)
            } else if (isTranscription) {
                Icon(Icons.Filled.Image, null, modifier = Modifier.size(16.dp), tint = iconTint)
            } else {
                Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(16.dp), tint = iconTint)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when (seg.type) {
                        "tool" -> toolDisplayName(seg)
                        "transcription" -> transcriptionLabel(detailSegments, detailIndex)
                        else -> stringResource(R.string.tool_thinking)
                    },
                    style = ChatType.meta,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (seg.type == "thought" && seg.content.isNotBlank()) {
                    StreamingThoughtPreviewText(
                        content = seg.content,
                        streaming = isStreamingContent,
                    )
                } else {
                    val summary = when (seg.type) {
                        "tool" -> toolSummary(seg)
                        "transcription" -> seg.content.takeIf { it.isNotBlank() }
                            ?: "Image transcription is empty."
                        else -> ""
                    }
                    if (summary.isNotBlank()) {
                        if (isTool) {
                            ToolSummaryText(
                                presentation = ToolPresentationResolver.resolve(seg),
                                streaming = isStreamingContent,
                            )
                        } else {
                            StreamingMutedText(
                                text = summary,
                                streaming = isStreamingContent,
                            )
                        }
                    }
                }
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
            }
        }
        }
    }
}
