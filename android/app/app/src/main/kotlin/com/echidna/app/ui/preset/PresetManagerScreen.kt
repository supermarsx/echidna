package com.echidna.app.ui.preset

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.echidna.app.model.Preset
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PresetManagerScreen(
    viewModel: PresetManagerViewModel,
    onImportRequest: () -> Unit,
    onExportResult: (String) -> Unit,
    onShareResult: (String) -> Unit
) {
    val presets by viewModel.presets.collectAsStateWithLifecycle()
    val active by viewModel.activePreset.collectAsStateWithLifecycle()
    val defaultId by viewModel.defaultPresetId.collectAsStateWithLifecycle()

    var showCreateDialog by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<Preset?>(null) }
    var query by remember { mutableStateOf("") }

    // Active preset pinned to the top, then a name-stable order for the rest. Search filters
    // by name, description and tags so it stays useful once the list grows.
    val visible = remember(presets, active.id, query) {
        presets
            .filter { it.matches(query) }
            .sortedByDescending { it.id == active.id }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.size(4.dp))
        PresetHeader(
            count = presets.size,
            onImport = onImportRequest,
            onExportAll = { onExportResult(viewModel.exportAllPresets()) }
        )

        Button(
            onClick = { showCreateDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("New Preset")
        }

        // A search field earns its space only once there are enough presets to scan.
        if (presets.size > 3) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Filled.Clear, contentDescription = "Clear search")
                        }
                    }
                },
                placeholder = { Text("Search presets") }
            )
        }

        if (visible.isEmpty()) {
            EmptyState(
                searching = query.isNotBlank(),
                onCreate = { showCreateDialog = true }
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)
            ) {
                items(visible, key = { it.id }) { preset ->
                    PresetCard(
                        preset = preset,
                        isActive = preset.id == active.id,
                        isDefault = preset.id == defaultId,
                        onActivate = { viewModel.selectPreset(preset.id) },
                        onDuplicate = { viewModel.duplicatePreset(preset.id) },
                        onDelete = { pendingDelete = preset },
                        onRename = { showRenameDialog = preset.id },
                        onSetDefault = { viewModel.setDefaultPreset(preset.id) },
                        onShare = { viewModel.sharePreset(preset.id)?.let(onShareResult) }
                    )
                }
            }
        }
    }

    if (showCreateDialog) {
        PresetCreateDialog(
            presets = presets,
            onCreate = { name, description, baseId ->
                viewModel.createPreset(name, description, baseId)
                showCreateDialog = false
            },
            onDismiss = { showCreateDialog = false }
        )
    }

    val renameId = showRenameDialog
    if (renameId != null) {
        PresetRenameDialog(
            currentName = presets.firstOrNull { it.id == renameId }?.name.orEmpty(),
            onRename = { newName ->
                viewModel.renamePreset(renameId, newName)
                showRenameDialog = null
            },
            onDismiss = { showRenameDialog = null }
        )
    }

    val deleteTarget = pendingDelete
    if (deleteTarget != null) {
        DeleteConfirmDialog(
            preset = deleteTarget,
            onConfirm = {
                viewModel.deletePreset(deleteTarget.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

/** Case-insensitive match of a preset against a search query (name, description, tags). */
private fun Preset.matches(query: String): Boolean {
    if (query.isBlank()) return true
    val q = query.trim().lowercase()
    return name.lowercase().contains(q) ||
        description?.lowercase()?.contains(q) == true ||
        tags.any { it.lowercase().contains(q) }
}

@Composable
private fun PresetHeader(
    count: Int,
    onImport: () -> Unit,
    onExportAll: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Preset Manager", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                text = if (count == 1) "1 preset" else "$count presets",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        TooltipIconButton(tooltip = "Import a preset (.json)", onClick = onImport) {
            Icon(Icons.Filled.FileDownload, contentDescription = "Import preset")
        }
        Spacer(Modifier.width(4.dp))
        TooltipIconButton(tooltip = "Export & share all presets", onClick = onExportAll) {
            Icon(Icons.Filled.FileUpload, contentDescription = "Export all presets")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(
    preset: Preset,
    isActive: Boolean,
    isDefault: Boolean,
    onActivate: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onSetDefault: () -> Unit,
    onShare: () -> Unit
) {
    val accent = accentFor(preset.id)
    var menuOpen by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        border = if (isActive) BorderStroke(2.dp, accent) else null,
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                accent.copy(alpha = 0.10f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                // A stable per-preset colour + initial gives each preset a quick visual identity.
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .background(accent.copy(alpha = 0.20f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = preset.name.firstOrNull()?.uppercase() ?: "?",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = preset.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (isActive) StatusPill("ACTIVE", GreenAccent)
                        if (isDefault) StatusPill("DEFAULT", accent, leadingStar = true)
                    }
                    preset.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                // Secondary actions live in a tidy overflow menu instead of a crowded row.
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { menuOpen = false; onRename() }
                        )
                        DropdownMenuItem(
                            text = { Text("Duplicate") },
                            leadingIcon = { Icon(Icons.Filled.ContentCopy, contentDescription = null) },
                            onClick = { menuOpen = false; onDuplicate() }
                        )
                        DropdownMenuItem(
                            text = { Text(if (isDefault) "Default preset" else "Set as default") },
                            leadingIcon = { Icon(Icons.Filled.Star, contentDescription = null) },
                            enabled = !isDefault,
                            onClick = { menuOpen = false; onSetDefault() }
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { menuOpen = false; onShare() }
                        )
                        Divider()
                        DropdownMenuItem(
                            text = { Text("Delete") },
                            leadingIcon = {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = null,
                                    tint = if (isActive) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                                )
                            },
                            // t5-e1: the active preset can't be deleted — guard it here too.
                            enabled = !isActive,
                            colors = androidx.compose.material3.MenuDefaults.itemColors(
                                textColor = MaterialTheme.colorScheme.error
                            ),
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }

            if (preset.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    preset.tags.forEach { tag -> TagChip(tag) }
                }
            }

            Text(
                text = presetSummary(preset),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Primary action: prominent "Activate", or a clear non-actionable "Active" state.
            if (isActive) {
                FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        disabledContainerColor = GreenAccent.copy(alpha = 0.18f),
                        disabledContentColor = GreenAccent
                    )
                ) {
                    Icon(Icons.Filled.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Active")
                }
            } else {
                Button(onClick = onActivate, modifier = Modifier.fillMaxWidth()) {
                    Text("Activate")
                }
            }
        }
    }
}

/** Compact one-line summary of a preset's key settings. */
private fun presetSummary(preset: Preset): String {
    val enabled = preset.modules.count { it.enabled }
    val effects = when (enabled) {
        0 -> "no effects on"
        1 -> "1 effect on"
        else -> "$enabled effects on"
    }
    return "${preset.latencyMode.label} · ${preset.dryWet}% wet · $effects"
}

@Composable
private fun StatusPill(text: String, accent: Color, leadingStar: Boolean = false) {
    Surface(
        color = accent.copy(alpha = 0.20f),
        contentColor = accent,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            if (leadingStar) {
                Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(11.dp))
            }
            Text(text = text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun EmptyState(searching: Boolean, onCreate: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.Inbox,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = if (searching) "No presets match your search" else "No presets yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = if (searching) {
                "Try a different name or tag."
            } else {
                "Create your first voice preset to get started."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (!searching) {
            Button(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("New Preset")
            }
        }
    }
}

/** An icon button whose purpose is revealed by a tooltip on hover / long-press. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TooltipIconButton(
    tooltip: String,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val tooltipState = rememberTooltipState()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = tooltipState
    ) {
        FilledTonalIconButton(onClick = onClick) { content() }
    }
}

/**
 * A preset tag rendered as a chip. Tapping (or long-pressing) it shows a tooltip that
 * expands the abbreviation into its full meaning, so short badges stay compact but remain
 * discoverable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagChip(tag: String) {
    val tooltipState = rememberTooltipState()
    val scope = rememberCoroutineScope()
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tagExplanation(tag)) } },
        state = tooltipState
    ) {
        AssistChip(
            onClick = { scope.launch { tooltipState.show() } },
            label = { Text(tag) }
        )
    }
}

/** Maps a short preset tag to a human-readable explainer shown in its tooltip. */
private fun tagExplanation(tag: String): String = when (tag.uppercase()) {
    "NAT" -> "NAT — Natural: subtle, natural-sounding voice"
    "LL" -> "LL — Low-Latency: minimal delay (~15 ms), best for live calls"
    "HQ" -> "HQ — High-Quality: best fidelity (~30 ms)"
    "FX" -> "FX — Effect: bold, creative voice transformation"
    else -> tag
}

// Semantic accent reused from the app's modern status styling (t5-e6).
private val GreenAccent = Color(0xFF4CAF50)

// A small palette that gives each preset a stable, distinct colour identity.
private val PresetAccents = listOf(
    Color(0xFF42A5F5), // blue
    Color(0xFF66BB6A), // green
    Color(0xFFAB47BC), // purple
    Color(0xFFFFA726), // orange
    Color(0xFF26C6DA), // cyan
    Color(0xFFEC407A), // pink
    Color(0xFF7E57C2), // deep purple
    Color(0xFF9CCC65)  // lime
)

/** Deterministically maps a preset id to one of [PresetAccents]. */
private fun accentFor(id: String): Color =
    PresetAccents[(id.hashCode() and 0x7fffffff) % PresetAccents.size]

@Composable
private fun DeleteConfirmDialog(
    preset: Preset,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        icon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("Delete preset?") },
        text = { Text("\"${preset.name}\" will be permanently removed. This can't be undone.") }
    )
}

@Composable
private fun PresetCreateDialog(
    presets: List<Preset>,
    onCreate: (String, String?, String?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var baseId by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onCreate(name, description.ifBlank { null }, baseId) }, enabled = name.isNotBlank()) {
                Text("Create")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Create Preset") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Description") })
                Text(text = "Base on:")
                LazyColumn(modifier = Modifier.heightIn(max = 200.dp)) {
                    item {
                        TextButton(onClick = { baseId = null }) { Text(if (baseId == null) "• Empty" else "Empty") }
                    }
                    items(presets) { preset ->
                        TextButton(onClick = { baseId = preset.id }) {
                            val prefix = if (baseId == preset.id) "• " else ""
                            Text("$prefix${preset.name}")
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PresetRenameDialog(
    currentName: String,
    onRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(currentName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank()) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        title = { Text("Rename Preset") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }) }
    )
}
