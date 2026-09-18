package com.newoether.agora.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** Factory the system uses to create the assistant overlay session. */
class AgoraVoiceInteractionSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle): VoiceInteractionSession =
        AgoraVoiceInteractionSession(this)
}
