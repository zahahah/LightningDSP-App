package lightning.dsp.session.root

/** Short-lived permission for a scoped app to prepare its session immediately before play(). */
internal data class SessionPlaybackPreparationLease(
    val uid: Int,
    val packageName: String,
    val expiresAt: Long,
) {
    fun isCurrent(sessionId: Int, ownerUid: Int, ownerPackage: String, now: Long): Boolean =
        sessionId > 0 && uid == ownerUid && packageName == ownerPackage && now < expiresAt
}
