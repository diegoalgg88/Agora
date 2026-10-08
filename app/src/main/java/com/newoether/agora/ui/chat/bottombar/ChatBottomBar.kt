package com.newoether.agora.ui.chat.bottombar

import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.content.MediaType
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.background
import com.newoether.agora.model.apiModelName
import com.newoether.agora.model.ContextBudget
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.material3.Icon
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.newoether.agora.R
import com.newoether.agora.model.AttachmentImportState
import com.newoether.agora.model.SelectedAttachment
import com.newoether.agora.ui.common.LocalAgoraHaptics
import com.newoether.agora.ui.common.openAiServiceTierShortLabel
import com.newoether.agora.ui.common.thinkingControlShortLabel
import com.newoether.agora.ui.motion.LocalAgoraMotionPolicy
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.ui.theme.ChatType
import com.newoether.agora.util.noOpBringIntoView
import com.newoether.agora.viewmodel.ConversationComposerController
import com.newoether.agora.viewmodel.ConversationComposerSnapshot
import com.newoether.agora.viewmodel.ConversationComposerSubmissionController
import com.newoether.agora.viewmodel.QueuedSend
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.newoether.agora.data.CustomProviderConfig
import com.newoether.agora.data.providerDisplayName
import com.newoether.agora.data.modelDisplayName
internal val CHAT_BOTTOM_BAR_OUTER_RADIUS = 28.dp
internal val CHAT_BOTTOM_BAR_OUTER_SHAPE = RoundedCornerShape(CHAT_BOTTOM_BAR_OUTER_RADIUS)
internal val CHAT_DROPDOWN_MENU_SHAPE = RoundedCornerShape(16.dp)
internal fun contextUsageExceedsCompactThreshold(
    estimatedTokens: Int, tokenBudget: Int, thresholdPercent: Int,
): Boolean {
    val normalizedBudget = tokenBudget.coerceAtLeast(1)
    val normalizedPercent = thresholdPercent.coerceIn(50, 100)
    val threshold = ((normalizedBudget.toLong() * normalizedPercent + 99L) / 100L)
        .coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    return tokenBudget > 0 && estimatedTokens > threshold
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
internal fun ChatBottomBar(
    submissionController: ConversationComposerSubmissionController,
    composerOwnerId: String,
    composerController: ConversationComposerController,
    composerSnapshot: ConversationComposerSnapshot,
    onStopGeneration: () -> Unit = {},
    isLoading: Boolean,
    isCompacting: Boolean = false,
    isSwitching: Boolean = false,
    enabledModels: Set<String>,
    selectedModel: String,
    modelAliases: Map<String, String> = emptyMap(),
    modelProviderNames: Map<String, Boolean> = emptyMap(),
    customProviders: List<CustomProviderConfig> = emptyList(),
    codeExecutionEnabled: Boolean = false,
    googleSearchEnabled: Boolean = false,
    openAiWebSearchAvailable: Boolean = false,
    openAiWebSearchEnabled: Boolean = false,
    thinkingEnabled: Boolean = true,
    thinkingLevel: String = "medium",
    thinkingBudgetEnabled: Boolean = false,
    thinkingBudgetTokens: Int = 4096,
    openAiServiceTierAvailable: Boolean = false,
    openAiServiceTierEnabled: Boolean = false,
    openAiServiceTier: String = "auto",
    webSearchEnabled: Boolean = false,
    shellEnabled: Boolean = false,
    showLowContextMode: Boolean = false,
    lowContextModeEnabled: Boolean = false,
    onCodeExecutionToggle: (Boolean) -> Unit = {},
    onGoogleSearchToggle: (Boolean) -> Unit = {},
    onOpenAiWebSearchToggle: (Boolean) -> Unit = {},
    onThinkingToggle: (Boolean) -> Unit = {},
    onThinkingLevelChange: (String) -> Unit = {},
    onThinkingBudgetEnabledChange: (Boolean) -> Unit = {},
    onThinkingBudgetTokensChange: (Int) -> Unit = {},
    onOpenAiServiceTierToggle: (Boolean) -> Unit = {},
    onOpenAiServiceTierChange: (String) -> Unit = {},
    onWebSearchToggle: (Boolean) -> Unit = {},
    onShellToggle: (Boolean) -> Unit = {},
    onLowContextModeToggle: (Boolean) -> Unit = {},
    onModelSelect: (String) -> Unit,
    onAllMediaClick: ((urls: List<String>, index: Int) -> Unit)? = null,
    onFileContentClick: ((fileName: String, content: String) -> Unit)? = null,
    onPdfPagesClick: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    onPdfPreviewSelect: ((pages: List<String>, startIndex: Int) -> Unit)? = null,
    pdfViewerSelection: Set<Int> = emptySet(),
    onTogglePdfSelection: ((Int) -> Unit)? = null,
    onInitPdfSelection: ((Set<Int>) -> Unit)? = null,
    fullScreenViewerUrls: List<String>? = null,
    modifier: Modifier = Modifier,
    textFieldState: TextFieldState = rememberSaveable(saver = TextFieldState.Saver) { TextFieldState() },
    composerState: ChatComposerState = rememberChatComposerState(),
    focusRequester: FocusRequester = FocusRequester(),
    onInputFocusChanged: (Boolean) -> Unit = {},
    isExpanded: Boolean = false,
    isExpandAnimating: Boolean = false,
    onCollapse: () -> Unit = {},
    onExpand: () -> Unit = {},
    showWebSearch: Boolean = true,
    showShell: Boolean = true,
    onAdvancedClick: () -> Unit = {},
    compactDefaultModel: String? = null,
    compactDefaultPrompt: String = "",
    compactDefaultRetainCount: Int = 6,
    contextEstimatedTokens: Int = 0,
    contextTokenBudget: Int = ContextBudget.DEFAULT_TOKENS,
    contextCompactThresholdPercent: Int = 90,
    canCompact: Boolean = false,
    onCompactClick: () -> Unit = {},
    queuedSends: List<QueuedSend> = emptyList(),
    onRemoveQueuedSend: (String) -> Unit = {},
    isStopping: Boolean = false,
) {
    val motionPolicy = LocalAgoraMotionPolicy.current
    val allowSpatialTransitions = motionPolicy.allowSpatialTransitions
    val scrollState = rememberScrollState()
    BackHandler(enabled = isExpanded) { onCollapse() }
    val isModelValid = selectedModel.isNotBlank() && enabledModels.contains(selectedModel)
    val submissionState = remember(submissionController, composerOwnerId) {
        submissionController.observeState(composerOwnerId)
    }
    DisposableEffect(submissionController, composerOwnerId) {
        onDispose { submissionController.releaseState(composerOwnerId) }
    }
    val submission by submissionState.collectAsState()
    val composer = composerState
    val context = LocalContext.current
    val haptics = LocalAgoraHaptics.current
    val activityLaunchScope = rememberCoroutineScope()
    suspend fun withOwner(ownerId: String, action: suspend () -> Unit): Boolean {
        if (submissionController.snapshot(ownerId).isFrozen) return false
        composerController.load(ownerId)
        return try {
            if (submissionController.snapshot(ownerId).isFrozen) false else {
                action()
                true
            }
        } finally {
            withContext(NonCancellable) { composerController.release(ownerId) }
        }
    }
    fun importUris(ownerId: String, uris: List<Uri>, forcedType: String? = null, emitSuccessHaptic: Boolean = true) {
        if (uris.isEmpty() || submissionController.snapshot(ownerId).isFrozen) return
        activityLaunchScope.launch {
            val (attachments, rejected) = inspectAttachmentIngress(
                context,
                uris,
                forcedType,
                composer.acceptsLocalSandboxAttachments(),
            )
            if (submissionController.snapshot(ownerId).isFrozen) return@launch
            composer.reportUnsupportedFiles(rejected)
            if (attachments.isEmpty()) return@launch
            var imported = false
            if (withOwner(ownerId) {
                for (attachment in attachments) {
                    if (submissionController.snapshot(ownerId).isFrozen) break
                    imported = composerController.importAttachment(ownerId, attachment) || imported
                }
            } && imported && emitSuccessHaptic) haptics.selection()
        }
    }
    val clipboardImageReceiver = remember(context, composerOwnerId, composer) {
        object : ReceiveContentListener {
            override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
                val imageUris = mutableListOf<Uri>()
                val advertisesImages = transferableContent.hasMediaType(MediaType.Image)
                val remaining = transferableContent.consume { item ->
                    val uri = item.uri ?: return@consume false
                    val resolvedMime = context.contentResolver.getType(uri)
                    val isImage = resolvedMime?.startsWith("image/") == true ||
                        (resolvedMime == null && advertisesImages)
                    if (isImage) imageUris += uri
                    isImage
                }
                importUris(composerOwnerId, imageUris, "image", emitSuccessHaptic = false)
                return remaining
            }
        }
    }
    var showThinkingSheet by rememberSaveable { mutableStateOf(false) }
    var showOpenAiServiceTierSheet by rememberSaveable { mutableStateOf(false) }
    val composerOcclusionColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
    val composerOcclusionShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
    LaunchedEffect(fullScreenViewerUrls) {
        if (
            fullScreenViewerUrls == null &&
            composer.pdfDialogHiddenForPreview &&
            composer.pendingPdfAttachmentId != null
        ) {
            composer.showPdfPageDialog = true
            composer.pdfDialogHiddenForPreview = false
        }
    }
    LaunchedEffect(openAiServiceTierAvailable) {
        if (!openAiServiceTierAvailable) showOpenAiServiceTierSheet = false
    }
    LaunchedEffect(composerOwnerId, composerSnapshot.attachments, submission.isFrozen) {
        if (submission.isFrozen) {
            composer.resetPendingPdfState()
            composer.resetPendingVideoState()
            return@LaunchedEffect
        }
        if (composer.pendingPdfOwnerId != null && composer.pendingPdfOwnerId != composerOwnerId) {
            composer.resetPendingPdfState()
        }
        if (
            composer.pendingPdfOwnerId == composerOwnerId &&
            composer.pendingPdfAttachmentId != null &&
            composerSnapshot.attachments.none {
                it.localId == composer.pendingPdfAttachmentId &&
                    it.type == "pdf" && it.importState == AttachmentImportState.PROCESSING &&
                    it.selectedPages == null
            }
        ) composer.resetPendingPdfState()
        if (composer.pendingPdfAttachmentId == null) {
            composerSnapshot.attachments.firstOrNull {
                it.type == "pdf" && it.importState == AttachmentImportState.PROCESSING &&
                    it.selectedPages == null && (it.pageCount ?: 0) > 0
            }?.let { attachment ->
                composer.pendingPdfOwnerId = composerOwnerId
                composer.pendingPdfAttachmentId = attachment.localId
                composer.showPdfPageDialog = true
                onInitPdfSelection?.invoke((0 until minOf(attachment.pageCount ?: 0, 5)).toSet())
            }
        }
        if (composer.pendingVideoOwnerId != null && composer.pendingVideoOwnerId != composerOwnerId) {
            composer.resetPendingVideoState()
        }
        if (
            composer.pendingVideoOwnerId == composerOwnerId &&
            composer.pendingVideoAttachmentId != null &&
            composerSnapshot.attachments.none {
                it.localId == composer.pendingVideoAttachmentId &&
                    it.type == "video" && it.importState == AttachmentImportState.PROCESSING &&
                    it.frameCount == null
            }
        ) composer.resetPendingVideoState()
        if (composer.pendingVideoAttachmentId == null && composer.pendingPdfAttachmentId == null) {
            composerSnapshot.attachments.firstOrNull {
                it.type == "video" && it.importState == AttachmentImportState.PROCESSING &&
                    it.frameCount == null && it.localPath != null
            }?.let { attachment ->
                composer.pendingVideoOwnerId = composerOwnerId
                composer.pendingVideoAttachmentId = attachment.localId
                composer.showVideoSliceDialog = true
            }
        }
    }

    var pendingPhotoOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingVideoPickerOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingFileOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val photoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> pendingPhotoOwnerId?.let { importUris(it, uris, "image") }; pendingPhotoOwnerId = null }
    val videoLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris -> pendingVideoPickerOwnerId?.let { importUris(it, uris, "video") }; pendingVideoPickerOwnerId = null }
    val fileLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()
    ) { uris -> pendingFileOwnerId?.let { importUris(it, uris) }; pendingFileOwnerId = null }
    var pendingCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionPath by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingCameraPermissionOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var internalCameraOwnerId by rememberSaveable { mutableStateOf<String?>(null) }
    val cameraLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { captured ->
        val path = pendingCameraPath
        val ownerId = pendingCameraOwnerId
        pendingCameraPath = null
        pendingCameraOwnerId = null
        if (path != null && ownerId != null) composer.completeCameraCapture(
            ownerId, composerController, submissionController, path, captured,
        )
    }
    val cameraPermissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val path = pendingCameraPermissionPath
        val ownerId = pendingCameraPermissionOwnerId
        pendingCameraPermissionPath = null
        pendingCameraPermissionOwnerId = null
        if (granted && path != null && ownerId != null && !submissionController.snapshot(ownerId).isFrozen) {
            internalCameraPath = path
            internalCameraOwnerId = ownerId
        } else if (path != null && ownerId != null) {
            composer.completeCameraCapture(
                ownerId, composerController, submissionController, path, captured = false,
            )
            if (!granted) composer.reportCameraPreparationFailure()
        }
    }
    fun launchInternalCamera(ownerId: String, privatePath: String) {
        if (
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.CAMERA,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            internalCameraPath = privatePath
            internalCameraOwnerId = ownerId
        } else {
            pendingCameraPermissionPath = privatePath
            pendingCameraPermissionOwnerId = ownerId
            cameraPermissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }
    Box(modifier = modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier).padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 12.dp)) {
        Column(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)) {
            AnimatedVisibility(
                visible = isExpanded,
                enter = EnterTransition.None,
                exit = if (allowSpatialTransitions) {
                    shrinkVertically(tween(250)) + fadeOut(tween(250))
                } else {
                    fadeOut(tween(250))
                },
            ) {
                Spacer(modifier = Modifier.height(44.dp))
            }
            ComposerStatusColumn(
                queuedSends = queuedSends,
                onRemoveQueuedSend = onRemoveQueuedSend,
                modifier = Modifier.zIndex(0f),
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.weight(1f) else Modifier)
                    .then(
                        if (allowSpatialTransitions) {
                            Modifier.animateContentSize(
                                animationSpec = tween(durationMillis = 400),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .clip(composerOcclusionShape)
                    .background(composerOcclusionColor)
                    .zIndex(1f),
            ) {
        if (composerSnapshot.attachments.isNotEmpty()) {
            AttachmentPreviewRow(
                attachments = composerSnapshot.attachments,
                editable = !submission.isFrozen,
                onRemove = { id -> activityLaunchScope.launch { withOwner(composerOwnerId) { composerController.remove(composerOwnerId, id) } } },
                onRetry = { id -> activityLaunchScope.launch { withOwner(composerOwnerId) { composerController.retry(composerOwnerId, id) } } },
                onAllMediaClick = onAllMediaClick,
                onFileContentClick = onFileContentClick,
                onPdfPagesClick = onPdfPagesClick,
            )
        }

        Box(modifier = Modifier.fillMaxWidth().then(if (isExpanded) Modifier.weight(1f) else Modifier).noOpBringIntoView()) {
            TextField(
                state = textFieldState,
                scrollState = scrollState,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (isExpanded) Modifier.fillMaxHeight() else Modifier)
                    .contentReceiver(clipboardImageReceiver)
                    .focusRequester(focusRequester)
                    .onFocusChanged { focusState ->
                        onInputFocusChanged(focusState.isFocused)
                    }
                    .verticalScrollbar(scrollState, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                placeholder = {
                    Text(
                        stringResource(R.string.ask_agora),
                        style = ChatType.input,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                },
                lineLimits = TextFieldLineLimits.MultiLine(1, if (isExpanded) Int.MAX_VALUE else 6),
                contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 16.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    cursorColor = MaterialTheme.colorScheme.primary
                ),
                textStyle = ChatType.input.copy(color = MaterialTheme.colorScheme.onSurface)
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = !isExpanded,
                enter = fadeIn(tween(250)),
                exit = ExitTransition.None,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
                IconButton(onClick = { if (!isExpandAnimating) onExpand() }, modifier = Modifier.padding(end = 4.dp, top = 4.dp).size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.expand_all_24px), contentDescription = stringResource(R.string.expand), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
            }
        }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp).background(MaterialTheme.colorScheme.surfaceColorAtElevation(10.dp), RoundedCornerShape(100)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                AttachmentAddMenu(
                    enabled = !submission.isFrozen,
                    onCamera = {
                        activityLaunchScope.launch {
                            val target = composer.createCameraCaptureTarget()
                            if (target == null) {
                                composer.reportCameraPreparationFailure()
                                return@launch
                            }
                            if (canLaunchSystemImageCapture(context)) {
                                pendingCameraPath = target.privatePath
                                pendingCameraOwnerId = composerOwnerId
                                runCatching { cameraLauncher.launch(target.uri) }
                                    .onFailure {
                                        pendingCameraPath = null
                                        pendingCameraOwnerId = null
                                        launchInternalCamera(composerOwnerId, target.privatePath)
                                    }
                            } else {
                                launchInternalCamera(composerOwnerId, target.privatePath)
                            }
                        }
                    },
                    onPhotos = {
                        pendingPhotoOwnerId = composerOwnerId
                        photoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts
                                    .PickVisualMedia.ImageOnly,
                            ),
                        )
                    },
                    onVideos = {
                        pendingVideoPickerOwnerId = composerOwnerId
                        videoLauncher.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                androidx.activity.result.contract.ActivityResultContracts
                                    .PickVisualMedia.VideoOnly,
                            ),
                        )
                    },
                    onFiles = { pendingFileOwnerId = composerOwnerId; fileLauncher.launch("*/*") },
                )
                var activeMenu by remember { mutableStateOf<String?>(null) }
                var lastModelDismissTime by remember { mutableLongStateOf(0L) }
                var lastContextDismissTime by remember { mutableLongStateOf(0L) }
                var lastToolsDismissTime by remember { mutableLongStateOf(0L) }

                val selectedProvider = providerDisplayName(
                    com.newoether.agora.model.ModelId.parse(selectedModel).providerName,
                    customProviders,
                )
                val capabilityControlsEnabled = !lowContextModeEnabled
                val displayText = when {
                    isModelValid -> modelDisplayName(selectedModel, modelAliases, customProviders, modelProviderNames[selectedModel] != false)
                    enabledModels.isNotEmpty() -> stringResource(R.string.select_model)
                    else -> stringResource(R.string.no_model_selected)
                }
                
                ExposedDropdownMenuBox(
                    expanded = activeMenu == "model",
                    onExpandedChange = { }
                ) {
                    TextButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (activeMenu == "model") {
                                activeMenu = null
                            } else if (now - lastModelDismissTime > 200) {
                                activeMenu = "model"
                            }
                        },
                        modifier = Modifier.height(38.dp).widthIn(max = 160.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true),
                        contentPadding = PaddingValues(8.dp)
                    ) {
                        Text(
                            displayText,
                            style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = if (isModelValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }
                    
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "model", 
                        onDismissRequest = { 
                            if (activeMenu == "model") {
                                activeMenu = null
                                lastModelDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchAnchorWidth = false,
                        shape = CHAT_DROPDOWN_MENU_SHAPE,
                    ) {
                        if (enabledModels.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.models_no_models)) },
                                onClick = {
                                    activeMenu = null
                                    lastModelDismissTime = 0L // Reset to allow immediate re-open
                                },
                                enabled = false
                            )
                        } else {
                            // Grouped by provider, then alphabetical. enabledModels is a Set whose
                            // iteration order is insertion order (i.e. whenever each model was
                            // enabled), which scrambles providers together in the picker.
                            val sortedModels = remember(enabledModels, customProviders) {
                                enabledModels.sortedWith(
                                    compareBy(
                                        {
                                            providerDisplayName(
                                                com.newoether.agora.model.ModelId.parse(it).providerName,
                                                customProviders,
                                            ).lowercase()
                                        },
                                        { com.newoether.agora.model.ModelId.parse(it).apiModelName.lowercase() },
                                    )
                                )
                            }
                            sortedModels.forEach { model ->
                                DropdownMenuItem(
                                    text = {
                                        Text(modelDisplayName(model, modelAliases, customProviders, modelProviderNames[model] != false))
                                    },
                                    onClick = {
                                        haptics.selection()
                                        onModelSelect(model)
                                        activeMenu = null
                                        lastModelDismissTime = 0L
                                    }
                                )
                            }
                        }
                    }
                }
                
                val contextProgressColor = if (
                    contextUsageExceedsCompactThreshold(
                        contextEstimatedTokens,
                        contextTokenBudget,
                        contextCompactThresholdPercent,
                    )
                ) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
                val contextProgressTarget = if (contextTokenBudget <= 0) {
                    0f
                } else {
                    (contextEstimatedTokens.toFloat() / contextTokenBudget).coerceIn(0f, 1f)
                }
                val contextProgress by animateFloatAsState(
                    targetValue = contextProgressTarget,
                    animationSpec = if (motionPolicy.allowContinuousMotion) {
                        tween(durationMillis = 400)
                    } else {
                        snap()
                    },
                    label = "contextProgress",
                )
                ExposedDropdownMenuBox(
                    expanded = activeMenu == "context",
                    onExpandedChange = { },
                ) {
                    IconButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            if (activeMenu == "context") {
                                activeMenu = null
                            } else if (now - lastContextDismissTime > 200) {
                                activeMenu = "context"
                            }
                        },
                        modifier = Modifier
                            .size(32.dp)
                            .menuAnchor(
                                type = ExposedDropdownMenuAnchorType.PrimaryNotEditable,
                                enabled = true,
                            ),
                    ) {
                        CircularProgressIndicator(
                            progress = { contextProgress },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = contextProgressColor,
                        )
                    }
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "context",
                        onDismissRequest = {
                            if (activeMenu == "context") {
                                activeMenu = null
                                lastContextDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchAnchorWidth = false,
                        shape = CHAT_DROPDOWN_MENU_SHAPE,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = stringResource(R.string.context_title),
                                style = MaterialTheme.typography.titleSmall,
                            )
                            CircularProgressIndicator(
                                progress = { contextProgress },
                                modifier = Modifier.size(36.dp).align(Alignment.CenterHorizontally),
                                strokeWidth = 4.dp,
                                color = contextProgressColor,
                            )
                            Text(
                                text = stringResource(
                                    R.string.context_usage_messages,
                                    ContextBudget.compactLabel(contextEstimatedTokens),
                                    ContextBudget.compactLabel(contextTokenBudget),
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }

                ExposedDropdownMenuBox(
                    expanded = activeMenu == "tools",
                    onExpandedChange = { }
                ) {
                    IconButton(
                        onClick = { 
                            val now = System.currentTimeMillis()
                            if (activeMenu == "tools") {
                                activeMenu = null
                            } else if (now - lastToolsDismissTime > 200) {
                                activeMenu = "tools"
                            }
                        }, 
                        modifier = Modifier.size(32.dp).menuAnchor(type = ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                    ) {
                        Icon(Icons.Default.MoreVert, stringResource(R.string.tools), modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    
                    ExposedDropdownMenu(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                        expanded = activeMenu == "tools",
                        onDismissRequest = {
                            if (activeMenu == "tools") {
                                activeMenu = null
                                lastToolsDismissTime = System.currentTimeMillis()
                            }
                        },
                        matchAnchorWidth = false,
                        shape = CHAT_DROPDOWN_MENU_SHAPE,
                    ) {
                        if (showLowContextMode) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Memory,
                                            contentDescription = null,
                                            modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.low_context_mode))
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = lowContextModeEnabled,
                                        onCheckedChange = onLowContextModeToggle,
                                        modifier = Modifier.scale(0.7f),
                                    )
                                },
                                onClick = { onLowContextModeToggle(!lowContextModeEnabled) },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(androidx.compose.ui.res.painterResource(id = com.newoether.agora.R.drawable.neurology_24), null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column {
                                        Text(stringResource(R.string.thinking))
                                        Text(
                                            text = thinkingControlShortLabel(
                                                thinkingEnabled,
                                                thinkingLevel,
                                                thinkingBudgetEnabled,
                                                thinkingBudgetTokens
                                            ),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            },
                            trailingIcon = {
                                Switch(
                                    checked = thinkingEnabled,
                                    onCheckedChange = { onThinkingToggle(it) },
                                    modifier = Modifier.scale(0.7f)
                                )
                            },
                            onClick = {
                                activeMenu = null
                                showThinkingSheet = true
                            }
                        )
                        val isGemini = selectedProvider.equals("google", ignoreCase = true) && isModelValid
                        if (isGemini) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.code_execution))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = codeExecutionEnabled,
                                        onCheckedChange = { onCodeExecutionToggle(it) },
                                        enabled = capabilityControlsEnabled,
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                enabled = capabilityControlsEnabled,
                                onClick = { onCodeExecutionToggle(!codeExecutionEnabled) }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Image(
                                            painter = androidx.compose.ui.res.painterResource(R.drawable.provider_google),
                                            contentDescription = null,
                                            colorFilter = ColorFilter.tint(LocalContentColor.current),
                                            modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.google_search))
                                        Spacer(modifier = Modifier.width(10.dp))
                                        ProviderBadge("Gemini")
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = googleSearchEnabled,
                                        onCheckedChange = { onGoogleSearchToggle(it) },
                                        enabled = capabilityControlsEnabled,
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                enabled = capabilityControlsEnabled,
                                onClick = { onGoogleSearchToggle(!googleSearchEnabled) }
                            )
                        }
                        if (openAiServiceTierAvailable && isModelValid) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.Default.Speed,
                                            contentDescription = null,
                                            modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Column {
                                            Text(stringResource(R.string.openai_service_tier_title))
                                            Text(
                                                text = openAiServiceTierShortLabel(
                                                    openAiServiceTierEnabled,
                                                    openAiServiceTier,
                                                ),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = openAiServiceTierEnabled,
                                        onCheckedChange = onOpenAiServiceTierToggle,
                                        enabled = capabilityControlsEnabled,
                                        modifier = Modifier.scale(0.7f),
                                    )
                                },
                                enabled = capabilityControlsEnabled,
                                onClick = {
                                    activeMenu = null
                                    showOpenAiServiceTierSheet = true
                                },
                            )
                        }
                        if (openAiWebSearchAvailable && isModelValid) {
                            NativeSearchMenuItem(
                                checked = openAiWebSearchEnabled,
                                provider = "OpenAI",
                                enabled = capabilityControlsEnabled,
                                onCheckedChange = onOpenAiWebSearchToggle,
                            )
                        }
                        if (showWebSearch) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Language, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.web_search))
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = webSearchEnabled,
                                        onCheckedChange = { onWebSearchToggle(it) },
                                        enabled = capabilityControlsEnabled,
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                enabled = capabilityControlsEnabled,
                                onClick = { onWebSearchToggle(!webSearchEnabled) }
                            )
                        }
                        if (showShell) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Terminal, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(stringResource(R.string.shell_title))
                                    }
                                },
                                trailingIcon = {
                                    Switch(
                                        checked = shellEnabled,
                                        onCheckedChange = { onShellToggle(it) },
                                        enabled = capabilityControlsEnabled,
                                        modifier = Modifier.scale(0.7f)
                                    )
                                },
                                enabled = capabilityControlsEnabled,
                                onClick = { onShellToggle(!shellEnabled) }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Compress, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.context_compact))
                                }
                            },
                            enabled = canCompact && !isCompacting,
                            onClick = { activeMenu = null; onCompactClick() },
                        )
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Tune, null, modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(stringResource(R.string.advanced_settings))
                                }
                            },
                            // Unlike the toggle rows, this opens a dialog — collapse the menu first.
                            onClick = { activeMenu = null; onAdvancedClick() }
                        )
                    }
                }
            }
            ComposerSendButton(
                textFieldState = textFieldState,
                ownerId = composerOwnerId,
                snapshot = composerSnapshot,
                submissionController = submissionController,
                submission = submission,
                isLoading = isLoading,
                isSwitching = isSwitching,
                isStopping = isStopping,
                isModelValid = isModelValid,
                onStopGeneration = onStopGeneration,
                onCollapse = onCollapse,
            )
        }
        }
        AnimatedVisibility(
            visible = isExpanded,
            enter = fadeIn(tween(250)),
            exit = fadeOut(tween(250)),
            modifier = Modifier.align(Alignment.TopEnd).padding(end = 4.dp, top = 4.dp)
        ) {
            val elevatedSurface = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
            IconButton(onClick = { if (!isExpandAnimating) onCollapse() }, modifier = Modifier.size(40.dp).background(Brush.radialGradient(listOf(elevatedSurface, elevatedSurface.copy(alpha = 0.5f), Color.Transparent)), CircleShape)) { Icon(painter = androidx.compose.ui.res.painterResource(id = R.drawable.collapse_all_24px), contentDescription = stringResource(R.string.collapse), modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.9f)) }
        }
    }

    ChatBottomBarOverlayHost(
        showThinkingSheet = showThinkingSheet,
        onDismissThinkingSheet = { showThinkingSheet = false },
        thinkingEnabled = thinkingEnabled,
        thinkingLevel = thinkingLevel,
        thinkingBudgetEnabled = thinkingBudgetEnabled,
        thinkingBudgetTokens = thinkingBudgetTokens,
        onThinkingToggle = onThinkingToggle,
        onThinkingLevelChange = onThinkingLevelChange,
        onThinkingBudgetEnabledChange = onThinkingBudgetEnabledChange,
        onThinkingBudgetTokensChange = onThinkingBudgetTokensChange,
        selectedModel = selectedModel,
        customProviders = customProviders,
        showOpenAiServiceTierSheet = showOpenAiServiceTierSheet,
        openAiServiceTierAvailable = openAiServiceTierAvailable,
        onDismissOpenAiServiceTierSheet = { showOpenAiServiceTierSheet = false },
        openAiServiceTierEnabled = openAiServiceTierEnabled,
        openAiServiceTier = openAiServiceTier,
        onOpenAiServiceTierToggle = onOpenAiServiceTierToggle,
        onOpenAiServiceTierChange = onOpenAiServiceTierChange,
        internalCameraPath = internalCameraPath,
        internalCameraOwnerId = internalCameraOwnerId,
        onInternalCameraCleared = { internalCameraPath = null; internalCameraOwnerId = null },
        composerOwnerId = composerOwnerId,
        composerController = composerController,
        submissionController = submissionController,
        composerSnapshot = composerSnapshot,
        composer = composer,
        pdfViewerSelection = pdfViewerSelection,
        onTogglePdfSelection = onTogglePdfSelection,
        onPdfPreviewSelect = onPdfPreviewSelect,
    )

}
