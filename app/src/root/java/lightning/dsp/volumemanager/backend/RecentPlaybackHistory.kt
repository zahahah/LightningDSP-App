package lightning.dsp.volumemanager.backend

/** Volatile, bounded history of apps whose playback has just stopped. */
internal class RecentPlaybackHistory(private val limit: Int = 5) {
    private var active: Set<String> = emptySet()
    private val recent = ArrayDeque<String>()

    init { require(limit > 0) }

    fun update(activePackages: Collection<String>, availablePackages: Set<String>): List<String> {
        val current = activePackages.filter { it in availablePackages }.toSet()
        recent.removeAll { it !in availablePackages }
        active.filter { it !in current && it in availablePackages }.forEach { packageName ->
            recent.remove(packageName)
            recent.addFirst(packageName)
        }
        active = current
        while (recent.size > limit) recent.removeLast()
        return recent.filterNot { it in current }
    }
}
