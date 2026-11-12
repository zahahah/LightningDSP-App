package lightning.dsp.interop.appmixer

/** UID routing cannot split apps sharing a UID. The caller must supply a complete
 * package inventory for each UID, or reject the inventory before calling this policy. */
internal object AppMixerUidSelection {
    data class App(val uid: Int, val packageName: String, val enabled: Boolean)

    fun exclusions(apps: Collection<App>, relayUid: Int): Set<Int>? {
        if (apps.any { it.packageName.isBlank() }) return null
        val disabled = apps.groupBy { it.uid }.filterValues { members -> members.any { !it.enabled } }.keys
        return (disabled + setOf(0, relayUid)).takeIf { it.size <= AppMixerExperimentConfig.MAX_EXCLUDED_UIDS }
    }

    fun select(apps: Collection<App>, relayUid: Int): Set<Int> {
        val groups = apps.groupBy { it.uid }
        return groups.entries.asSequence().filter { (uid, members) ->
            uid != relayUid && uid >= 0 && uid % 100000 in 10000..19999 &&
                members.isNotEmpty() && members.all { it.packageName.isNotBlank() && it.enabled }
        }.map { it.key }.toSet()
    }
}
