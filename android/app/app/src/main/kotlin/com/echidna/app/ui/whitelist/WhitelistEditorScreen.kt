package com.echidna.app.ui.whitelist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.Preset

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WhitelistEditorScreen(viewModel: WhitelistEditorViewModel) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    var showAddField by remember { mutableStateOf(false) }
    var newPackageName by remember { mutableStateOf("") }
    var selectedCategory by rememberSaveable { mutableStateOf<String?>(null) }

    val categoryFilters = remember(entries) {
        entries.mapNotNull { it.categoryLabel }.distinct().sorted()
    }
    val filtered = remember(entries, query, selectedCategory) {
        val q = query.trim().lowercase()
        entries.filter { entry ->
            val categoryMatches = selectedCategory == null || entry.categoryLabel == selectedCategory
            val queryMatches = q.isBlank() ||
                entry.label.lowercase().contains(q) ||
                entry.packageName.lowercase().contains(q) ||
                entry.categoryLabel?.lowercase()?.contains(q) == true ||
                entry.suggestionReason?.lowercase()?.contains(q) == true
            categoryMatches && queryMatches
        }
    }
    val enabledApps = remember(filtered) { filtered.filter { it.enabled } }
    val suggestedApps = remember(filtered) { filtered.filter { !it.enabled && it.suggested } }
    val otherApps = remember(filtered) { filtered.filter { !it.enabled && !it.suggested } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "Per-App Whitelist", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Choose which apps the voice engine processes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            OutlinedButton(onClick = { viewModel.refresh() }, enabled = !loading) {
                Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (loading) "Loading..." else "Reload")
            }
        }

        OutlinedTextField(
            value = query,
            onValueChange = viewModel::setQuery,
            label = { Text("Search apps") },
            placeholder = { Text("Name, package, or category") },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        CategoryFilterRow(
            categories = categoryFilters,
            selectedCategory = selectedCategory,
            onSelected = { selectedCategory = it }
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${enabledApps.size} enabled | ${suggestedApps.size} suggested",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = { showAddField = !showAddField }) {
                Icon(
                    imageVector = if (showAddField) Icons.Filled.Clear else Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.size(6.dp))
                Text(if (showAddField) "Cancel" else "Add by package")
            }
        }

        AnimatedVisibility(visible = showAddField) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = newPackageName,
                    onValueChange = { newPackageName = it },
                    label = { Text("Package name") },
                    placeholder = { Text("com.example.app") },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                OutlinedButton(
                    onClick = {
                        viewModel.addApp(newPackageName.trim())
                        newPackageName = ""
                        showAddField = false
                    },
                    enabled = newPackageName.isNotBlank()
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Add")
                }
            }
        }

        if (filtered.isEmpty()) {
            EmptyState(loading = loading, hasQuery = query.isNotBlank())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                appSection(
                    title = "Enabled",
                    subtitle = "Apps the engine will process",
                    apps = enabledApps,
                    presets = presets,
                    viewModel = viewModel
                )
                appSection(
                    title = "Suggested",
                    subtitle = "Common voice, calls, social, games, and streaming apps",
                    apps = suggestedApps,
                    presets = presets,
                    viewModel = viewModel
                )
                appSection(
                    title = "Installed apps",
                    subtitle = "Launchable apps visible on this device",
                    apps = otherApps,
                    presets = presets,
                    viewModel = viewModel
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CategoryFilterRow(
    categories: List<String>,
    selectedCategory: String?,
    onSelected: (String?) -> Unit
) {
    if (categories.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedCategory == null,
            onClick = { onSelected(null) },
            label = { Text("All") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedCategory == category,
                onClick = { onSelected(category) },
                label = { Text(category) }
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appSection(
    title: String,
    subtitle: String?,
    apps: List<AppEntry>,
    presets: List<Preset>,
    viewModel: WhitelistEditorViewModel
) {
    if (apps.isEmpty()) return
    item(key = "header-$title") {
        Column(modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    text = apps.size.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    items(apps, key = { it.packageName }) { entry ->
        AppWhitelistCard(
            entry = entry,
            presets = presets,
            onToggle = { viewModel.toggleApp(entry.packageName, it) },
            onPresetChange = { viewModel.setAppPreset(entry.packageName, it) },
            onRemove = { viewModel.removeApp(entry.packageName) }
        )
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun AppWhitelistCard(
    entry: AppEntry,
    presets: List<Preset>,
    onToggle: (Boolean) -> Unit,
    onPresetChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val boundPresetName = presets.firstOrNull { it.id == entry.presetId }?.name ?: "Default"

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (entry.enabled) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AppIcon(packageName = entry.packageName)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.label,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = entry.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    EntryMetadataChips(entry)
                    if (!entry.installed) {
                        Text(
                            text = "Not installed on this device",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(checked = entry.enabled, onCheckedChange = onToggle)
            }

            AnimatedVisibility(visible = entry.enabled) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        AssistChip(
                            onClick = { expanded = true },
                            label = { Text("Preset: $boundPresetName") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Apps,
                                    contentDescription = null,
                                    modifier = Modifier.size(AssistChipDefaults.IconSize)
                                )
                            }
                        )
                        DropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Default") },
                                onClick = {
                                    onPresetChange("")
                                    expanded = false
                                }
                            )
                            presets.forEach { preset ->
                                DropdownMenuItem(
                                    text = { Text(preset.name) },
                                    onClick = {
                                        onPresetChange(preset.id)
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                    TextButton(onClick = onRemove) {
                        Text("Remove", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun EntryMetadataChips(entry: AppEntry) {
    val chips = buildList {
        entry.categoryLabel?.let { add(it) }
        if (entry.suggested) add("Suggested")
        if (entry.heuristic) add("Heuristic")
        if (entry.presetId.isNotBlank()) add("Preset bound")
    }
    if (chips.isEmpty()) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp)
    ) {
        chips.distinct().forEach { chip ->
            Surface(
                color = if (chip == "Suggested") {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
                },
                contentColor = if (chip == "Suggested") {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = chip,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }
        }
    }
    entry.suggestionReason?.takeIf { entry.heuristic }?.let { reason ->
        Text(
            text = reason,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

/**
 * Shows the app's real launcher icon when it is installed, falling back to a neutral placeholder
 * badge otherwise (e.g. whitelisted-but-not-installed or suggested apps).
 */
@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val bitmap: ImageBitmap? = remember(packageName) {
        runCatching { context.packageManager.getApplicationIcon(packageName).toImageBitmap() }
            .getOrNull()
    }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun EmptyState(loading: Boolean, hasQuery: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            shape = CircleShape,
            modifier = Modifier.padding(8.dp)
        ) {
            Icon(
                imageVector = if (hasQuery) Icons.Filled.Search else Icons.Filled.Apps,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(16.dp)
                    .size(32.dp)
            )
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = when {
                loading -> "Loading installed apps..."
                hasQuery -> "No apps match your search"
                else -> "No apps found"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** Rasterises any app-icon [Drawable] (incl. adaptive icons) into a Compose [ImageBitmap]. */
private fun Drawable.toImageBitmap(): ImageBitmap {
    if (this is BitmapDrawable) {
        bitmap?.let { return it.asImageBitmap() }
    }
    val width = intrinsicWidth.takeIf { it > 0 } ?: 108
    val height = intrinsicHeight.takeIf { it > 0 } ?: 108
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    setBounds(0, 0, canvas.width, canvas.height)
    draw(canvas)
    return bmp.asImageBitmap()
}
