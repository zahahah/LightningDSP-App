package lightning.dsp.interop

/** JNI pointers are opaque bit patterns, not positive numeric IDs.
 * AArch64 tagged pointers may have the sign bit set when represented as Long.
 * 1/2 are legacy native allocation-error sentinels, never valid handles. */
internal object NativeDspHandlePolicy {
    fun isValid(handle: Long): Boolean = handle != 0L && handle != 1L && handle != 2L
}
