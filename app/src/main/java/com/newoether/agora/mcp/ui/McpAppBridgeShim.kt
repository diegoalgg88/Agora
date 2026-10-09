package com.newoether.agora.mcp.ui

/**
 * Document-start script injected into every MCP App WebView before any page script runs.
 *
 * SDK views talk to `window.parent.postMessage(...)` and accept host messages from `message` events
 * whose `source` is `window.parent`. A top-level WebView has no parent frame, so this shim
 * substitutes one: view -> host messages go to the native `WebMessageListener`, host -> view
 * messages are re-dispatched as `message` events.
 *
 * `MessageEventInit.source` only accepts a Window, MessagePort or ServiceWorker, so the stand-in
 * parent can never be passed to the constructor (Chrome throws a TypeError and the view would never
 * see the host's reply to `ui/initialize`). It is attached to the event instance afterwards.
 */
internal object McpAppBridgeShim {
    /** Name of the native `WebMessageListener` object exposed to the document. */
    const val HOST_OBJECT_NAME = "__agoraMcpHost"

    val SCRIPT: String = """
        (function () {
          var host = function () { return window.${HOST_OBJECT_NAME}; };
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
          function deliver(raw) {
            var data;
            try { data = JSON.parse(raw); } catch (e) { return; }
            var event = new MessageEvent('message', {
              data: data,
              origin: window.location.origin
            });
            try {
              Object.defineProperty(event, 'source', { value: parentProxy });
            } catch (e) {}
            window.dispatchEvent(event);
          }
          function bind() {
            var h = host();
            if (!h) return false;
            h.onmessage = function (event) { deliver(event.data); };
            return true;
          }
          if (!bind()) {
            var timer = setInterval(function () { if (bind()) clearInterval(timer); }, 5);
          }
        })();
    """.trimIndent()
}
