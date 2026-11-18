package com.example.expressivelauncher.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.webkit.RenderProcessGoneDetail
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.expressivelauncher.data.DesktopPlugins
import com.example.expressivelauncher.data.desktopPlugins

@Composable
internal fun DesktopPluginView(visible: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { DesktopPlugins(context) }
    val plugins by desktopPlugins(store)
    Column(modifier) {
        for (plugin in plugins) if (plugin.enabled) key(plugin.id) {
            var failed by remember { mutableStateOf(false) }
            var mounted by remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(visible) { if (visible) mounted = true }
            if (!failed && mounted) {
                AndroidView(
                    modifier = Modifier.size(116.dp),
                    factory = { PluginWebView(it, plugin.script) { failed = true } },
                    update = {
                        it.interactive = plugin.interactive
                        it.visibility = if (visible) WebView.VISIBLE else WebView.INVISIBLE
                    },
                    onRelease = { it.destroy() },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class PluginWebView(context: Context, script: String, onRendererGone: () -> Unit) : WebView(context) {
    var interactive = false
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (width == 0 || height == 0) return false
            val x = (event.x * 240f / width).coerceIn(0f, 240f)
            val y = (event.y * 240f / height).coerceIn(0f, 240f)
            evaluateJavascript(
                "document.getElementById('desktop')?.dispatchEvent(new CustomEvent('launchertap', {detail:{x:$x,y:$y}}));",
                null,
            )
            performClick()
            return true
        }
    })

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        isFocusable = false
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        settings.apply {
            javaScriptEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            blockNetworkLoads = true
            domStorageEnabled = false
            javaScriptCanOpenWindowsAutomatically = false
        }
        webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                if (message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    android.util.Log.w("DesktopPlugin", message.message())
                }
                return true
            }
        }
        webViewClient = object : WebViewClient() {
            private var started = false

            override fun onPageFinished(view: WebView, url: String) {
                if (started) return
                started = true
                view.evaluateJavascript(
                    """
                    (() => {
                        const target = document.getElementById('desktop');
                        if (!target) return false;
                        (function (canvas) { 'use strict';
                        $script
                        })(target);
                        return true;
                    })();
                    """.trimIndent(),
                ) { result ->
                    if (result != "true") android.util.Log.w("DesktopPlugin", "Script did not initialize")
                    view.invalidate()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                WebResourceResponse("text/plain", "UTF-8", null)

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    android.util.Log.w("DesktopPlugin", "Canvas load failed with code ${error.errorCode}")
                    onRendererGone()
                }
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                onRendererGone()
                return true
            }
        }
        loadUrl(LOCAL_PAGE)
    }

    override fun dispatchTouchEvent(event: MotionEvent) = interactive && detector.onTouchEvent(event)

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) onResume() else onPause()
        evaluateJavascript(
            "document.getElementById('desktop')?.dispatchEvent(new CustomEvent('launchervisibilitychange', " +
                "{detail:{visible:$isVisible}}));",
            null,
        )
    }

    private companion object {
        const val LOCAL_PAGE = "file:///android_asset/desktop-canvas.html"
    }
}
