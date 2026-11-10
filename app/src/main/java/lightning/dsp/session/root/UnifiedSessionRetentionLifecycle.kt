package lightning.dsp.session.root

/** Generation fence for provider callbacks queued across service teardown/recreation. */
internal class UnifiedSessionRetentionLifecycle {
    private var generation = 0L
    private var active = false
    @Synchronized fun start() { generation++; active = true }
    @Synchronized fun stop() { generation++; active = false }
    @Synchronized fun current(): Long? = generation.takeIf { active }
    @Synchronized fun accepts(expected: Long): Boolean = active && generation == expected
}
