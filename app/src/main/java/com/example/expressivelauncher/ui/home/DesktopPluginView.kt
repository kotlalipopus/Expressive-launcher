package com.example.expressivelauncher.ui.home

import android.annotation.SuppressLint
import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.expressivelauncher.data.DesktopPlugins
import com.example.expressivelauncher.data.activeDesktopPlugin

@Composable
internal fun DesktopPluginView(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val store = remember(context) { DesktopPlugins(context) }
    val plugin by activeDesktopPlugin(store)
    val script = plugin?.script ?: return
    key(script) {
        var failed by remember { mutableStateOf(false) }
        if (!failed) {
            AndroidView(
                modifier = modifier,
                factory = { PluginWebView(it, script) { failed = true } },
                onRelease = { it.destroy() },
            )
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class PluginWebView(context: Context, script: String, onRendererGone: () -> Unit) : WebView(context) {
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

    override fun dispatchTouchEvent(event: MotionEvent) = false

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible) onResume() else onPause()
    }

    private companion object {
        const val LOCAL_PAGE = "file:///android_asset/desktop-canvas.html"
    }
}
