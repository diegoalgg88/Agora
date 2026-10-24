package com.newoether.agora.ui.chat.interaction

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import com.newoether.agora.model.SubagentSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** El capsule solo navega: tocar abre la hija por selección canónica; vacío/owner null no dibuja. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationSubagentsBarTest {
    @get:Rule val compose = createComposeRule()

    @Test fun capsulesNavigateScrollAndClearWithTheirOwner() {
        val agents = (0..8).map { index ->
            SubagentSummary("child-$index", "Worker $index", "Provider:model", "parent",
                listOf("running", "completed", "failed", "stopped", "idle")[index % 5])
        }
        var items by mutableStateOf(agents)
        var owner by mutableStateOf<String?>("parent")
        var selected: String? = null
        compose.setContent {
            MaterialTheme {
                Box(Modifier.fillMaxSize()) {
                    ConversationSubagentsBar(owner, items) { selected = it }
                }
            }
        }
        compose.onNodeWithText("Worker 0").assertExists().performClick()
        assertEquals("child-0", selected)
        compose.onNode(hasScrollAction()).performScrollToIndex(8)
        compose.onNodeWithText("Worker 8").assertExists().performClick()
        assertEquals("child-8", selected)
        compose.runOnIdle { items = emptyList() }
        compose.onNodeWithText("Worker 8").assertDoesNotExist()
        compose.runOnIdle { items = agents; owner = null }
        compose.onNodeWithText("Worker 0").assertDoesNotExist()
    }
}
