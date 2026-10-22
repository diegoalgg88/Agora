package com.newoether.agora.ui

import com.newoether.agora.util.bottomOverlayFadeStops
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApplicationUiSourceContractTest {
    @Test
    fun `onboarding primary action keeps fixed geometry without custom press motion`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        assertFalse(source.contains("val continueInteractionSource"))
        assertFalse(source.contains("collectIsPressedAsState()"))
        assertFalse(source.contains("val isContinuePressed"))
        assertFalse(source.contains("val horizontalInset by animateDpAsState"))
        assertFalse(source.contains("val actionHeight by animateDpAsState"))
        assertFalse(source.contains("val contentScale by animateFloatAsState"))
        assertFalse(source.contains("interactionSource = continueInteractionSource"))
        assertTrue(source.contains(".padding(horizontal = 32.dp)"))
        assertTrue(source.contains(".height(48.dp)"))
        assertFalse(source.contains(".scale(contentScale)"))
        assertTrue(source.contains("pagerState.animateScrollToPage("))
        assertTrue(source.contains("if (last) { exiting = true }"))
    }

    @Test
    fun `onboarding dot indicator keeps constant row height without spring`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/onboarding/WelcomeScreen.kt")

        // Fixed outer slot: selection changes never shift the whole indicator vertically.
        assertTrue(source.contains(
            "Box(Modifier.padding(horizontal = 4.dp).size(10.dp), contentAlignment = Alignment.Center)"
        ))
        assertTrue(source.contains("animateDpAsState(if (sel) 10.dp else 8.dp, tween(120))"))
        assertTrue(source.contains(
            "animateColorAsState(if (sel) MaterialTheme.colorScheme.primary else " +
                "MaterialTheme.colorScheme.outlineVariant, tween(120))"
        ))
        assertFalse(source.contains("spring("))
    }

    @Test
    fun `Skills settings mirrors the saved Memory file presentation`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt",
        )

        assertTrue(source.contains("var skillsLoaded"))
        assertTrue(source.contains("var skillOperationInFlight"))
        assertTrue(source.contains("fun loadSkills("))
        assertTrue(source.contains("CircularProgressIndicator("))
        assertTrue(source.contains("R.string.memory_access_title"))
        assertTrue(source.contains("R.string.skills_create_hint"))
        assertTrue(source.contains("Icons.Default.MoreVert"))
        assertTrue(source.contains("DropdownMenu("))
        assertTrue(source.contains("SettingsAddItem("))
        assertFalse(source.contains("import androidx.compose.material3.Button\n"))
        assertTrue(source.contains("containerColor = MaterialTheme.colorScheme.surfaceContainer"))
        assertTrue(source.contains("fontWeight = FontWeight.Bold"))
        assertTrue(source.contains("shape = RoundedCornerShape(16.dp)"))
        assertTrue(source.contains("FontFamily.Monospace"))
        assertTrue(source.contains("Modifier.clearFocusOnTap()"))
        assertTrue(source.contains("Spacer(modifier = Modifier.height(80.dp))"))
        assertTrue(source.contains("file.name.removeSuffix(\".md\")"))
    }

    @Test
    fun `singular transcription ellipsis exists in every locale`() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue(
                "$directory transcription_ellipsis_single",
                strings.contains("name=\"transcription_ellipsis_single\""),
            )
        }
    }

    @Test
    fun `Skills entry uses Extension while Markdown import uses Memory Description icon`() {
        val settings = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt")
        val page = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsSkillsPage.kt")
        val strings = sourceFile("app/src/main/res/values/strings.xml")

        assertTrue(settings.contains("R.string.settings_skills, R.string.settings_skills_desc, Icons.Default.Extension"))
        assertFalse(page.contains("AutoAwesome"))
        assertTrue(page.contains("Icons.Default.Extension"))
        assertTrue(page.contains("Icons.Default.Description"))
        mapOf(
            "skills_access" to "Access Saved Skills",
            "skills_saved_title" to "Saved Skills",
            "skills_add" to "Add Skill",
            "skills_delete_title" to "Delete Skill?",
        ).forEach { (key, value) ->
            assertTrue(strings.contains("""<string name="$key">$value</string>"""))
        }
    }

    @Test
    fun `Skills UI strings keep locale and delete placeholder parity`() {
        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )

        directories.forEach { directory ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertTrue("$directory skills_create_hint", strings.contains("name=\"skills_create_hint\""))
            assertTrue("$directory skills_create", strings.contains("name=\"skills_create\""))
            assertTrue("$directory skills_edit", strings.contains("name=\"skills_edit\""))
            assertTrue(
                "$directory skills_delete_message placeholder",
                stringValue(strings, "skills_delete_message").contains("%1\$s"),
            )
        }
    }

    @Test
    fun `PDF page bitmaps are initialized opaque white before both framework render paths`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/util/PdfPageRenderer.kt",
        )

        assertTrue(source.contains("private const val MAX_PAGES = 5"))
        assertTrue(source.contains("private const val TARGET_LONG_EDGE = 1536"))
        assertTrue(source.contains("private fun createPageBitmap(width: Int, height: Int): Bitmap"))
        assertEquals(1, Regex("Bitmap\\.createBitmap\\(").findAll(source).count())
        assertTrue(source.contains("eraseColor(Color.WHITE)"))
        assertEquals(
            2,
            Regex("bitmap = createPageBitmap\\(width, height\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex(
                "page\\.render\\(bitmap, null, null, " +
                    "PdfRenderer\\.Page\\.RENDER_MODE_FOR_DISPLAY\\)",
            ).findAll(source).count(),
        )
        assertEquals(
            2,
            Regex("Bitmap\\.CompressFormat\\.JPEG, 80").findAll(source).count(),
        )
        assertTrue(source.contains("for (index in selectedPages.sorted())"))
        assertTrue(source.contains("onProgress?.invoke(index + 1, effectiveTotal)"))
        assertEquals(4, Regex("paths\\.forEach \\{ File\\(it\\)\\.delete\\(\\) \\}")
            .findAll(source).count())
        assertTrue(source.contains(
            "): List<String> = renderAllPages(context, uri.toString(), maxPages, onProgress)",
        ))
        assertTrue(source.contains("val descriptor = openDescriptor(context, source) ?: return emptyList()"))
    }

    @Test
    fun `generation settings description names only localized LLM parameters`() {
        val expected = linkedMapOf(
            "values" to "LLM parameters",
            "values-ar" to "معاملات LLM",
            "values-de" to "LLM-Parameter",
            "values-es" to "Parámetros del LLM",
            "values-fr" to "Paramètres du LLM",
            "values-ja" to "LLM パラメーター",
            "values-ko" to "LLM 매개변수",
            "values-pt-rBR" to "Parâmetros do LLM",
            "values-ru" to "Параметры LLM",
            "values-vi" to "Tham số LLM",
            "values-zh" to "LLM 参数",
            "values-zh-rTW" to "LLM 參數",
        )

        expected.forEach { (directory, value) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            assertEquals(
                "$directory settings_generation_desc",
                value,
                stringValue(strings, "settings_generation_desc"),
            )
        }
    }

    @Test
    fun `chat bottom dropdowns keep twenty four dp icons and adaptive provider color`() {
        val attachment = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/AttachmentAddMenu.kt",
        )
        val bottomBar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val components = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBarComponents.kt",
        )
        val userMessage = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )

        assertTrue(components.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24"))
        assertTrue(attachment.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(bottomBar.contains("CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp"))
        assertTrue(components.contains("Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp)"))
        assertTrue(bottomBar.contains("ColorFilter.tint(LocalContentColor.current)"))
        assertFalse(bottomBar.contains("ColorFilter.tint(Color.White)"))
        assertTrue(components.contains("tint = LocalContentColor.current"))
        assertFalse(components.contains("tint = Color.White"))
        assertTrue(attachment.contains("Icons.Default.Add"))
        assertTrue(attachment.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(bottomBar.contains("Icons.Default.MoreVert"))
        assertTrue(bottomBar.contains("modifier = Modifier.size(16.dp)"))
        assertTrue(userMessage.contains("leadingIcon = { Icon(Icons.Default.ContentCopy, null) }"))
    }

    @Test
    fun `normal chat bottom fade reveals the live background instead of painting a static color`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val masks = sourceFile("app/src/main/java/com/newoether/agora/util/GradientBlur.kt")

        assertTrue(source.contains("val expandedGradientTopPaddingPx = with(density) { 20.dp.toPx() }"))
        assertTrue(source.contains("val gradientWidthPx = with(density) { 40.dp.toPx() }"))
        assertTrue(source.contains("if (!isExpanded) Spacer(modifier = Modifier.height(12.dp))"))
        assertTrue(source.contains("expandedHeightPx = with(density) { 44.dp.toPx() }"))
        assertTrue(source.contains("modifier = Modifier.fillMaxSize().gradientBlur("))
        assertTrue(source.contains("blurAtTopDp = if (blurEffectsEnabled) 8f else 0f"))
        assertTrue(masks.contains("Modifier.verticalBottomOverlayFade(fadeHeightDp, bottomOverlayHeight, renderEffect)"))
        assertTrue(source.contains("fadeHeightDp = 40f"))
        assertTrue(source.contains("bottomOverlayHeight = bottomBarHeight + with(density) { outerSpacerHeightPx.toDp() } + 12.dp"))
        assertTrue(source.contains("if (isExpanded && totalH > 0f)"))
        assertFalse(source.contains("normalGradientTopPaddingPx"))
        assertTrue(masks.contains("fun Modifier.verticalBottomOverlayFade("))
        assertTrue(masks.contains("fun bottomOverlayFadeStops("))
        assertTrue(masks.contains("CompositingStrategy.Offscreen"))
        assertTrue(masks.contains("blendMode = BlendMode.DstIn"))
    }

    @Test
    fun `bottom overlay fade geometry tracks the live composer cover and clamps safely`() {
        val regular = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 200f,
        )
        assertEquals(0.8f, regular.first, 0.0001f)
        assertEquals(0.84f, regular.second, 0.0001f)

        val oversizedOverlay = bottomOverlayFadeStops(
            canvasHeightPx = 1_000f,
            fadeHeightPx = 40f,
            bottomOverlayHeightPx = 1_500f,
        )
        assertEquals(0f, oversizedOverlay.first, 0.0001f)
        assertEquals(0.04f, oversizedOverlay.second, 0.0001f)
    }

    @Test
    fun `MCP page entry refresh is background single flight without polling`() {
        val page = sourceFile("app/src/main/java/com/newoether/agora/ui/settings/SettingsMcpPage.kt")
        val viewModel = sourceFile("app/src/main/java/com/newoether/agora/viewmodel/ChatViewModel.kt")
        val registry = sourceFile("app/src/main/java/com/newoether/agora/mcp/McpRegistry.kt")

        assertTrue(page.contains("LaunchedEffect(Unit)"))
        assertTrue(page.contains("viewModel.refreshMcpServersOnPageEntry()"))
        assertFalse(page.contains("delay("))
        assertTrue(viewModel.contains("fun refreshMcpServersOnPageEntry()"))
        assertTrue(viewModel.contains("mcpRegistry.refreshOnPageEntry()"))
        assertTrue(registry.contains("private val workDispatcher: CoroutineDispatcher = Dispatchers.IO"))
        assertTrue(registry.contains("scope.launch(workDispatcher)"))
        assertTrue(registry.contains("pendingBuilds"))
        assertTrue(registry.contains("McpRuntimeRefreshReason.PAGE_ENTRY"))
        assertTrue(registry.contains("isCurrentMcpRuntimeBuild("))
        val entryRefresh = registry.substringAfter("fun refreshOnPageEntry()")
            .substringBefore("suspend fun execute(")
        assertFalse(entryRefresh.contains("synchronized(lock)"))
        assertFalse(entryRefresh.contains("delay("))
    }

    @Test
    fun `ordinary segment detail does not repeat message error while Compact keeps its error`() {
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val segmentDetail = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/SegmentDetailSheet.kt",
        )

        val compactDetail = messageItem.substringAfter("if (showCompactDetail) {")
        val ordinarySegmentDetail = segmentDetail
            .substringAfter("internal fun MessageSegmentDetailHost(")
            .substringBefore("internal fun usesVirtualizedSegmentDetail(")

        assertTrue(compactDetail.contains("errorText = detailErrorText"))
        assertFalse(ordinarySegmentDetail.contains("errorText ="))
    }

    @Test
    fun `generation error and stopped bars share neutral body text presentation`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/GenerationErrorBar.kt",
        )
        val errorBar = source
            .substringAfter("internal fun GenerationErrorBar(")
            .substringBefore("internal fun StoppedGenerationBar(")
        val stoppedBar = source.substringAfter("internal fun StoppedGenerationBar(")

        assertTrue(errorBar.contains("GenerationTerminalText("))
        assertTrue(stoppedBar.contains("GenerationTerminalText("))
        assertTrue(source.contains("style = ChatType.body"))
        assertTrue(source.contains(
            "color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)",
        ))
    }

    @Test
    fun `chat dropdown menus share the same sixteen dp rounded shape`() {
        val bottomBar = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/bottombar/ChatBottomBar.kt",
        )
        val compactDialog = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ChatManualCompactDialog.kt",
        )

        assertTrue(bottomBar.contains(
            "internal val CHAT_DROPDOWN_MENU_SHAPE = RoundedCornerShape(16.dp)",
        ))
        assertEquals(
            3,
            Regex("shape = CHAT_DROPDOWN_MENU_SHAPE").findAll(bottomBar).count(),
        )
        assertTrue(compactDialog.contains(
            "import com.newoether.agora.ui.chat.bottombar.CHAT_DROPDOWN_MENU_SHAPE",
        ))
        assertEquals(
            1,
            Regex("shape = CHAT_DROPDOWN_MENU_SHAPE").findAll(compactDialog).count(),
        )
    }

    @Test
    fun `editing a user message scrolls its turn to focus with reduced motion fallback`() {
        val messageList = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt",
        )
        val scrollActor = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/RobustLazyListScroll.kt",
        )
        val editFocus = messageList
            .substringAfter("LaunchedEffect(\n        conversationId,\n        editingMessageId,")
            .substringBefore("LaunchedEffect(regenerationTransition?.id)")

        assertTrue(messageList.contains(
            "var editingMessageId by remember(conversationId) { mutableStateOf<String?>(null) }",
        ))
        assertTrue(editFocus.contains("messageListTurnIndex(turns, messageId)"))
        assertTrue(editFocus.contains("withFrameNanos { }"))
        assertTrue(editFocus.contains("cancelMutationAnchoring()"))
        assertTrue(editFocus.contains("140.dp.toPx()"))
        assertTrue(editFocus.contains("state.scrollToItem("))
        assertTrue(editFocus.contains("state.smoothSeekToItem("))
        assertTrue(scrollActor.contains("scroll(MutatePriority.Default)"))
    }

    @Test
    fun `user edit size owner includes the branch selector`() {
        val user = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/UserMessageBubble.kt",
        )
        val stableBlock = user
            .substringAfter("Column(\n        horizontalAlignment = Alignment.End,")
            .substringBefore("DropdownMenu(")

        assertEquals(1, Regex("Modifier\\.animateContentSize").findAll(user).count())
        assertTrue(stableBlock.contains("Modifier.animateContentSize"))
        assertTrue(user.substringAfter(stableBlock).contains("if (showBranchSelector"))
    }

    @Test
    fun `both fork entry points require the shared confirmation dialog`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val dialogs = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatDialogs.kt")
        val topMenuEntry = source
            .substringAfter("onForkConversation = {")
            .substringBefore("onShareConversation = {")
        val messageActionEntry = source
            .substringAfter("onFork = { id ->")
            .substringBefore("onShare = { id ->")
        val confirmation = dialogs
            .substringAfter("internal fun ChatForkConfirmationHost(")
            .substringBefore("internal fun ChatForkConfirmDialog(")

        assertTrue(topMenuEntry.contains(
            "pendingForkRequest = ForkConversationRequest(messageId = null)",
        ))
        assertFalse(topMenuEntry.contains("viewModel.forkConversationFrom("))
        assertTrue(messageActionEntry.contains(
            "pendingForkRequest = ForkConversationRequest(messageId = id)",
        ))
        assertFalse(messageActionEntry.contains("viewModel.forkConversationFrom("))
        assertTrue(confirmation.contains("ChatForkConfirmDialog("))
        assertEquals(
            2,
            Regex("viewModel\\.forkConversationFrom\\(").findAll(confirmation).count(),
        )
        assertTrue(source.contains(
            "ChatForkConfirmationHost(pendingForkRequest, viewModel) { pendingForkRequest = null }",
        ))
        assertTrue(confirmation.contains("onDismiss = onDismiss"))
    }

    @Test
    fun `image transcription progress does not impersonate provider retry activity`() {
        val transcription = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/TranscriptionManager.kt",
        )
        val generation = sourceFile(
            "app/src/main/java/com/newoether/agora/viewmodel/GenerationManager.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )

        assertFalse(transcription.contains("retryText ="))
        assertTrue(generation.contains(
            "retryText = context.getString(R.string.generation_retry_attempt, event.attempt, event.maxAttempts)",
        ))
        assertTrue(assistant.contains(
            "!retryText.isNullOrBlank() -> AssistantInlineActivityMode.RETRY",
        ))
    }

    @Test
    fun `Context and Thinking segment labels are localized in every supported locale`() {
        val keys = listOf(
            "context_title",
            "context_desc",
            "thinking_segment_display_mode",
            "thinking_segment_display_mode_desc",
            "thinking_segment_display_card",
            "thinking_segment_display_bottom_sheet",
            "thinking_segments_title",
        )
        val expected = linkedMapOf(
            "values-ar" to listOf(
                "السياق", "إدارة السياق", "مقاطع التفكير",
                "اختر مكان فتح مقاطع التفكير", "بطاقة", "لوحة سفلية", "مقاطع التفكير",
            ),
            "values-de" to listOf(
                "Kontext", "Kontextverwaltung", "Denksegmente",
                "Auswählen, wo Denksegmente geöffnet werden", "Karte",
                "Unteres Dialogfeld", "Denksegmente",
            ),
            "values-es" to listOf(
                "Contexto", "Gestión del contexto", "Segmentos de razonamiento",
                "Elige dónde se abren los segmentos de razonamiento", "Tarjeta",
                "Hoja inferior", "Segmentos de razonamiento",
            ),
            "values-fr" to listOf(
                "Contexte", "Gestion du contexte", "Segments de réflexion",
                "Choisissez où ouvrir les segments de réflexion", "Carte",
                "Panneau inférieur", "Segments de réflexion",
            ),
            "values-ja" to listOf(
                "コンテキスト", "コンテキスト管理", "思考セグメント",
                "思考セグメントを開く場所を選択", "カード", "ボトムシート", "思考セグメント",
            ),
            "values-ko" to listOf(
                "컨텍스트", "컨텍스트 관리", "사고 세그먼트",
                "사고 세그먼트를 열 위치 선택", "카드", "하단 시트", "사고 세그먼트",
            ),
            "values-pt-rBR" to listOf(
                "Contexto", "Gerenciamento de contexto", "Segmentos de raciocínio",
                "Escolha onde abrir os segmentos de raciocínio", "Cartão",
                "Painel inferior", "Segmentos de raciocínio",
            ),
            "values-ru" to listOf(
                "Контекст", "Управление контекстом", "Сегменты рассуждений",
                "Выберите, где открывать сегменты рассуждений", "Карточка",
                "Нижняя панель", "Сегменты рассуждений",
            ),
            "values-vi" to listOf(
                "Ngữ cảnh", "Quản lý ngữ cảnh", "Phân đoạn suy luận",
                "Chọn nơi mở các phân đoạn suy luận", "Thẻ",
                "Bảng dưới", "Phân đoạn suy luận",
            ),
            "values-zh" to listOf(
                "上下文", "上下文管理", "思考片段",
                "选择思考片段的打开位置", "卡片", "底部面板", "思考片段",
            ),
            "values-zh-rTW" to listOf(
                "上下文", "上下文管理", "思考片段",
                "選擇思考片段的開啟位置", "卡片", "底部面板", "思考片段",
            ),
        )

        expected.forEach { (directory, values) ->
            val strings = sourceFile("app/src/main/res/$directory/strings.xml")
            keys.zip(values).forEach { (key, value) ->
                assertEquals("$directory $key", value, stringValue(strings, key))
            }
        }
    }

    @Test
    fun `transcription chooser lists concrete models only while nullable summary stays compatible`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsTranscriptionPage.kt",
        )
        val chooser = source
            .substringAfter("if (showModelDialog)")
            .substringBefore("if (showAddDialog)")

        assertFalse(chooser.contains("transcription-model-none"))
        assertFalse(chooser.contains("setImageTranscriptionModel(null)"))
        assertTrue(source.contains("?: stringResource(R.string.transcription_no_model)"))
        assertTrue(source.contains("transcriptionModel == null"))
    }

    @Test
    fun `Appearance uses semantic groups and leading visuals for every setting row`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val groupKeys = listOf(
            "appearance_theme_color",
            "appearance_motion_feedback",
            "appearance_chat_display",
            "font_title",
        )
        val groupIndices = groupKeys.map { key ->
            appearance.indexOf("title = stringResource(R.string.$key)")
        }

        assertTrue(groupIndices.all { it >= 0 })
        assertTrue(groupIndices.zipWithNext().all { (current, next) -> current < next })
        assertFalse(appearance.contains("R.string.appearance_interface"))
        assertEquals(4, Regex("SettingsGroup\\(").findAll(appearance).count())
        assertEquals(15, Regex("SettingsItem\\(").findAll(appearance).count())
        assertEquals(15, Regex("leadingContent\\s*=").findAll(appearance).count())
        listOf(
            "Palette",
            "Style",
            "BlurOn",
            "MotionPhotosOff",
            "Vibration",
            "VerticalAlignBottom",
            "Functions",
            "ViewAgenda",
            "TextFields",
            "UploadFile",
        ).forEach { icon ->
            assertTrue("Appearance is missing the $icon icon", appearance.contains("Icons.Default.$icon"))
        }
        val toolBlocksRow = appearance
            .substringAfter("headlineContent = { Text(stringResource(R.string.tool_call_display_mode)) }")
            .substringBefore("trailingContent =")
        val thinkingSegmentsRow = appearance
            .substringAfter("Text(stringResource(R.string.thinking_segment_display_mode))")
            .substringBefore("trailingContent =")
        val autoExpandRow = appearance
            .substringAfter("Text(stringResource(R.string.auto_expand_active_group))")
            .substringBefore("trailingContent =")
        assertTrue(toolBlocksRow.contains("painterResource(R.drawable.material_symbol_lists_24)"))
        assertTrue(thinkingSegmentsRow.contains("Icons.Default.ViewAgenda"))
        assertTrue(
            autoExpandRow.contains(
                "R.drawable.material_symbol_expand_content_24",
            ),
        )
        listOf("AccountTree", "Psychology", "UnfoldMore").forEach { oldIcon ->
            assertFalse("Appearance still uses $oldIcon", appearance.contains("Icons.Default.$oldIcon"))
        }
        assertTrue(appearance.contains(".background(currentPrimary)"))

        val toolBlocksIndex = appearance.indexOf("R.string.tool_call_display_mode")
        val thinkingSegmentIndex = appearance.indexOf("R.string.thinking_segment_display_mode")
        val autoExpandIndex = appearance.indexOf("R.string.auto_expand_active_group")
        assertTrue(toolBlocksIndex >= 0)
        assertTrue(thinkingSegmentIndex > toolBlocksIndex)
        assertTrue(autoExpandIndex > thinkingSegmentIndex)
    }

    @Test
    fun `Appearance removes dead detailed token usage UI threading but keeps persistence compatibility`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val messageList = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/MessageList.kt")
        val messageItem = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt",
        )
        val assistant = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt",
        )
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )

        listOf(appearance, chatApp, messageList, messageItem, assistant).forEach {
            assertFalse(it.contains("detailedTokenUsage"))
        }
        assertFalse(appearance.contains("R.string.detailed_token_usage"))
        assertFalse(appearance.contains("setDetailedTokenUsage"))
        assertTrue(settings.contains("detailedTokenUsage"))
        assertTrue(settings.contains("saveDetailedTokenUsage"))
        assertTrue(archive.contains("\"detailedTokenUsage\""))
    }

    @Test
    fun `Stick to bottom is portable and gates only generation auto follow`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val chatApp = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val settings = sourceFile(
            "app/src/main/java/com/newoether/agora/data/SettingsManager.kt",
        )
        val repository = sourceFile(
            "app/src/main/java/com/newoether/agora/data/repository/SettingsRepository.kt",
        )
        val archive = sourceFile(
            "app/src/main/java/com/newoether/agora/data/PortableSettingsArchive.kt",
        )
        val availability = chatApp
            .substringAfter("val streamingFollowAvailability =")
            .substringBefore("Box(modifier = Modifier.fillMaxSize())")

        assertTrue(appearance.contains("R.string.stick_to_bottom"))
        assertTrue(appearance.contains("setStickToBottom"))
        assertTrue(settings.contains("it[STICK_TO_BOTTOM] ?: true"))
        assertTrue(settings.contains("saveStickToBottom"))
        assertTrue(settings.contains("prefs.remove(STICK_TO_BOTTOM)"))
        assertTrue(repository.contains("hot(settingsManager.stickToBottom, true)"))
        assertTrue(archive.contains("\"stickToBottom\""))
        assertTrue(archive.contains("saveStickToBottom"))
        assertFalse(availability.contains("stickToBottom"))
        assertTrue(chatApp.contains(
            "streamingFollowAvailability.enabled && stickToBottom",
        ))
    }

    @Test
    fun `Thinking display policy is configurable only outside Timeline and auto expands Grouped cards`() {
        val appearance = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAppearancePage.kt",
        )
        val assistant =
            sourceFile("app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessageContent.kt")
        val presentationModel =
            sourceFile("app/src/main/java/com/newoether/agora/ui/chat/message/AssistantMessagePresentation.kt")
        val messageItem =
            sourceFile("app/src/main/java/com/newoether/agora/ui/chat/message/MessageItem.kt")
        val model = sourceFile("app/src/main/java/com/newoether/agora/model/ChatMessage.kt")

        assertTrue(model.contains("fun isAvailableFor(toolCallDisplayMode: String?)"))
        assertTrue(model.contains("fun effectiveMode("))
        assertTrue(model.contains("fun allowsAutoExpand("))
        assertTrue(model.contains(
            "ToolCallDisplayModes.normalize(toolCallDisplayMode) != ToolCallDisplayModes.TIMELINE"
        ))
        assertTrue(model.contains("ToolCallDisplayModes.GROUPED_TIMELINE"))
        assertTrue(model.contains("normalize(thinkingSegmentDisplayMode) == CARD"))
        assertTrue(appearance.contains(
            "ThinkingSegmentDisplayModes.isAvailableFor(normalizedToolCallDisplayMode)"
        ))
        assertTrue(appearance.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
        val toolBlocksIndex = appearance.indexOf("R.string.tool_call_display_mode")
        val thinkingSegmentIndex = appearance.indexOf("R.string.thinking_segment_display_mode")
        val autoExpandIndex = appearance.indexOf("R.string.auto_expand_active_group")
        assertTrue(toolBlocksIndex >= 0)
        assertTrue(thinkingSegmentIndex > toolBlocksIndex)
        assertTrue(autoExpandIndex > thinkingSegmentIndex)
        assertTrue(presentationModel.contains("ThinkingSegmentDisplayModes.effectiveMode("))
        assertTrue(messageItem.contains("ThinkingSegmentDisplayModes.allowsAutoExpand("))
    }

    @Test
    fun `Responses API rows use the API Format JSON icon`() {
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )

        assertTrue(provider.contains("SettingsIconContent(icon = Icons.Default.DataObject)"))
        val responseRows = provider
            .split("headlineContent = { Text(stringResource(R.string.responses_api)) },")
            .drop(1)
        assertEquals(2, responseRows.size)
        responseRows.forEach { rowSource ->
            val row = rowSource.substringBefore("trailingContent = {")
            assertTrue(row.contains("Icons.Default.DataObject"))
            assertTrue(row.contains("tint = MaterialTheme.colorScheme.primary"))
        }
    }

    @Test
    fun `provider base URL placeholder stays separate from its stored default`() {
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderDetailPage.kt",
        )

        assertTrue(provider.contains(
            "val defaultUrl = providerInstance?.defaultBaseUrl.orEmpty()"
        ))
        assertTrue(provider.contains(
            "val placeholderUrl = providerInstance?.baseUrlPlaceholder.orEmpty()"
        ))
        assertTrue(provider.contains(
            "val displayedUrl = savedUrl?.takeIf(String::isNotBlank) ?: defaultUrl"
        ))
        assertTrue(provider.contains("placeholder = { Text(placeholderUrl"))
    }

    @Test
    fun `Settings destination rows omit redundant arrows without losing behavior`() {
        val home = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsScreen.kt",
        )
        val shell = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsShellPage.kt",
        )
        val provider = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsProviderPage.kt",
        )

        assertFalse(home.contains("KeyboardArrowRight"))
        assertTrue(home.contains(".clickable { selectedCategory = cat.key }"))
        assertTrue(home.contains("Column(modifier = Modifier.weight(1f))"))

        val sandbox = shell
            .substringAfter("private fun SandboxSection(")
            .substringBefore("private fun SandboxNotSupportedSection(")
        assertFalse(sandbox.contains("Icons.Default.ChevronRight"))
        assertTrue(sandbox.contains("Switch(checked = sandboxEnabled"))
        assertTrue(sandbox.contains("modifier = Modifier.clickable { onManage() }"))

        assertFalse(provider.contains("KeyboardArrowRight"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = name }"))
        assertTrue(provider.contains("config.protocol.displayName()"))
        assertTrue(provider.contains("modifier = Modifier.clickable { selectedProvider = config.name }"))
        assertTrue(provider.contains(
            "modifier = Modifier.clickable { selectedProvider = Constants.PROVIDER_LOCAL }"
        ))
        assertFalse(provider.contains("Spacer(modifier = Modifier.width(4.dp))"))
    }

    @Test
    fun `shell confirmation code surface provides standalone Markdown locals`() {
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val assets = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/message/MessageBubbleAssets.kt",
        )
        val codeBlock = assets
            .substringAfter("internal fun ChatMarkdownCodeBlock(")
            .substringBefore("private fun OverflowFriendlyMarkdownTable(")

        assertTrue(main.contains("ChatMarkdownCodeBlock(code = pending.summary)"))
        assertTrue(codeBlock.contains("CompositionLocalProvider("))
        assertTrue(codeBlock.contains("LocalMarkdownColors provides assets.renderContext.colors"))
        assertTrue(codeBlock.contains("LocalMarkdownDimens provides markdownDimens()"))
        assertTrue(codeBlock.contains("MarkdownCodeBackground("))
    }

    @Test
    fun `every full screen viewer uses shared spatial entrance and exit with reduced motion fallback`() {
        val source = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val mediaViewer = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/FullScreenMediaViewer.kt",
        )
        val mediaDialog = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/FullScreenMediaPreviewDialog.kt",
        )
        val imageActions = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ImageActions.kt",
        )
        val videoPlayer = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/VideoPlayer.kt",
        )
        val texturePlayerLayout = sourceFile(
            "app/src/main/res/layout/view_texture_video_player.xml",
        )
        val dialogWindow = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/components/DialogWindowEdgeToEdge.kt",
        )

        assertTrue(source.contains(
            "private fun fullScreenPreviewEnterTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeIn(tween(durationMillis = 220))"))
        assertTrue(source.contains(
            "scaleIn(tween(durationMillis = 300, easing = FastOutSlowInEasing), initialScale = 0.96f)"
        ))
        assertTrue(source.contains("EnterTransition.None"))
        assertTrue(source.contains(
            "private fun fullScreenPreviewExitTransition(allowSpatialTransitions: Boolean)"
        ))
        assertTrue(source.contains("fadeOut(tween(durationMillis = 180))"))
        assertTrue(source.contains(
            "scaleOut(tween(durationMillis = 220, easing = FastOutLinearInEasing), targetScale = 0.96f)"
        ))
        assertTrue(source.contains("ExitTransition.None"))
        assertEquals(
            2,
            Regex("enter = fullScreenPreviewEnterTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(
            2,
            Regex("exit = fullScreenPreviewExitTransition\\(motionPolicy\\.allowSpatialTransitions\\)")
                .findAll(source)
                .count(),
        )
        assertEquals(1, Regex("if \\(!currentState && !isRunning\\)").findAll(source).count())
        assertTrue(mediaDialog.contains("if (!currentState && !isRunning && latestTarget == null) onHidden()"))
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.MEDIA_PREVIEW)"
        ))
        assertTrue(source.contains(
            "topLevelPresentation.release(TopLevelPresentation.TEXT_PREVIEW)"
        ))
        assertTrue(source.contains("FullScreenMediaPreviewDialog("))
        assertTrue(mediaDialog.contains("Dialog("))
        assertTrue(mediaDialog.contains(".background(Color.Black)"))
        assertTrue(mediaDialog.contains("visibilityTransition.animateFloat("))
        assertTrue(mediaDialog.contains("label = \"mediaPreviewBackdropAlpha\""))
        assertTrue(mediaDialog.contains(".graphicsLayer { alpha = backdropAlpha }"))
        assertTrue(mediaDialog.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemDim()"))
        assertTrue(imageActions.contains("DialogWindowNoSystemAnimation()"))
        assertTrue(imageActions.contains("withFrameNanos { }"))
        assertTrue(imageActions.contains("HttpClient.client.newCall("))
        assertTrue(videoPlayer.contains("R.layout.view_texture_video_player"))
        assertTrue(texturePlayerLayout.contains("""app:surface_type="texture_view""""))
        assertTrue(texturePlayerLayout.contains(
            """app:shutter_background_color="@android:color/transparent"""",
        ))
        assertTrue(dialogWindow.contains(
            "clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)"
        ))
        assertTrue(source.contains("if (savedContent != null && savedName != null)"))
        assertTrue(mediaViewer.contains(
            "val currentPageIsVideo = rememberIsVideoMedia(urls[pagerState.currentPage])",
        ))
        assertTrue(mediaViewer.contains("if (currentPageIsVideo == true) closing = true"))
        assertTrue(mediaViewer.contains("else onClose()"))
        assertTrue(mediaViewer.contains("BackHandler { requestClose() }"))
        assertTrue(mediaViewer.contains("onClick = { requestClose() }"))
        assertFalse(mediaViewer.contains("LaunchedEffect(closing)"))
        assertFalse(mediaViewer.contains("kotlinx.coroutines.delay(400)"))
    }

    @Test
    fun `notification permission waits for Chat and gates initial composer focus until dismissal`() {
        val main = sourceFile("app/src/main/java/com/newoether/agora/MainActivity.kt")
        val chat = sourceFile("app/src/main/java/com/newoether/agora/ui/chat/ChatApp.kt")
        val interactionEffects = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/chat/ChatAppInteractionEffects.kt",
        )
        val service = sourceFile(
            "app/src/main/java/com/newoether/agora/service/AgoraForegroundService.kt",
        )
        val activityStartup = main
            .substringAfter("override fun onCreate(savedInstanceState: Bundle?)")
            .substringBefore("override fun onResume()")
        val mainNavigation = main.substringAfter("fun MainNavigation(")
        val permissionLauncher = mainNavigation
            .substringAfter("val notificationPermissionLauncher")
            .substringBefore("LaunchedEffect(Unit)")
        val permissionEffect = mainNavigation
            .substringAfter("LaunchedEffect(Unit) {")
            .substringBefore("var showSettings")
        val launchEffects = interactionEffects
            .substringAfter("internal fun ChatLaunchInteractionEffects(")
            .substringBefore("internal fun ChatNavigationEffects(")
        val generationChannel = service
            .substringAfter("private fun createGenerationChannel(context: Context)")
            .substringBefore("fun showTerminalNotification(")
        val completionChannel = service
            .substringAfter("private fun createCompletionChannel(context: Context)")
            .substringBefore("private fun createPendingIntent(")

        val onboardingBranch = activityStartup.substringAfter("when (showOnboarding)")

        assertFalse(activityStartup.contains("requestPermissions("))
        assertTrue(onboardingBranch.substringAfter("false -> {").contains("MainNavigation("))
        assertTrue(mainNavigation.contains("mutableStateOf(!shouldRequestNotificationPermission)"))
        assertTrue(permissionLauncher.contains("initialComposerFocusReady = true"))
        assertTrue(permissionEffect.contains("AgoraForegroundService.createChannels(appContext)"))
        assertTrue(permissionEffect.contains("notificationPermissionLauncher.launch("))
        assertFalse(permissionEffect.contains("delay("))
        assertTrue(
            permissionEffect.indexOf("AgoraForegroundService.createChannels(appContext)") <
                permissionEffect.indexOf("notificationPermissionLauncher.launch("),
        )
        assertTrue(main.contains("initialComposerFocusReady = initialComposerFocusReady"))
        assertTrue(chat.contains("initialComposerFocusReady: Boolean = true"))
        assertTrue(chat.contains("ChatLaunchInteractionEffects("))
        assertTrue(launchEffects.contains("LaunchedEffect(Unit)"))
        assertTrue(launchEffects.contains("latestOnShowLaunchContent()"))
        assertTrue(launchEffects.contains(
            "LaunchedEffect(initialComposerFocusReady, inputFocusRequester)"
        ))
        assertTrue(launchEffects.contains("if (initialComposerFocusReady)"))
        assertTrue(launchEffects.contains("inputFocusRequester.requestFocus()"))
        assertTrue(generationChannel.contains("NotificationManager.IMPORTANCE_LOW"))
        assertTrue(generationChannel.contains("setSound(null, null)"))
        assertTrue(generationChannel.contains("setShowBadge(false)"))
        assertTrue(completionChannel.contains("NotificationManager.IMPORTANCE_HIGH"))
        assertTrue(completionChannel.contains("RingtoneManager.TYPE_NOTIFICATION"))
        assertTrue(completionChannel.contains("enableVibration(true)"))
        assertTrue(service.contains(".setPriority(NotificationCompat.PRIORITY_HIGH)"))
    }

    @Test
    fun `Automation groups exact execution and battery optimization in every locale`() {
        val page = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/settings/SettingsAutomationPage.kt",
        )
        val manifest = sourceFile("app/src/main/AndroidManifest.xml")
        val backgroundGroup = page
            .substringAfter("title = stringResource(R.string.automation_background_execution)")
            .substringBefore("if (showDocFab)")

        assertTrue(backgroundGroup.contains("R.string.automation_exact_execution"))
        assertTrue(backgroundGroup.contains("R.string.automation_battery_optimization"))
        assertTrue(
            backgroundGroup.indexOf("R.string.automation_exact_execution") <
                backgroundGroup.indexOf("R.string.automation_battery_optimization"),
        )
        assertTrue(page.contains("isIgnoringBatteryOptimizations(context.packageName)"))
        assertTrue(page.contains("Lifecycle.Event.ON_RESUME"))
        assertTrue(page.contains("Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS"))
        assertFalse(manifest.contains("REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))

        val directories = listOf(
            "values", "values-ar", "values-de", "values-es", "values-fr", "values-ja",
            "values-ko", "values-pt-rBR", "values-ru", "values-vi", "values-zh",
            "values-zh-rTW",
        )
        val keys = listOf(
            "automation_background_execution",
            "automation_battery_optimization",
            "automation_battery_optimization_ignored_desc",
            "automation_battery_optimization_active_desc",
        )
        directories.forEach { directory ->
            val fileName = if (directory == "values-zh") "strings.xml" else "automation_strings.xml"
            val strings = sourceFile("app/src/main/res/$directory/$fileName")
            keys.forEach { key ->
                assertTrue("$directory $key", strings.contains("name=\"$key\""))
            }
            assertFalse("$directory obsolete scheduling category", strings.contains(
                "name=\"automation_scheduling\"",
            ))
        }
    }

    @Test
    fun `Once date picker keeps the Material modal height without taking over mode or IME`() {
        val source = sourceFile(
            "app/src/main/java/com/newoether/agora/ui/tasks/TaskEditorSupportingComponents.kt",
        )
        val picker = source
            .substringAfter("internal fun TaskDatePickerDialog(")
            .substringBefore("internal fun TaskTimePickerDialog(")

        assertTrue(picker.contains("modifier = Modifier.height(568.dp)"))
        assertTrue(picker.contains("showModeToggle = true"))
        assertFalse(picker.contains("pendingCalendarMode"))
        assertFalse(picker.contains("displayMode = DatePickerDisplayMode"))
        assertFalse(picker.contains("LocalSoftwareKeyboardController"))
        assertFalse(picker.contains("delay("))
    }

    private fun stringValue(xml: String, key: String): String {
        val regex = Regex("""<string name="$key">([^<]*)</string>""")
        return requireNotNull(regex.find(xml)) { "Missing $key" }.groupValues[1]
    }

    private fun sourceFile(relativePath: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, relativePath)
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $relativePath")
    }
}
