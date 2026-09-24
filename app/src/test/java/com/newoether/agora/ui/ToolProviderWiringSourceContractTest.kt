package com.newoether.agora.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-contract guard for tool-provider registration. A provider that exists in
 * the DI container but is missing from a generation path's provider list is
 * invisible to the model there — exactly the interactive-chat gap that once made
 * `setup_email` unreachable while the headless path had it. This pins the wiring
 * so adding a provider without registering it in both paths fails the build.
 */
class ToolProviderWiringSourceContractTest {

    private fun source(file: String): String {
        var directory = File(requireNotNull(System.getProperty("user.dir"))).absoluteFile
        repeat(8) {
            val candidate = File(directory, "app/src/main/java/$file")
            if (candidate.isFile) return candidate.readText()
            directory = directory.parentFile ?: error("Reached filesystem root")
        }
        error("Unable to locate $file")
    }

    @Test
    fun interactiveChatRegistersEmailTools() {
        val chatViewModel = source("com/newoether/agora/viewmodel/ChatViewModel.kt")
        val registration = Regex(
            "additionalToolProviders\\s*=\\s*listOf\\(([^)]*)\\)",
        ).find(chatViewModel)?.groupValues?.get(1)
            ?: error("additionalToolProviders list not found in ChatViewModel")
        assertTrue(
            "emailToolProvider missing from interactive chat tools: $registration",
            registration.contains("emailToolProvider"),
        )
    }

    @Test
    fun headlessEngineRegistersEmailTools() {
        val container = source("com/newoether/agora/di/AppContainer.kt")
        val extraProviders = Regex(
            "extraToolProviders\\s*=\\s*listOf\\(([^)]*)\\)",
        ).find(container)?.groupValues?.get(1)
            ?: error("extraToolProviders list not found in AppContainer")
        assertTrue(
            "emailToolProvider missing from headless engine tools: $extraProviders",
            extraProviders.contains("emailToolProvider"),
        )
    }

    @Test
    fun factoryPassesEmailProviderThroughToChatViewModel() {
        val factory = source("com/newoether/agora/viewmodel/ChatViewModelFactory.kt")
        assertTrue(
            "ChatViewModelFactory must receive and forward emailToolProvider",
            factory.contains("emailToolProvider"),
        )
        val container = source("com/newoether/agora/di/AppContainer.kt")
        val factoryCall = container.substringAfter("fun chatViewModelFactory()")
        assertTrue(
            "AppContainer.chatViewModelFactory() must pass emailToolProvider",
            factoryCall.contains("emailToolProvider"),
        )
    }
}
