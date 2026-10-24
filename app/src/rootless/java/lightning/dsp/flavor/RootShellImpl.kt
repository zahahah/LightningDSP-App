@file:Suppress("UNUSED_PARAMETER")

package lightning.dsp.flavor

// Stubbed
object RootShellImpl {
    interface OnShellAttachedCallback {
        fun onShellAttached(isRoot: Boolean)
    }

    fun getShell(callback: OnShellAttachedCallback) {}
    fun cmd(command: String) = false
}
