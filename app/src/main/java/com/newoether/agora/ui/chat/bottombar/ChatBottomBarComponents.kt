package com.newoether.agora.ui.chat.bottombar

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.ui.theme.ChatType

internal const val CHAT_DROPDOWN_MENU_ICON_SIZE_DP = 24

fun Modifier.verticalScrollbar(
    scrollState: ScrollState,
    color: Color,
    width: androidx.compose.ui.unit.Dp = 3.dp
): Modifier = drawWithContent {
    drawContent()
    if (scrollState.maxValue > 0) {
        val viewPortHeight = size.height
        val totalHeight = scrollState.maxValue + viewPortHeight
        val thumbHeight = (viewPortHeight / totalHeight) * viewPortHeight
        val thumbOffset = (scrollState.value / totalHeight) * viewPortHeight
        drawRoundRect(color = color, topLeft = Offset(size.width - width.toPx() - 4.dp.toPx(), thumbOffset), size = Size(width.toPx(), thumbHeight), cornerRadius = CornerRadius(width.toPx() / 2))
    }
}

@Composable
internal fun NativeSearchMenuItem(
    checked: Boolean,
    provider: String,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    DropdownMenuItem(
        text = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.provider_openai),
                    contentDescription = null,
                    tint = LocalContentColor.current,
                    modifier = Modifier.size(CHAT_DROPDOWN_MENU_ICON_SIZE_DP.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(stringResource(R.string.openai_search))
                Spacer(modifier = Modifier.width(10.dp))
                ProviderBadge(provider)
            }
        },
        trailingIcon = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = Modifier.scale(0.7f),
            )
        },
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
    )
}

@Composable
internal fun ProviderBadge(provider: String) {
    val badgeColor = when (provider.lowercase()) {
        "google", "gemini", "openai" -> MaterialTheme.colorScheme.onPrimaryContainer
        "anthropic" -> Color(0xFFD97757)
        else -> MaterialTheme.colorScheme.primary
    }
    val badgeBackground = when (provider.lowercase()) {
        "google", "gemini", "openai" -> MaterialTheme.colorScheme.primaryContainer
        else -> badgeColor.copy(alpha = 0.15f)
    }
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = badgeBackground
    ) {
        Text(
            provider,
            style = ChatType.micro,
            color = badgeColor,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
        )
    }
}

internal fun contextUsageAtCapacity(estimatedTokens: Int, tokenBudget: Int): Boolean =
    tokenBudget > 0 && estimatedTokens >= tokenBudget
