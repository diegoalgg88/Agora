package com.newoether.agora.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Store
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.newoether.agora.R
import com.newoether.agora.data.RegistrySkillEntry
import com.newoether.agora.data.SkillMarketplace
import com.newoether.agora.data.curatedSkillMarketplaces
import com.newoether.agora.ui.motion.MotionAwareCircularProgressIndicator as CircularProgressIndicator
import com.newoether.agora.viewmodel.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSkillMarketplacePage(
    viewModel: ChatViewModel,
    onBack: () -> Unit,
) {
    var selectedMarketplace by remember { mutableStateOf<SkillMarketplace?>(null) }
    var loadedSkills by remember { mutableStateOf<List<RegistrySkillEntry>?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var installedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var installInFlight by remember { mutableStateOf<String?>(null) }

    fun refreshInstalled() {
        scope.launch {
            installedIds = withContext(Dispatchers.IO) {
                runCatching {
                    viewModel.skillManager.listFiles()
                        .map { it.name.removeSuffix(".md") }
                        .toSet()
                }.getOrDefault(emptySet())
            }
        }
    }

    LaunchedEffect(Unit) { refreshInstalled() }

    LaunchedEffect(selectedMarketplace) {
        val marketplace = selectedMarketplace
        if (marketplace != null) {
            isLoading = true
            errorMessage = null
            loadedSkills = null
            searchQuery = ""
            try {
                val result = withContext(Dispatchers.IO) {
                    viewModel.skillManager.browseMarketplace(marketplace)
                }
                result.onSuccess { loadedSkills = it }
                    .onFailure {
                        errorMessage = it.localizedMessage
                            ?: context.getString(R.string.skills_marketplace_load_failed)
                    }
            } catch (e: Exception) {
                errorMessage = e.localizedMessage
                    ?: context.getString(R.string.skills_marketplace_load_failed)
            } finally {
                isLoading = false
            }
        }
    }

    CollapsingSettingsScaffold(
        title = if (selectedMarketplace == null) {
            stringResource(R.string.skills_marketplaces_title)
        } else {
            stringResource(R.string.skills_marketplace_available)
        },
        onBack = {
            if (selectedMarketplace != null) {
                selectedMarketplace = null
                loadedSkills = null
            } else {
                onBack()
            }
        },
        scrollState = rememberScrollState(),
    ) {
        SettingsGroupColumn {
            if (selectedMarketplace == null) {
                SettingsGroup(
                    title = stringResource(R.string.skills_marketplace_curated),
                    items = curatedSkillMarketplaces.map { marketplace ->
                        {
                            SettingsItem(
                                headlineContent = {
                                    Text(
                                        marketplace.name,
                                        fontWeight = FontWeight.Medium,
                                    )
                                },
                                supportingContent = {
                                    Text(
                                        "${marketplace.owner}/${marketplace.repo}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                },
                                leadingContent = {
                                    Icon(
                                        Icons.Default.Store,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().clickable {
                                    selectedMarketplace = marketplace
                                },
                            )
                        }
                    }
                )
            } else {
                val skills = loadedSkills
                if (!isLoading && errorMessage == null && !skills.isNullOrEmpty()) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text(stringResource(R.string.skills_marketplace_search)) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                val filteredSkills = remember(skills, searchQuery) {
                    val query = searchQuery.trim().lowercase()
                    if (query.isEmpty()) {
                        skills.orEmpty()
                    } else {
                        skills.orEmpty().filter {
                            it.id.lowercase().contains(query) ||
                                it.description.lowercase().contains(query) ||
                                it.sourceName.lowercase().contains(query)
                        }
                    }
                }
                SettingsGroup(
                    title = stringResource(R.string.skills_marketplace_installable),
                    items = buildList {
                        if (isLoading) {
                            add {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .heightIn(min = 64.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                }
                            }
                        } else if (errorMessage != null) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.skills_marketplace_error),
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            errorMessage!!,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        } else if (skills?.isEmpty() == true) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.skills_marketplace_empty),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        } else if (filteredSkills.isEmpty()) {
                            add {
                                SettingsItem(
                                    headlineContent = {
                                        Text(
                                            stringResource(R.string.skills_marketplace_search_empty),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                )
                            }
                        } else {
                            filteredSkills.forEach { skill ->
                                add {
                                    val alreadyInstalled = skill.id in installedIds
                                    SettingsItem(
                                        headlineContent = {
                                            Text(
                                                skill.id,
                                                fontWeight = FontWeight.Medium,
                                            )
                                        },
                                        supportingContent = {
                                            Column {
                                                Text(
                                                    skill.description,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                                if (skill.requiresSandbox) {
                                                    Text(
                                                        stringResource(R.string.skills_requires_shell),
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.tertiary,
                                                    )
                                                }
                                                Text(
                                                    skill.sourceName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                )
                                            }
                                        },
                                        leadingContent = {
                                            Icon(
                                                Icons.Default.CloudDownload,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.primary,
                                            )
                                        },
                                        trailingContent = when {
                                            installInFlight == skill.id -> {
                                                {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(20.dp),
                                                        strokeWidth = 2.dp,
                                                    )
                                                }
                                            }
                                            alreadyInstalled -> {
                                                {
                                                    Icon(
                                                        Icons.Default.Check,
                                                        contentDescription = stringResource(
                                                            R.string.skills_marketplace_installed,
                                                        ),
                                                        tint = MaterialTheme.colorScheme.primary,
                                                    )
                                                }
                                            }
                                            else -> null
                                        },
                                        modifier = Modifier.fillMaxWidth().clickable(
                                            enabled = !alreadyInstalled && installInFlight == null,
                                        ) {
                                            installInFlight = skill.id
                                            scope.launch {
                                                try {
                                                    val result = withContext(Dispatchers.IO) {
                                                        viewModel.skillManager
                                                            .installFromRegistryEntry(skill)
                                                            .getOrThrow()
                                                    }
                                                    viewModel.emitSnackbar(
                                                        if (result.file.bundledFileCount > 0) {
                                                            context.getString(
                                                                R.string.skills_installed_with_bundled,
                                                                result.file.name,
                                                                result.file.bundledFileCount,
                                                            )
                                                        } else {
                                                            context.getString(
                                                                R.string.skills_installed,
                                                                result.file.name,
                                                            )
                                                        },
                                                    )
                                                    refreshInstalled()
                                                } catch (e: Exception) {
                                                    viewModel.emitSnackbar(
                                                        context.getString(
                                                            R.string.skills_install_failed,
                                                            skill.id,
                                                            e.localizedMessage ?: "",
                                                        ),
                                                    )
                                                } finally {
                                                    installInFlight = null
                                                }
                                            }
                                        },
                                    )
                                }
                            }
                        }
                    }
                )
            }
        }
    }
}
