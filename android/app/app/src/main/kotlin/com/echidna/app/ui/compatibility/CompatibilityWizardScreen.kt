package com.echidna.app.ui.compatibility

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun CompatibilityWizardScreen(viewModel: CompatibilityWizardViewModel, onFinish: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(text = "Compatibility Wizard", style = MaterialTheme.typography.headlineSmall)
        if (state.running) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Running device probes…")
                    CircularProgressIndicator()
                }
            }
        }
        state.result?.let { result ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "SELinux: ${result.selinuxStatus}")
                    result.audioStack.forEach { stack ->
                        Text(text = "${stack.name}: ${if (stack.supported) "Supported" else "Unsupported"} — ${stack.message}")
                    }
                    Text(text = "Notes")
                    result.notes.forEach { Text(text = "• $it") }
                }
            }
        }
        Button(
            onClick = {
                if (state.result == null) {
                    viewModel.runProbes()
                } else {
                    onFinish()
                }
            },
            enabled = !state.running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    state.running -> "Probing…"
                    state.result == null -> "Retry"
                    else -> "Finish"
                }
            )
        }
    }
}
