package com.newoether.agora.mcp.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.newoether.agora.AgoraApplication
import com.newoether.agora.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives the `com.newoether.agora:/oauth/mcp` redirect of an MCP OAuth sign-in and hands it to
 * [McpOAuthManager]. It validates nothing itself (the manager checks scheme, path, `state`,
 * expiry and issuer), keeps no UI, and returns the user to the app immediately.
 */
class McpOAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    private fun handle(intent: Intent?) {
        val redirect = intent?.dataString ?: return
        val app = application as? AgoraApplication ?: return
        // The container may still be opening (process was killed while the browser was up).
        CALLBACK_SCOPE.launch {
            app.awaitContainer()?.mcpRegistry?.oauth?.complete(redirect)
        }
    }

    private companion object {
        val CALLBACK_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
