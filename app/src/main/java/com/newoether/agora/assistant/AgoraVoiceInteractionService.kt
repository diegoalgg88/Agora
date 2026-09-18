package com.newoether.agora.assistant

import android.service.voice.VoiceInteractionService

/**
 * Entry point the system binds when Agora is the default digital assistant app. All behavior
 * lives in the session; this service only exists so the OS can resolve the component.
 */
class AgoraVoiceInteractionService : VoiceInteractionService()
