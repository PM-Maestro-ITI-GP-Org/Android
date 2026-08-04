package com.motorguard.ivi.ui.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Android requires a session *service* whose only job is to mint a session when
 * the assistant is triggered. Registered in the manifest and pointed at by
 * res/xml/interaction_service.xml.
 */
class VoiceSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession =
        VoiceOverlaySession(this)
}
