package com.newoether.agora.mcp.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.newoether.agora.mcp.McpUiResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * The features the isolation model depends on. Without both, the host never renders a view and the
 * plain tool result stays the only presentation: there is deliberately no weaker fallback bridge.
 */
internal object McpAppWebSupport {
    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
}

/**
 * Owns one sandboxed WebView for one MCP App document and wires it to a [McpAppBridgeRouter].
 *
 * Isolation: the document is served by [WebViewClient.shouldInterceptRequest] on a dedicated
 * synthetic origin with real CSP / Permissions-Policy response headers; every other request is
 * blocked unless the server declared its origin; there is no `addJavascriptInterface`; messages
 * travel through an origin-restricted `WebMessageListener`; navigation, popups, dialogs, file and
 * device permissions are all refused. All methods must be called on the main thread.
 */
internal class McpAppWebSession(
    context: Context,
    serverId: String,
    private val resource: McpUiResource,
    private val router: McpAppBridgeRouter,
    private val scope: CoroutineScope,
    private val onRenderProcessGone: () -> Unit,
) {
    private companion object {
        const val BRIDGE_NAME = "__agoraMcpHost"

        /**
         * Runs before any page script. SDK views talk to `window.parent.postMessage` and listen for
         * `message` events whose `source` is `window.parent`; in a top-level WebView there is no
         * parent frame, so this shim substitutes one that forwards to the native listener.
         */
        val BRIDGE_SHIM = """
            (function () {
              var host = function () { return window.__agoraMcpHost; };
              var parentProxy = {
                postMessage: function (message) {
                  var h = host();
                  if (!h) return;
                  try { h.postMessage(JSON.stringify(message)); } catch (e) {}
                }
              };
              try {
                Object.defineProperty(window, 'parent', {
                  configurable: true,
                  get: function () { return parentProxy; }
                });
              } catch (e) {}
              function bind() {
                var h = host();
                if (!h) return false;
                h.onmessage = function (event) {
                  var data;
                  try { data = JSON.parse(event.data); } catch (e) { return; }
                  window.dispatchEvent(new MessageEvent('message', {
                    data: data,
                    source: parentProxy,
                    origin: window.location.origin
                  }));
                };
                return true;
              }
              if (!bind()) {
                var timer = setInterval(function () { if (bind()) clearInterval(timer); }, 5);
              }
            })();
        """.trimIndent()
    }

    private val origin = McpAppSandbox.origin(serverId, resource.uri)
    private val pageUrl = McpAppSandbox.pageUrl(origin)
    private val pageBytes = resource.html.toByteArray(Charsets.UTF_8)
    private val pageHeaders = McpAppSandbox.pageHeaders(resource.uiMeta)

    @Volatile
    private var reply: JavaScriptReplyProxy? = null

    @Volatile
    private var closed = false

    val webView: WebView = WebView(context)

    init {
        configure()
    }

    fun start() {
        if (!closed) webView.loadUrl(pageUrl)
    }

    /** Best-effort teardown request, then the WebView is destroyed. Idempotent. */
    fun close() {
        if (closed) return
        router.teardownMessage()?.let { reply?.postMessage(it) }
        closed = true
        reply = null
        webView.stopLoading()
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    private fun configure() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = false
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            setSupportZoom(false)
            setGeolocationEnabled(false)
            safeBrowsingEnabled = true
        }
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.overScrollMode = WebView.OVER_SCROLL_NEVER

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return blocked()
                if (url == pageUrl && request.isForMainFrame && request.method == "GET") {
                    return WebResourceResponse(
                        "text/html",
                        "utf-8",
                        200,
                        "OK",
                        pageHeaders,
                        ByteArrayInputStream(pageBytes),
                    )
                }
                return if (McpAppSandbox.isRequestAllowed(url, origin, resource.uiMeta)) null else blocked()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                onRenderProcessGone()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.deny()
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?,
            ) {
                callback?.invoke(origin, false, false)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?,
            ): Boolean = false

            override fun onJsAlert(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsConfirm(
                view: WebView?,
                url: String?,
                message: String?,
                result: JsResult?,
            ): Boolean {
                result?.cancel()
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?,
            ): Boolean {
                result?.cancel()
                return true
            }
        }

        val allowedOrigins = setOf(origin)
        WebViewCompat.addDocumentStartJavaScript(webView, BRIDGE_SHIM, allowedOrigins)
        WebViewCompat.addWebMessageListener(
            webView,
            BRIDGE_NAME,
            allowedOrigins,
        ) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            onViewMessage(message, sourceOrigin, isMainFrame, replyProxy)
        }
    }

    private fun onViewMessage(
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (closed || !isMainFrame) return
        if ("${sourceOrigin.scheme}://${sourceOrigin.host}" != origin) return
        val data = message.data ?: return
        reply = replyProxy
        scope.launch {
            val outgoing = router.handle(data)
            if (!closed) outgoing.forEach { replyProxy.postMessage(it) }
        }
    }

    private fun blocked() = WebResourceResponse(
        "text/plain",
        "utf-8",
        403,
        "Blocked",
        emptyMap(),
        ByteArrayInputStream(ByteArray(0)),
    )
}
