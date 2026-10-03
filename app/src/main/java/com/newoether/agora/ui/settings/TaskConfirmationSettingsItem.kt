package com.newoether.agora.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.local.TaskConfirmationCardStyle
import com.newoether.agora.data.local.TaskConfirmationMode
import com.newoether.agora.data.repository.SettingsRepository

/**
 * Self-contained settings section for rich task confirmations (plan PLAN-20261002-TASK-CONFIRM,
 * F7). Own file because SettingsAutomationPage sits near the 999-line budget.
 *
 * Presentation follows Universal Installer's "Pantalla de instalación" (InstallUiPreviewComponents):
 * a phone-shaped frame showing the confirmation surface exactly where it will land (mini card
 * anchored bottom/centered, or a notification row for the informational mode), and the card-style
 * picker rendered as two tappable mini-phone thumbnails instead of plain radios.
 */
@Composable
fun TaskConfirmationSettingsItem(
    settingsRepository: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val enabled by settingsRepository.taskConfirmationEnabled.collectAsState()
    val mode by settingsRepository.taskConfirmationMode.collectAsState()
    val cardStyle by settingsRepository.taskConfirmationCardStyle.collectAsState()

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Enable toggle ────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.task_confirmation_settings_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.task_confirmation_settings_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.padding(horizontal = 4.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { settingsRepository.setTaskConfirmationEnabled(it) },
            )
        }

        if (enabled) {
            // ── Phone-frame preview: the surface where it will actually land ──
            ConfirmationUiPreview(mode = mode, cardStyle = cardStyle)

            // ── Presentation mode radios (Universal Installer's ExternalOpenMode analogue) ──
            ModeRadio(
                label = stringResource(R.string.task_confirmation_mode_prompt),
                description = stringResource(R.string.task_confirmation_mode_prompt_desc),
                selected = mode == TaskConfirmationMode.PROMPT,
                onSelect = { settingsRepository.setTaskConfirmationMode(TaskConfirmationMode.PROMPT) },
            )
            ModeRadio(
                label = stringResource(R.string.task_confirmation_mode_auto),
                description = stringResource(R.string.task_confirmation_mode_auto_desc),
                selected = mode == TaskConfirmationMode.AUTO,
                onSelect = { settingsRepository.setTaskConfirmationMode(TaskConfirmationMode.AUTO) },
            )

            // ── Card anchor — tappable mini-phone thumbnails (InstallUiStyle analogue) ──
            if (mode == TaskConfirmationMode.PROMPT) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                Text(
                    text = stringResource(R.string.task_confirmation_card_style_section),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                CardAnchorPicker(
                    current = cardStyle,
                    onChange = { settingsRepository.setTaskConfirmationCardStyle(it) },
                )
            }
        }
    }
}

/**
 * A phone-shaped frame with the confirmation surface drawn where it will actually land:
 * a mini card anchored bottom/centered (PROMPT) or a notification shade row (AUTO).
 * Direct analogue of Universal Installer's InstallUiPreview.
 */
@Composable
private fun ConfirmationUiPreview(
    mode: TaskConfirmationMode,
    cardStyle: TaskConfirmationCardStyle,
) {
    val accent = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier.size(width = 150.dp, height = 264.dp),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(12.dp),
            ) {
                when (mode) {
                    TaskConfirmationMode.AUTO -> MiniNotification(
                        modifier = Modifier.align(Alignment.TopCenter),
                        accent = accent,
                    )

                    TaskConfirmationMode.PROMPT -> MiniCard(
                        modifier = Modifier.align(
                            if (cardStyle == TaskConfirmationCardStyle.CENTERED) {
                                Alignment.Center
                            } else {
                                Alignment.BottomCenter
                            },
                        ),
                        widthFraction = if (cardStyle == TaskConfirmationCardStyle.CENTERED) 0.86f else 1f,
                        accent = accent,
                    )
                }
            }
        }
    }
}

/** Skeleton confirmation card: title line, body lines, and one accent action line. */
@Composable
private fun MiniCard(modifier: Modifier, widthFraction: Float, accent: Color) {
    Column(
        modifier = modifier
            .fillMaxWidth(widthFraction)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        MiniLine(0.7f, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        MiniLine(1f, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        MiniLine(0.9f, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        Spacer(Modifier.height(2.dp))
        MiniLine(0.45f, accent, height = 7.dp)
    }
}

/** Skeleton notification row: app icon square plus two text lines. */
@Composable
private fun MiniNotification(modifier: Modifier, accent: Color) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(accent),
        )
        Spacer(Modifier.width(6.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            MiniLine(0.8f, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
            MiniLine(1f, MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        }
    }
}

@Composable
private fun MiniLine(widthFraction: Float, color: Color, height: Dp = 4.dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(widthFraction)
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

/**
 * Bottom/Centered as two tappable mini-phone thumbnails — the picker IS the preview
 * (Universal Installer's CardPositionPicker analogue).
 */
@Composable
private fun CardAnchorPicker(
    current: TaskConfirmationCardStyle,
    onChange: (TaskConfirmationCardStyle) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 40.dp, end = 24.dp, top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(
            TaskConfirmationCardStyle.BOTTOM to R.string.task_confirmation_card_style_bottom,
            TaskConfirmationCardStyle.CENTERED to R.string.task_confirmation_card_style_centered,
        ).forEach { (style, labelRes) ->
            AnchorThumbnail(
                style = style,
                label = stringResource(labelRes),
                selected = style == current,
                onClick = { if (style != current) onChange(style) },
            )
        }
    }
}

@Composable
private fun AnchorThumbnail(
    style: TaskConfirmationCardStyle,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val accent = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            modifier = Modifier
                .size(width = 84.dp, height = 112.dp)
                .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) accent else MaterialTheme.colorScheme.outlineVariant,
            ),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(9.dp),
                contentAlignment = if (style == TaskConfirmationCardStyle.BOTTOM) {
                    Alignment.BottomCenter
                } else {
                    Alignment.Center
                },
            ) {
                MiniCard(
                    modifier = Modifier,
                    widthFraction = if (style == TaskConfirmationCardStyle.BOTTOM) 1f else 0.82f,
                    accent = accent,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected) {
                Icon(
                    imageVector = Icons.Rounded.CheckCircle,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ModeRadio(
    label: String,
    description: String?,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
