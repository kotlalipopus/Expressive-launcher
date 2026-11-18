package com.example.expressivelauncher.data

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.awaitCancellation
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

internal data class DesktopPlugin(
    val id: String,
    val name: String,
    val script: String,
    val enabled: Boolean = true,
    val interactive: Boolean = false,
)

internal class DesktopPlugins(context: Context) {
    val preferences: SharedPreferences = context.getSharedPreferences("desktop_plugin", Context.MODE_PRIVATE)

    fun current(): List<DesktopPlugin> {
        val saved = preferences.getString("plugins", null)
        if (saved != null) return runCatching {
            val array = JSONArray(saved)
            List(array.length()) { index ->
                val value = array.getJSONObject(index)
                DesktopPlugin(
                    value.getString("id"), value.getString("name"), value.getString("script"),
                    value.optBoolean("enabled", true), value.optBoolean("interactive", false),
                )
            }
        }.getOrDefault(emptyList())
        val script = preferences.getString("script", null) ?: return emptyList()
        val name = preferences.getString("name", null).orEmpty()
        val previousExample = name == "desktop-pet.js" &&
            MessageDigest.getInstance("SHA-256").digest(script.toByteArray()).contentEquals(exampleDigest)
        return if (previousExample) emptyList() else listOf(DesktopPlugin("legacy", name, script))
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
        synchronized(writeLock) {
            val plugins = current()
            check(plugins.size < LIMIT)
            save(plugins + DesktopPlugin(UUID.randomUUID().toString(), name.take(120), source))
        }
    }

    fun setEnabled(id: String, enabled: Boolean) = update(id) { it.copy(enabled = enabled) }

    fun setInteractive(id: String, interactive: Boolean) = update(id) { it.copy(interactive = interactive) }

    fun remove(id: String) = synchronized(writeLock) {
        save(current().filterNot { it.id == id })
    }

    private fun update(id: String, transform: (DesktopPlugin) -> DesktopPlugin) = synchronized(writeLock) {
        save(current().map { if (it.id == id) transform(it) else it })
    }

    private fun save(plugins: List<DesktopPlugin>) {
        val array = JSONArray()
        for (plugin in plugins) array.put(JSONObject().apply {
            put("id", plugin.id)
            put("name", plugin.name)
            put("script", plugin.script)
            put("enabled", plugin.enabled)
            put("interactive", plugin.interactive)
        })
        preferences.edit().putString("plugins", array.toString()).remove("name").remove("script").apply()
    }

    companion object {
        const val LIMIT = 3
        private val writeLock = Any()
        private val exampleDigest = "8466eb0c76492a347a279c8dcb4611bdbae6000fca6919d16e8a55e90a7a3fe4"
            .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

@Composable
internal fun desktopPlugins(store: DesktopPlugins): State<List<DesktopPlugin>> =
    produceState(androidx.compose.runtime.remember(store) { store.current() }, store) {
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
