package lightning.dsp.flavor

import com.topjohnwu.superuser.Shell
import android.os.SystemClock
import lightning.dsp.BuildConfig
import timber.log.Timber

object RootShellImpl {
    private var lastRootRetry = -30_000L

    @Synchronized
    fun hasRootShell(): Boolean {
        val shell = Shell.getShell()
        if (shell.isRoot) return true
        // libsu caches the non-root shell after a denial. Reacquire at a bounded
        // rate so a later KernelSU/Magisk approval works without clearing data.
        // This requests authorization; it never grants itself root privileges.
        val now = SystemClock.elapsedRealtime()
        if (now - lastRootRetry < 30_000L) return false
        lastRootRetry = now
        shell.close()
        return Shell.getShell().isRoot
    }

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
