package com.example.expressivelauncher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class PluginAction(
    val component: ComponentName,
    val label: String,
    val provider: String,
    internal val signingIdentity: String,
    val enabled: Boolean,
)

/** Versioned discovery and explicit, user-initiated dispatch to another Android application. */
class LauncherPlugins(context: Context) {
    private val context = context.applicationContext
    private val packages = this.context.packageManager
    private val approvals = this.context.getSharedPreferences("plugin_approvals", Context.MODE_PRIVATE)

    fun discover(): List<PluginAction> {
        val identities = mutableMapOf<String, String?>()
        return packages.queryIntentActivities(Intent(ACTION), PackageManager.GET_META_DATA)
            .mapNotNull { result ->
                val activity = result.activityInfo ?: return@mapNotNull null
                if (!activity.exported || !activity.permission.isNullOrEmpty() ||
                    activity.metaData?.getInt(API_VERSION) != 1 ||
                    activity.packageName == context.packageName
                ) return@mapNotNull null

                val identity = identities.getOrPut(activity.packageName) {
                    signingIdentity(activity.packageName)
                } ?: return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name)
                PluginAction(
                    component = component,
                    label = result.loadLabel(packages).toString(),
                    provider = activity.applicationInfo.loadLabel(packages).toString(),
                    signingIdentity = identity,
                    enabled = approvals.getString(component.flattenToString(), null) == identity,
                )
            }
            .distinctBy { it.component }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { action: PluginAction -> action.label }
                .thenBy { it.component.flattenToString() })
    }

    fun setEnabled(action: PluginAction, enabled: Boolean) {
        val key = action.component.flattenToString()
        approvals.edit().apply {
            if (enabled) putString(key, action.signingIdentity) else remove(key)
        }.apply()
    }

    fun launch(host: Context, action: PluginAction): Boolean {
        val current = discover().firstOrNull {
            it.component == action.component && it.signingIdentity == action.signingIdentity && it.enabled
        } ?: return false
        return try {
            host.startActivity(Intent(ACTION).setComponent(current.component))
            true
        } catch (_: android.content.ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun signingIdentity(packageName: String): String? {
        val signatures = try {
            if (Build.VERSION.SDK_INT >= 28) {
                packages.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                    .signingInfo?.apkContentsSigners
            } else {
                packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
            }
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        return signatures?.takeIf { it.isNotEmpty() }?.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }?.sorted()?.joinToString(":")
    }

    private companion object {
        const val ACTION = "com.example.expressivelauncher.action.PLUGIN"
        const val API_VERSION = "com.example.expressivelauncher.plugin.API_VERSION"
    }
}
