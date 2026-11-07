package dev.davidx3d.wisp

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.davidx3d.wisp.session.VoiceSessionService

@HiltAndroidApp
class WispApp : Application() {
    override fun onCreate() {
        super.onCreate()
        VoiceSessionService.createChannel(this)
    }
}
