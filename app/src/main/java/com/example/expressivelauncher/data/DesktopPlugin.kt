package com.example.expressivelauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.awaitCancellation

internal data class DesktopPlugin(val name: String, val script: String)

internal class DesktopPlugins(private val context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("desktop_plugin", Context.MODE_PRIVATE)

    fun current(): DesktopPlugin? {
        val script = preferences.getString("script", null) ?: return null
        return DesktopPlugin(preferences.getString("name", null).orEmpty(), script)
    }

    fun install(name: String, input: java.io.InputStream) {
        require(name.endsWith(".js", ignoreCase = true))
        val buffer = ByteArray(64 * 1024 + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count <= 0) break
            size += count
        }
        require(size in 1 until buffer.size)
        val source = Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(buffer, 0, size))
            .toString().removePrefix("\uFEFF")
        require(source.isNotBlank())
        preferences.edit().putString("name", name.take(120)).putString("script", source).apply()
    }

    fun installExample() {
        context.assets.open("desktop-pet.js").use { install("desktop-pet.js", it) }
    }

    fun remove() {
        preferences.edit().remove("name").remove("script").apply()
    }
}

@Composable
internal fun activeDesktopPlugin(store: DesktopPlugins): State<DesktopPlugin?> =
    produceState(store.current(), store) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            value = store.current()
        }
        store.preferences.registerOnSharedPreferenceChangeListener(listener)
        value = store.current()
        try {
            awaitCancellation()
        } finally {
            store.preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
