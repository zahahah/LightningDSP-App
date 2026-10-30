package me.timschneeberger.rootlessjamesdsp.backup

import android.content.Context
import androidx.work.WorkerParameters

/** WorkManager persists this class name; keep old queued/manual/periodic work resolvable. */
class BackupCreatorJob(context: Context, params: WorkerParameters) :
    lightning.dsp.backup.BackupCreatorJob(context, params)
