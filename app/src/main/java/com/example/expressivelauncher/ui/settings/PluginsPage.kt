package com.example.expressivelauncher.ui.settings

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.expressivelauncher.R
import com.example.expressivelauncher.data.DesktopPlugins
import com.example.expressivelauncher.data.desktopPlugins
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PluginsPage() {
    val context = LocalContext.current
    val store = remember(context) { DesktopPlugins(context) }
    val plugins by desktopPlugins(store)
    var failed by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?: uri.lastPathSegment.orEmpty()
                        requireNotNull(resolver.openInputStream(uri)).use { store.install(name, it) }
                    }
                    failed = false
                } catch (exception: CancellationException) {
                    throw exception
                } catch (_: Exception) {
                    failed = true
                } finally {
                    importing = false
                }
            }
        }
    }

    Text(stringResource(R.string.desktop_plugins_description), style = MaterialTheme.typography.bodyMedium)
    if (importing) {
        Text(stringResource(R.string.plugins_loading))
        return
    }
    if (plugins.size < DesktopPlugins.LIMIT) {
        ActionItem(Icons.Rounded.FileOpen, stringResource(R.string.desktop_plugin_import), null, onClick = {
            picker.launch(arrayOf("*/*"))
        })
    } else {
        Text(stringResource(R.string.desktop_plugin_limit, DesktopPlugins.LIMIT))
    }
    for (plugin in plugins) key(plugin.id) {
        ToggleItem(Icons.Rounded.Extension, plugin.name, null, plugin.enabled) {
            store.setEnabled(plugin.id, it)
        }
        ToggleItem(
            Icons.Rounded.TouchApp,
            stringResource(R.string.desktop_plugin_touch),
            stringResource(R.string.desktop_plugin_touch_hint),
            plugin.interactive,
            enabled = plugin.enabled,
        ) { store.setInteractive(plugin.id, it) }
        ActionItem(Icons.Rounded.Delete, stringResource(R.string.desktop_plugin_remove), plugin.name, onClick = {
            store.remove(plugin.id)
        })
    }
    if (failed) Text(stringResource(R.string.desktop_plugin_error), color = MaterialTheme.colorScheme.error)
}
