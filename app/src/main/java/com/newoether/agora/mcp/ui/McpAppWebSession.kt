package com.newoether.agora.mcp.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.ViewGroup
import android.webkit.ConsoleMessage
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
import com.newoether.agora.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

private const val TAG = "McpApp"
private const val MAX_LOGGED_CONSOLE_CHARS = 300

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
 *
 * Blocked requests and the view's console output are written to [DebugLog] (never to the UI) so a
 * view that renders blank can be diagnosed: a missing CSP domain or a script error shows up there.
 */
internal class McpAppWebSession(
    context: Context,
    serverId: String,
    private val resource: McpUiResource,
    private val router: McpAppBridgeRouter,
    private val scope: CoroutineScope,
    private val onRenderProcessGone: () -> Unit,
) {
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
                val url = request?.url?.toString() ?: return blocked(null)
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
                return if (McpAppSandbox.isRequestAllowed(url, origin, resource.uiMeta)) {
                    null
                } else {
                    blocked(url)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                DebugLog.w(TAG, "render process gone (crashed=${detail?.didCrash()})")
                onRenderProcessGone()
                return true
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    DebugLog.d(
                        TAG,
                        "console ${consoleMessage.messageLevel()}: " +
                            consoleMessage.message().orEmpty().take(MAX_LOGGED_CONSOLE_CHARS),
                    )
                }
                return true
            }

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
        WebViewCompat.addDocumentStartJavaScript(webView, McpAppBridgeShim.SCRIPT, allowedOrigins)
        WebViewCompat.addWebMessageListener(
            webView,
            McpAppBridgeShim.HOST_OBJECT_NAME,
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

    private fun blocked(url: String?): WebResourceResponse {
        if (url != null) DebugLog.w(TAG, "blocked request to ${loggableUrl(url)}")
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            403,
            "Blocked",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

/** Scheme, host and path only: query strings and fragments may carry tokens or user data. */
internal fun loggableUrl(url: String): String {
    val uri = Uri.parse(url)
    val scheme = uri.scheme ?: return "(invalid url)"
    val host = uri.host ?: return "$scheme:"
    return "$scheme://$host${uri.path.orEmpty()}".take(200)
}
