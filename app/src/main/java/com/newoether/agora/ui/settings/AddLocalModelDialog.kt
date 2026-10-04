package com.newoether.agora.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.LocalChatModelConfig
import com.newoether.agora.ui.components.clearFocusOnTap

/**
 * Registration dialog for an imported local model (GGUF or .litertlm). The format, decided
 * by the import magic-bytes check, drives which fields appear: .litertlm shows backend /
 * vision / top-K and hides the GGUF vision projector picker.
 */
@Composable
fun AddLocalModelDialog(
    importedFormat: String,
    importedPath: String,
    mmprojPickedUri: String?,
    onMmprojPickedUriConsumed: () -> Unit,
    isModelIdTaken: (String) -> Boolean,
    mmprojLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Array<String>, android.net.Uri?>,
    onConfirm: (LocalChatModelConfig) -> Unit,
    onDismiss: (mmprojPath: String) -> Unit,
) {
    val isLitertlm = importedFormat == LocalChatModelConfig.FORMAT_LITERTLM
    var modelId by remember { mutableStateOf("") }; var modelAlias by remember { mutableStateOf("") }
    var addMmprojPath by remember { mutableStateOf("") }
    var nCtx by remember { mutableStateOf(if (isLitertlm) LocalChatModelConfig.LITERTLM_DEFAULT_NCTX.toString() else "16384") }
    var temperature by remember { mutableStateOf("0.7") }; var topP by remember { mutableStateOf("0.9") }; var maxTokens by remember { mutableStateOf("1024") }
    var addBackend by remember { mutableStateOf(LocalChatModelConfig.BACKEND_AUTO) }
    var addTopK by remember { mutableStateOf("40") }
    var addVision by remember { mutableStateOf(false) }
    var idError by remember { mutableStateOf<String?>(null) }; var formError by remember { mutableStateOf<String?>(null) }
    val idRegex = remember { Regex("^[a-z0-9._-]+\$") }

    fun deleteFilesAsync(path: String?) {
        path?.takeIf(String::isNotBlank)?.let { java.io.File(it).delete() }
    }

    LaunchedEffect(mmprojPickedUri) {
        mmprojPickedUri?.let { newPath ->
            deleteFilesAsync(addMmprojPath)
            addMmprojPath = newPath
            onMmprojPickedUriConsumed()
        }
    }

    AlertDialog(
        modifier = Modifier.clearFocusOnTap(),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        onDismissRequest = {
            onDismiss(addMmprojPath)
        },
        title = { Text(stringResource(R.string.add_local_chat_model), fontWeight = FontWeight.Bold) },
        text = { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            OutlinedTextField(value = modelId, onValueChange = { modelId = it; idError = null }, label = { Text(stringResource(R.string.model_id_label)) }, supportingText = if (idError != null) {{ Text(idError!!, color = MaterialTheme.colorScheme.error) }} else null, isError = idError != null, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = modelAlias, onValueChange = { modelAlias = it }, label = { Text(stringResource(R.string.model_alias_label)) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            if (isLitertlm) {
                Text(stringResource(R.string.litertlm_format_badge), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf(
                        LocalChatModelConfig.BACKEND_AUTO to R.string.litertlm_backend_auto,
                        LocalChatModelConfig.BACKEND_CPU to R.string.litertlm_backend_cpu,
                        LocalChatModelConfig.BACKEND_GPU to R.string.litertlm_backend_gpu,
                    ).forEach { (value, labelRes) ->
                        FilterChip(
                            selected = addBackend == value,
                            onClick = { addBackend = value },
                            label = { Text(stringResource(labelRes), style = MaterialTheme.typography.labelMedium) },
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
                if (addBackend == LocalChatModelConfig.BACKEND_GPU || addBackend == LocalChatModelConfig.BACKEND_AUTO) {
                    val bundleSizeGb = java.io.File(importedPath).length() / (1024.0 * 1024.0 * 1024.0)
                    if (bundleSizeGb > 3.0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(stringResource(R.string.litertlm_gpu_memory_warning), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = addVision, onCheckedChange = { addVision = it })
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(stringResource(R.string.litertlm_vision_capable), style = MaterialTheme.typography.bodyMedium)
                        Text(stringResource(R.string.litertlm_vision_capable_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = addTopK, onValueChange = { addTopK = it }, label = { Text(stringResource(R.string.litertlm_top_k)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = nCtx, onValueChange = { nCtx = it }, label = { Text(stringResource(R.string.local_ctx_size)) }, supportingText = if (isLitertlm) {{ Text(stringResource(R.string.litertlm_context_hint), style = MaterialTheme.typography.bodySmall) }} else null, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            if (!isLitertlm) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val hasMmproj = addMmprojPath.isNotBlank()
                    OutlinedButton(onClick = { mmprojLauncher.launch(arrayOf("*/*")) }, shape = RoundedCornerShape(16.dp), modifier = Modifier.weight(1f), colors = ButtonDefaults.outlinedButtonColors(contentColor = if (hasMmproj) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(18.dp)); Spacer(modifier = Modifier.width(6.dp)); Text(if (hasMmproj) addMmprojPath.split("/").lastOrNull() ?: "" else stringResource(R.string.local_mmproj_path_label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (hasMmproj) {
                        Spacer(modifier = Modifier.width(8.dp))
                        TextButton(onClick = {
                            val removedPath = addMmprojPath
                            addMmprojPath = ""
                            deleteFilesAsync(removedPath)
                        }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = temperature, onValueChange = { temperature = it }, label = { Text(stringResource(R.string.local_temperature)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = topP, onValueChange = { topP = it }, label = { Text(stringResource(R.string.local_top_p)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(value = maxTokens, onValueChange = { maxTokens = it }, label = { Text(stringResource(R.string.local_max_tokens)) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            formError?.let { Spacer(modifier = Modifier.height(8.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }},
        confirmButton = { TextButton(onClick = {
            val id = modelId.trim(); idError = null; formError = null
            if (id.isBlank()) { idError = "ID is required"; return@TextButton }
            if (!idRegex.matches(id)) { idError = "Only a-z, 0-9, . _ - allowed"; return@TextButton }
            if (isModelIdTaken(id)) { idError = "Already in use"; return@TextButton }
            val n = nCtx.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Context size must be positive"; return@TextButton }
            val t = temperature.toFloatOrNull()?.takeIf { it in 0f..2f } ?: run { formError = "Temperature must be 0–2"; return@TextButton }
            val p = topP.toFloatOrNull()?.takeIf { it in 0f..1f } ?: run { formError = "Top P must be 0–1"; return@TextButton }
            val m = maxTokens.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Max tokens must be positive"; return@TextButton }
            val k = if (isLitertlm) {
                addTopK.toIntOrNull()?.takeIf { it > 0 } ?: run { formError = "Top K must be positive"; return@TextButton }
            } else 40
            if (m > n) { formError = "Max tokens must not exceed context size"; return@TextButton }
            onConfirm(
                LocalChatModelConfig(
                    modelId = id, alias = modelAlias.ifBlank { id }, localFilePath = importedPath,
                    mmprojPath = if (isLitertlm) "" else addMmprojPath.trim(),
                    nCtx = n, temperature = t, topP = p, maxTokens = m,
                    format = importedFormat, backend = addBackend, topK = k, visionCapable = addVision,
                )
            )
        }) { Text(stringResource(R.string.add)) } },
        dismissButton = { TextButton(onClick = {
            onDismiss(addMmprojPath)
        }) { Text(stringResource(R.string.cancel)) } }
    )
}
