package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QuestionAnswer
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.chat.message.ChatMarkdownCodeBlock
import com.newoether.agora.viewmodel.AskUserController
import com.newoether.agora.viewmodel.ShellConfirmationController

private val ContentMaxWidth = 840.dp
private val ScrollableContentMaxHeight = 200.dp

/**
 * Barra inferior que responde los requests de [interactions] sin cubrir la conversación.
 *
 * Los requests se apilan, así que el bar es un pager: una card por request, deslizables, la
 * más vieja primero. Responder remueve esa card y la siguiente toma su lugar. Nada aquí se
 * cierra tocando fuera; ambos tipos de request se responden solo con sus propios botones, lo
 * que mantiene la confirmación de shell como un gate de seguridad real.
 *
 * [onHeightChanged] reporta la altura medida en px para que el host eleve lo que está sobre el
 * composer, y reporta cero cuando no hay nada que responder.
 */
@Composable
internal fun UserInteractionBar(
    interactions: List<UserInteraction>,
    onAnswerQuestion: (Long, List<String>) -> Unit,
    onSkipQuestion: (Long) -> Unit,
    onShellDecision: (Long, Boolean, Boolean) -> Unit,
    onHeightChanged: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (interactions.isEmpty()) {
        LaunchedEffect(Unit) { onHeightChanged(0f) }
        return
    }
    val pagerState = rememberPagerState(pageCount = { interactions.size })
    Column(
        modifier = modifier
            .fillMaxWidth()
            .onSizeChanged { onHeightChanged(it.height.toFloat()) },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.widthIn(max = ContentMaxWidth),
            contentPadding = PaddingValues(horizontal = 12.dp),
            pageSpacing = 8.dp,
            verticalAlignment = Alignment.Bottom,
            key = { index -> interactions.getOrNull(index)?.key ?: index },
        ) { page ->
            when (val interaction = interactions.getOrNull(page)) {
                is UserInteraction.Question -> InteractionCard {
                    QuestionCardContent(
                        request = interaction.request,
                        onAnswer = { choices -> onAnswerQuestion(interaction.request.id, choices) },
                        onSkip = { onSkipQuestion(interaction.request.id) },
                    )
                }
                is UserInteraction.ShellCommand -> InteractionCard {
                    ShellCardContent(
                        pending = interaction.pending,
                        onDecision = { allow, alwaysAllow ->
                            onShellDecision(interaction.pending.id, allow, alwaysAllow)
                        },
                    )
                }
                null -> Box(modifier = Modifier.fillMaxWidth())
            }
        }
        if (interactions.size > 1) {
            Spacer(Modifier.height(8.dp))
            PageIndicator(pageCount = interactions.size, currentPage = pagerState.currentPage)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InteractionCard(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) { content() }
    }
}

@Composable
private fun QuestionCardContent(
    request: AskUserController.Request,
    onAnswer: (List<String>) -> Unit,
    onSkip: () -> Unit,
) {
    // La selección pertenece solo a este request: uno nuevo jamás hereda una respuesta vieja.
    var selected by remember(request.id) { mutableStateOf(emptySet<String>()) }
    CardHeader(
        icon = { tint ->
            Icon(Icons.Default.QuestionAnswer, null, modifier = Modifier.size(18.dp), tint = tint)
        },
        title = stringResource(R.string.ask_user_title),
    )
    Spacer(Modifier.height(10.dp))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = request.question,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(10.dp))
        request.options.forEach { option ->
            OptionRow(
                option = option,
                checked = option in selected,
                allowMultiple = request.allowMultiple,
                onToggle = {
                    selected = when {
                        !request.allowMultiple -> setOf(option)
                        option in selected -> selected - option
                        else -> selected + option
                    }
                },
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onSkip) { Text(stringResource(R.string.ask_user_skip)) }
        Spacer(Modifier.width(4.dp))
        Button(
            onClick = { onAnswer(request.options.filter { it in selected }) },
            enabled = selected.isNotEmpty(),
        ) { Text(stringResource(R.string.ask_user_send)) }
    }
}

@Composable
private fun OptionRow(
    option: String,
    checked: Boolean,
    allowMultiple: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // El row es dueño del click, así el control se mantiene no interactivo y todo el row
        // se lee como un solo target para servicios de accesibilidad.
        if (allowMultiple) {
            Checkbox(checked = checked, onCheckedChange = null)
        } else {
            RadioButton(selected = checked, onClick = null)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = option,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun ShellCardContent(
    pending: ShellConfirmationController.PendingShellCommand,
    onDecision: (Boolean, Boolean) -> Unit,
) {
    var alwaysAllow by remember(pending.id) { mutableStateOf(false) }
    CardHeader(
        icon = { tint ->
            Icon(Icons.Default.Terminal, null, modifier = Modifier.size(18.dp), tint = tint)
        },
        title = stringResource(R.string.shell_confirm_title, pending.server),
    )
    Spacer(Modifier.height(10.dp))
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = ScrollableContentMaxHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        ChatMarkdownCodeBlock(code = pending.summary)
    }
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { alwaysAllow = !alwaysAllow },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = alwaysAllow, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.shell_confirm_always),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
    Spacer(Modifier.height(6.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onDecision(false, false) },
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) { Text(stringResource(R.string.shell_confirm_deny)) }
        Spacer(Modifier.width(4.dp))
        Button(onClick = { onDecision(true, alwaysAllow) }) {
            Text(stringResource(R.string.shell_confirm_allow))
        }
    }
}

@Composable
private fun CardHeader(
    icon: @Composable (Color) -> Unit,
    title: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        icon(MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun PageIndicator(pageCount: Int, currentPage: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(pageCount) { index ->
            val active = index == currentPage
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (active) 7.dp else 5.dp)
                    .clip(CircleShape)
                    .background(
                        if (active) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    ),
            )
        }
    }
}
