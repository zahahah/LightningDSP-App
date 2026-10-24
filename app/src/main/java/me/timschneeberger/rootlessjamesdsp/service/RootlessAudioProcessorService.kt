package me.timschneeberger.rootlessjamesdsp.service

import android.os.Build
import androidx.annotation.RequiresApi

/** Stable Android component identity for grants, tiles or persisted pending intents. */
@RequiresApi(Build.VERSION_CODES.Q)
class RootlessAudioProcessorService : lightning.dsp.service.RootlessAudioProcessorService()
