package me.timschneeberger.rootlessjamesdsp.flavor

import com.topjohnwu.superuser.Shell
import me.timschneeberger.rootlessjamesdsp.BuildConfig
import timber.log.Timber

object RootShellImpl {
    fun hasRootShell(): Boolean = Shell.getShell().isRoot

    /** Preserve the helper's machine-readable error line even on exit 1. */
    fun cmdForOutput(command: String): List<String> {
        val result = Shell.cmd(command).exec()
        if (!result.isSuccess) Timber.w("Native control helper exited with ${result.code}")
        return result.out
    }
    init {
        Shell.enableVerboseLogging = BuildConfig.DEBUG
        Shell.setDefaultBuilder(Shell.Builder.create()
            .setFlags(Shell.FLAG_REDIRECT_STDERR)
            .setTimeout(10)
        )
    }

    interface OnShellAttachedCallback {
        fun onShellAttached(isRoot: Boolean)
    }

    fun getShell(callback: OnShellAttachedCallback) {
        Shell.getShell {
            callback.onShellAttached(it.isRoot)
        }
    }

    fun cmd(command: String): Boolean {
        Timber.d("root command: $command")
        return Shell.cmd(command).exec().run {
            if(!isSuccess) {
                Timber.e("Command failed; error $code")
                err.forEach(Timber::e)
            }
            isSuccess
        }
    }
}
