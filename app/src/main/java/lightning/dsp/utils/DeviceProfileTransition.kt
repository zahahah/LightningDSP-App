package lightning.dsp.utils

/** Preserve the outgoing profile before clearing state; an unknown owner must not overwrite an existing target. */
internal fun replaceDeviceProfile(
    hasCurrentProfile: Boolean,
    targetExists: Boolean,
    storeCurrent: () -> Boolean,
    replace: () -> Unit,
): Boolean {
    if ((hasCurrentProfile || !targetExists) && !storeCurrent()) return false
    replace()
    return true
}
