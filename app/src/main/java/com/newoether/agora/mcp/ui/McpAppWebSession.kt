package com.newoether.agora.mcp.ui

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Message
import android.view.View
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
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
private const val MAX_LOGGED_REFS = 20
private const val RENDER_PROBE_DELAY_MS = 3000L
private const val MAX_PROBE_CHARS = 800
private const val RENDER_PROBE_JS = """(function(){
  var cs = [].slice.call(document.querySelectorAll('canvas')).map(function(e){
    var r = e.getBoundingClientRect(), s = getComputedStyle(e), g = null, lost = 'nogl';
    try { g = e.getContext('webgl2') || e.getContext('webgl'); if (g) lost = g.isContextLost(); } catch (x) {}
    return {w:e.width,h:e.height,cw:Math.round(r.width),ch:Math.round(r.height),disp:s.display,vis:s.visibility,op:s.opacity,lost:lost};
  });
  return JSON.stringify({iw:innerWidth,ih:innerHeight,dpr:devicePixelRatio,body:[document.body.clientWidth,document.body.clientHeight],gl:typeof mapboxgl,canvases:cs});
})()"""
private val EXTERNAL_REF = Regex("""(?:src|href)\s*=\s*["'](https?://[^"']+)["']""")
private val BRIDGE_METHOD = Regex(""""method"\s*:\s*"([^"]+)"""")

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

    /** Debug builds only: enables the post-load render probe (see [probeRenderState]). */
    private var debugProbe = false

    /**
     * Subclass so container size changes reach the page: apps that measure themselves at first
     * layout (e.g. Mapbox GL) otherwise keep a stale canvas size when the host view is later
     * resized (auto-open height, expand, list relayout). Dispatching a standard window resize
     * is a no-op for apps that ignore it.
     */
    val webView: WebView = object : WebView(context) {
        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            if (w != ow || h != oh) dispatchViewportResize()
        }
    }

    init {
        configure()
        logDiagnostics(context)
    }

    /**
     * Debug-build-only visibility into a view that renders blank without any console error:
     * the CSP actually applied, the external resources the document references, and (below) the
     * bridge methods the view sends. Also turns on remote WebView inspection (chrome://inspect).
     * Release builds stay silent: [DebugLog] is a no-op there and inspection is never enabled.
     */
    private fun logDiagnostics(context: Context) {
        val debuggable =
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!debuggable) return
        debugProbe = true
        WebView.setWebContentsDebuggingEnabled(true)
        DebugLog.d(TAG, "csp=${pageHeaders["Content-Security-Policy"]}")
        val external = EXTERNAL_REF.findAll(resource.html)
            .map { loggableUrl(it.groupValues[1]) }
            .distinct()
            .take(MAX_LOGGED_REFS)
            .toList()
        DebugLog.d(TAG, "html bytes=${pageBytes.size} externalRefs=$external")
    }

    fun start() {
        if (closed) return
        if (webView.width > 0 && webView.height > 0) {
            webView.loadUrl(pageUrl)
            return
        }
        // The document must not load while the host view is still unmeasured: apps using
        // percent-height CSS (html/body 100%) resolve against the layout viewport frozen at
        // load time, so a 0-sized load collapses them to 0 height for good (observed with the
        // Mapbox GL app: map container stays 0px while innerHeight reports the real size).
        webView.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?,
                left: Int,
                top: Int,
                right: Int,
                bottom: Int,
                oldLeft: Int,
                oldTop: Int,
                oldRight: Int,
                oldBottom: Int,
            ) {
                if (closed) {
                    v?.removeOnLayoutChangeListener(this)
                    return
                }
                if (right - left > 0 && bottom - top > 0) {
                    v?.removeOnLayoutChangeListener(this)
                    if (!closed) webView.loadUrl(pageUrl)
                }
            }
        })
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

    private fun dispatchViewportResize() {
        if (closed) return
        webView.evaluateJavascript("window.dispatchEvent(new Event('resize'))", null)
    }

    /**
     * Debug-only: logs geometry and WebGL state of every canvas (sizes, visibility, context lost)
     * so a map that fetches tiles but shows nothing can be told apart as a 0-size canvas, a lost
     * GL context, or a compositing problem in the host. No page content or URLs are read.
     */
    private fun probeRenderState() {
        if (closed) return
        webView.evaluateJavascript(RENDER_PROBE_JS) { result ->
            DebugLog.d(TAG, "probe view=${webView.width}x${webView.height} page=${result.orEmpty().take(MAX_PROBE_CHARS)}")
        }
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

        // While the view is open, one-finger drags belong to the app content (e.g. panning a
        // map), not to the chat list; scrolling past it still works by touching outside the
        // view or using the close control.
        webView.setOnTouchListener { view, _ ->
            view.parent?.requestDisallowInterceptTouchEvent(true)
            false
        }

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
                    DebugLog.d(TAG, "allowed request ${loggableUrl(url)}")
                    null
                } else {
                    blocked(url)
                }
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean = true

            // Status codes of sub-resources the page was allowed to fetch (e.g. a 403 from a
            // URL-restricted Mapbox token): invisible to the console and to the allow-list.
            override fun onReceivedHttpError(
                view: WebView?,
                request: WebResourceRequest?,
                errorResponse: WebResourceResponse?,
            ) {
                val url = request?.url?.toString() ?: return
                DebugLog.w(TAG, "http ${errorResponse?.statusCode} ${loggableUrl(url)}")
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                val url = request?.url?.toString() ?: return
                DebugLog.w(TAG, "net error ${error?.errorCode} ${error?.description} ${loggableUrl(url)}")
            }

            override fun onRenderProcessGone(
                view: WebView?,
                detail: RenderProcessGoneDetail?,
            ): Boolean {
                DebugLog.w(TAG, "render process gone (crashed=${detail?.didCrash()})")
                onRenderProcessGone()
                return true
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                // The page usually initializes before the host view reaches its final size;
                // re-announce the viewport so self-measuring apps can lay out correctly.
                dispatchViewportResize()
                if (debugProbe) {
                    // Let the map finish its first frames before sampling its state.
                    view?.postDelayed({ probeRenderState() }, RENDER_PROBE_DELAY_MS)
                }
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
        // Method name only: payloads may carry tool results or user data.
        DebugLog.d(TAG, "view -> host method=${BRIDGE_METHOD.find(data)?.groupValues?.get(1)}")
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
