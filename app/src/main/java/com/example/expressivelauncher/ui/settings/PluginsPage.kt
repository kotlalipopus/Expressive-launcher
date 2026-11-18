package com.example.expressivelauncher.ui.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.expressivelauncher.R
import com.example.expressivelauncher.data.LauncherPlugins
import com.example.expressivelauncher.data.PluginAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PluginsPage(resumeSignal: Int) {
    val context = LocalContext.current
    val plugins = remember(context) { LauncherPlugins(context) }
    var actions by remember { mutableStateOf<List<PluginAction>?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var launchFailed by remember { mutableStateOf(false) }

    LaunchedEffect(resumeSignal, refresh) {
        actions = withContext(Dispatchers.IO) { plugins.discover() }
    }

    Text(stringResource(R.string.plugins_description), style = MaterialTheme.typography.bodyMedium)
    ActionItem(Icons.Rounded.Refresh, stringResource(R.string.plugins_refresh), null, onClick = {
        launchFailed = false
        refresh++
    })

    if (launchFailed) {
        Text(stringResource(R.string.plugin_launch_failed), color = MaterialTheme.colorScheme.error)
    }

    val installed = actions
    if (installed == null) {
        Text(stringResource(R.string.plugins_loading))
    } else if (installed.isEmpty()) {
        Text(stringResource(R.string.plugins_empty))
    } else {
        installed.forEach { action ->
            androidx.compose.runtime.key(action.component) {
                ToggleItem(
                    Icons.Rounded.Extension,
                    action.label,
                    "${action.provider}\n${action.component.packageName}",
                    action.enabled,
                ) { enabled ->
                    plugins.setEnabled(action, enabled)
                    actions = actions?.map {
                        if (it.component == action.component) it.copy(enabled = enabled) else it
                    }
                }
                if (action.enabled) {
                    ActionItem(
                        Icons.AutoMirrored.Rounded.OpenInNew,
                        stringResource(R.string.plugin_open, action.label),
                        null,
                        onClick = {
                            launchFailed = !plugins.launch(context, action)
                            if (launchFailed) refresh++
                        },
                    )
                }
            }
        }
    }
}
