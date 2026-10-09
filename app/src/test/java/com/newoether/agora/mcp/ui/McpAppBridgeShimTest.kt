package com.newoether.agora.mcp.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * There is no JavaScript engine in the unit-test classpath, so these pin the two properties of the
 * shim that were wrong once and that no Kotlin-level test can observe. Whether the handshake works
 * end to end needs a device with a real WebView.
 */
class McpAppBridgeShimTest {
    private val script = McpAppBridgeShim.SCRIPT

    @Test
    fun `the native listener name is the one the shim talks to`() {
        assertEquals("__agoraMcpHost", McpAppBridgeShim.HOST_OBJECT_NAME)
        assertTrue(script.contains("window.__agoraMcpHost"))
    }

    @Test
    fun `the stand in parent is never passed to the MessageEvent constructor`() {
        // MessageEventInit.source rejects anything but a Window, MessagePort or ServiceWorker; a
        // plain object makes the constructor throw and the view never receives the host's replies.
        val constructorCall = script.substringAfter("new MessageEvent(").substringBefore(");")

        assertTrue(constructorCall.contains("data: data"))
        assertFalse(constructorCall.contains("source"))
        // It is attached to the event instance afterwards, and window.parent returns the same object
        // so SDK views that compare event.source with window.parent accept the message.
        assertTrue(script.contains("Object.defineProperty(event, 'source', { value: parentProxy })"))
        assertTrue(script.contains("get: function () { return parentProxy; }"))
    }

    @Test
    fun `view to host messages are serialized once and failures never reach the page`() {
        assertTrue(script.contains("h.postMessage(JSON.stringify(message))"))
        assertTrue(script.contains("try { h.postMessage(JSON.stringify(message)); } catch (e) {}"))
    }
}
