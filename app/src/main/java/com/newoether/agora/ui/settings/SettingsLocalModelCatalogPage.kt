package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.catalog.CatalogEntry
import com.newoether.agora.data.catalog.ModelCatalogParser
import com.newoether.agora.data.local.LocalModelDownloadEntity
import com.newoether.agora.data.localmodel.DownloadProgress
import com.newoether.agora.data.localmodel.LocalModelStatus
import com.newoether.agora.service.LocalModelDownloadWorker
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.launch

/**
 * Catalog of downloadable local (GGUF) models: hosted entries (remote → cache
 * → bundled fallback) merged with durable download rows and live WorkManager
 * progress. Completion registers the model into the Local provider
 * automatically (worker-side, via the process-scoped ModelManager).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsLocalModelCatalogPage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    var entries by remember { mutableStateOf<List<CatalogEntry>?>(null) }
    var showCancelConfirm by remember { mutableStateOf<CatalogEntry?>(null) }

    val rows by viewModel.localModelDownloadManager.observeRows()
        .collectAsState(initial = emptyList())
    val workInfos by viewModel.localModelDownloadManager.observeWorkInfos()
        .collectAsState(initial = emptyList())

    fun progressFor(catalogEntryId: String): Triple<Long, Long, Long> {
        val info = workInfos.firstOrNull { info ->
            info.tags.any { it == LocalModelDownloadWorker.idTag(catalogEntryId) }
        } ?: return Triple(0L, 0L, 0L)
        if (info.state.isFinished) return Triple(0L, 0L, 0L)
        val received = info.progress.getLong(LocalModelDownloadWorker.KEY_RECEIVED_BYTES, 0L)
        val rate = info.progress.getLong(LocalModelDownloadWorker.KEY_DOWNLOAD_RATE, 0L)
        val remaining = info.progress.getLong(LocalModelDownloadWorker.KEY_REMAINING_MS, 0L)
        return Triple(received, rate, remaining)
    }

    suspend fun reloadCatalog(forceRefresh: Boolean = false) {
        entries = viewModel.modelCatalogRepository.getVisibleEntries(forceRefresh)
    }

    LaunchedEffect(Unit) { reloadCatalog() }

    CollapsingSettingsScaffold(
        title = stringResource(R.string.local_model_catalog_title),
        onBack = onBack,
        actions = {
            IconButton(onClick = { scope.launch { reloadCatalog(forceRefresh = true) } }) {
                Icon(Icons.Default.Refresh, stringResource(R.string.local_model_catalog_retry))
            }
        },
    ) {
        SettingsGroupColumn {
            val currentEntries = entries
            if (currentEntries == null) {
                SettingsGroup(title = "", items = listOf(
                    {
                        Box(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                stringResource(R.string.local_model_catalog_loading),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                ))
            } else if (currentEntries.isEmpty()) {
                SettingsGroup(title = "", items = listOf(
                    {
                        SettingsItem(
                            headlineContent = {
                                Text(
                                    stringResource(R.string.local_model_catalog_empty),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                            supportingContent = {
                                Text(
                                    stringResource(R.string.local_model_catalog_empty_desc),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                )
                            },
                            leadingContent = {
                                Icon(
                                    Icons.Default.CloudDownload,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                )
                            },
                            modifier = Modifier.heightIn(min = 64.dp),
                        )
                    }
                ))
            } else {
                SettingsGroup(title = "", items = currentEntries.map { entry ->
                    {
                        val row = rows.firstOrNull { it.catalogEntryId == entry.id }
                        val (received, rate, remainingMs) = progressFor(entry.id)
                        CatalogEntryItem(
                            entry = entry,
                            row = row,
                            receivedBytes = received,
                            downloadRate = rate,
                            remainingMs = remainingMs,
                            onStartDownload = {
                                scope.launch {
                                    viewModel.localModelDownloadManager.startDownload(
                                        entry = entry,
                                        modelId = entry.id,
                                        nCtx = entry.defaultConfig.contextSize,
                                        temperature = entry.defaultConfig.temperature,
                                        topP = entry.defaultConfig.topP,
                                        maxTokens = entry.defaultConfig.maxTokens,
                                    )
                                }
                            },
                            onCancelDownload = { showCancelConfirm = entry },
                        )
                    }
                })
            }
        }
    }

    showCancelConfirm?.let { entry ->
        AlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            onDismissRequest = { showCancelConfirm = null },
            title = { Text(stringResource(R.string.local_model_download_cancel), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.local_model_download_cancel_text, entry.displayName)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        viewModel.localModelDownloadManager.cancelDownload(entry.id)
                        showCancelConfirm = null
                    }
                }) { Text(stringResource(R.string.cancel)) }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = null }) {
                    Text(stringResource(R.string.local_model_download_pause))
                }
            },
        )
    }
}

@Composable
private fun CatalogEntryItem(
    entry: CatalogEntry,
    row: LocalModelDownloadEntity?,
    receivedBytes: Long,
    downloadRate: Long,
    remainingMs: Long,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
) {
    val status = row?.status
    val percent = if (status == LocalModelStatus.DOWNLOADING) {
        DownloadProgress.percent(receivedBytes, entry.sizeInBytes)
    } else 0
    SettingsItem(
        modifier = Modifier.clickable(enabled = status != LocalModelStatus.DOWNLOADING) {
            if (status == null || status == LocalModelStatus.FAILED) onStartDownload()
        },
        headlineContent = { Text(entry.displayName, fontWeight = FontWeight.Medium) },
        supportingContent = {
            Column {
                @OptIn(ExperimentalLayoutApi::class)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.padding(top = 1.dp),
                ) {
                    if (entry.format == com.newoether.agora.data.LocalChatModelConfig.FORMAT_LITERTLM) {
                        CatalogBadge(label = stringResource(R.string.litertlm_format_badge))
                    }
                    if (entry.capabilities.vision) {
                        CatalogBadge(
                            icon = { Icon(Icons.Default.Visibility, null, modifier = Modifier.size(12.dp)) },
                            label = stringResource(R.string.local_model_badge_vision),
                        )
                    }
                    if (entry.capabilities.tools) {
                        CatalogBadge(label = stringResource(R.string.local_model_badge_tools))
                    }
                    if (entry.capabilities.thinking) {
                        CatalogBadge(label = stringResource(R.string.local_model_badge_thinking))
                    }
                    CatalogBadge(label = ModelCatalogParser.formatDownloadSize(entry.sizeInBytes))
                    if (entry.minRamGb > 0) {
                        CatalogBadge(
                            icon = { Icon(Icons.Default.Memory, null, modifier = Modifier.size(12.dp)) },
                            label = stringResource(R.string.local_model_ram_compact, entry.minRamGb),
                        )
                    }
                }
                if (status == LocalModelStatus.DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (downloadRate > 0) {
                            stringResource(
                                R.string.local_model_download_speed_eta,
                                formatRate(downloadRate),
                                formatRemainingTime(remainingMs)
                            )
                        } else {
                            stringResource(R.string.local_model_downloading, percent)
                        },
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                } else if (status == LocalModelStatus.FAILED) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(R.string.local_model_download_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
        leadingContent = {
            Icon(
                Icons.Default.AutoAwesome,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
        },
        trailingContent = {
            when (status) {
                LocalModelStatus.DOWNLOADING -> IconButton(onClick = onCancelDownload, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, stringResource(R.string.local_model_download_pause), modifier = Modifier.size(18.dp))
                }
                LocalModelStatus.READY -> Icon(
                    Icons.Default.Check,
                    contentDescription = stringResource(R.string.local_model_download_ready),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                else -> TextButton(onClick = onStartDownload) {
                    Text(stringResource(R.string.local_model_download))
                }
            }
        },
    )
}

@Composable
private fun CatalogBadge(label: String, icon: (@Composable () -> Unit)? = null) {
    if (icon == null && label.isBlank()) return
    Surface(
        shape = RoundedCornerShape(5.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon?.invoke()
            if (icon != null && label.isNotBlank()) Spacer(Modifier.width(3.dp))
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun formatRate(bytesPerSecond: Long): String {
    val kbps = bytesPerSecond / 1024.0
    return when {
        kbps >= 1024.0 -> String.format(java.util.Locale.US, "%.1f MB/s", kbps / 1024.0)
        else -> String.format(java.util.Locale.US, "%.0f KB/s", kbps)
    }
}

private fun formatRemainingTime(remainingMs: Long): String {
    if (remainingMs <= 0L) return "—"
    val totalSeconds = remainingMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(java.util.Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(java.util.Locale.US, "%dm %02ds", minutes, seconds)
        else -> String.format(java.util.Locale.US, "%ds", seconds)
    }
}
