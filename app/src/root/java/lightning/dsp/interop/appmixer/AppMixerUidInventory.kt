package lightning.dsp.interop.appmixer

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.os.UserManager
import android.content.pm.LauncherApps
import lightning.dsp.session.shared.SessionDspControlPreferences
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Profile-scoped inventory; unknown package membership rejects that profile's route. */
internal class AppMixerUidInventory(private val context: Context, private val directPolicyUpdates: Boolean = false) {
    private data class WorkInventory(val apps: List<AppMixerUidSelection.App>, val userIds: Set<Int>)
    private data class CachedWorkPackages(val expiresAt: Long, val packagesByUid: Map<Int, Set<String>>?)

    private val workPackageCache = mutableMapOf<Int, CachedWorkPackages>()

    data class Route(
        val excludedUids: Set<Int>,
        val includesSystemSounds: Boolean,
        val userIds: Set<Int>,
        val workProfileIncluded: Boolean,
    )

    fun readRoute(includeSystemSounds: Boolean): Route? {
        return try {
            val packages = context.packageManager
            val preferences = context.getSharedPreferences("lightningdsp_volume_panel", Context.MODE_PRIVATE)
            @Suppress("DEPRECATION")
            val personal = packages.getInstalledApplications(0)
            val currentUser = Process.myUid() / 100000
            val launcher = context.getSystemService(LauncherApps::class.java)
            val personalApps = appsFor(personal, currentUser, packages::getPackagesForUid, preferences)
                ?: return null

            // Work-profile PackageManager visibility is restricted across users. Keep LauncherApps
            // as the authority for which profiles are eligible, then query their complete package
            // membership through the already-authorized root shell. Do not infer shared UIDs from
            // launcher activities alone.
            // The route only includes work users when LauncherApps exposes work apps; using
            // true as the absent-key default therefore enables work audio only when it exists.
            val optedInToWork = preferences.getBoolean("process_work_profile_audio", true)
            val workInventory = if (optedInToWork && launcher != null) runCatching {
                val userManager = context.getSystemService(UserManager::class.java)
                val work = launcher.profiles.filter { profile ->
                    userManager?.isQuietModeEnabled(profile) != true
                }.flatMap { profile ->
                    launcher.getActivityList(null, profile).map { it.applicationInfo }
                }.filter { it.uid >= 0 && it.uid / 100000 != currentUser }
                val users = work.map { it.uid / 100000 }.toSet()
                if (users.isEmpty()) WorkInventory(emptyList(), emptySet()) else {
                    val membersByUid = users.associateWith { userId ->
                        readWorkProfilePackages(userId) ?: throw IncompleteProfileInventory()
                    }
                    val visibleUids = work.map { it.uid }.toSet()
                    if (visibleUids.any { uid -> membersByUid[uid / 100000]?.containsKey(uid) != true })
                        throw IncompleteProfileInventory()
                    val inventory = membersByUid.values.flatMap { it.entries }.distinctBy { it.key to it.value }
                    val apps = inventory.map { (uid, names) ->
                        names.map { name -> AppMixerUidSelection.App(uid, name,
                            preferences.getBoolean(SessionDspControlPreferences.preferenceKey(name, uid / 100000, currentUser), true)) }
                    }.flatten()
                    WorkInventory(apps, users)
                }
            }.getOrNull() else null
            val workIncluded = optedInToWork && workInventory != null
            val apps = if (workIncluded) personalApps + workInventory?.apps.orEmpty() else personalApps
            val users = if (workIncluded) workInventory!!.userIds + currentUser else setOf(currentUser)
            return routeFor(apps, users, includeSystemSounds, workIncluded)
        } catch (_: RuntimeException) { null }
    }

    private fun appsFor(
        installed: List<android.content.pm.ApplicationInfo>,
        currentUser: Int,
        packagesForUid: (Int) -> Array<String>?,
        preferences: android.content.SharedPreferences,
    ): List<AppMixerUidSelection.App>? {
        val uids = installed.map { it.uid }.filter { it >= 0 && it % 100000 in 10000..19999 }.toSet()
        val apps = ArrayList<AppMixerUidSelection.App>()
        for (uid in uids) {
            val members = packagesForUid(uid)?.toSet() ?: return null
            if (members.isEmpty()) return null
            for (name in members) apps += AppMixerUidSelection.App(uid, name,
                preferences.getBoolean(SessionDspControlPreferences.preferenceKey(name, uid / 100000, currentUser), true))
        }
        return apps
    }

    @Synchronized
    private fun readWorkProfilePackages(userId: Int): Map<Int, Set<String>>? {
        if (userId !in 1..21_473) return null
        val now = SystemClock.elapsedRealtime()
        workPackageCache[userId]?.takeIf { now < it.expiresAt }?.let { return it.packagesByUid }
        val parsed = queryWorkProfilePackageList(userId)
        workPackageCache[userId] = CachedWorkPackages(now + WORK_PACKAGE_CACHE_MILLIS, parsed)
        return parsed
    }

    private fun queryWorkProfilePackageList(userId: Int): Map<Int, Set<String>>? = runCatching {
        val process = ProcessBuilder("su", "-c", "cmd package list packages -U --user $userId")
            .redirectErrorStream(true).start()
        val output = FutureTask {
            process.inputStream.bufferedReader().useLines { lines ->
                val result = StringBuilder()
                var count = 0
                for (line in lines) {
                    if (line.length > MAX_PACKAGE_LINE_CHARS || ++count > MAX_PROFILE_PACKAGE_COUNT ||
                        result.length + line.length + 1 > MAX_PROFILE_OUTPUT_CHARS) return@useLines null
                    result.append(line).append('\n')
                }
                result.toString()
            }
        }
        Thread(output, "LightningDSP-work-profile-inventory").apply { isDaemon = true }.start()
        if (!process.waitFor(PROFILE_QUERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            output.cancel(true)
            return null
        }
        if (process.exitValue() != 0) return null
        val text = output.get(250, TimeUnit.MILLISECONDS) ?: return null
        AppMixerWorkProfilePackageList.parse(userId, text)
    }.getOrNull()

    private fun routeFor(
        apps: List<AppMixerUidSelection.App>,
        users: Set<Int>,
        includeSystemSounds: Boolean,
        workProfileIncluded: Boolean,
    ): Route? {
        val exclusions = AppMixerUidSelection.exclusions(apps, Process.myUid(), includeSystemSounds, users.size, directPolicyUpdates)
        if (exclusions != null) return Route(exclusions, includeSystemSounds, users, workProfileIncluded)
        if (includeSystemSounds && !directPolicyUpdates) {
            // Preserve Fusion Mode if the extra usage rules exceed AudioPolicy's
            // criterion budget; degrade only optional system-sound coverage.
            AppMixerUidSelection.exclusions(apps, Process.myUid(), false, users.size)?.let {
                return Route(it, false, users, workProfileIncluded)
            }
        }
        return null
    }

    private class IncompleteProfileInventory : RuntimeException()

    private companion object {
        const val PROFILE_QUERY_TIMEOUT_MILLIS = 3_000L
        const val WORK_PACKAGE_CACHE_MILLIS = 15_000L
        const val MAX_PROFILE_PACKAGE_COUNT = 8_192
        const val MAX_PROFILE_OUTPUT_CHARS = 1_048_576
        const val MAX_PACKAGE_LINE_CHARS = 512
    }
}

/** Strict parser for `cmd package list packages -U --user <id>`; malformed output fails closed. */
internal object AppMixerWorkProfilePackageList {
    private val linePattern = Regex("package:([A-Za-z0-9_.]+)\\s+uid:(\\d+)")

    fun parse(userId: Int, output: String): Map<Int, Set<String>>? {
        if (userId !in 1..21_473 || output.isBlank()) return null
        val packages = linkedMapOf<Int, MutableSet<String>>()
        for (line in output.lineSequence().filter(String::isNotBlank)) {
            val match = linePattern.matchEntire(line.trim()) ?: return null
            val packageName = match.groupValues[1]
            val uid = match.groupValues[2].toIntOrNull() ?: return null
            if (uid < 0 || uid / 100000 != userId) return null
            if (uid % 100000 in 10_000..19_999) packages.getOrPut(uid) { linkedSetOf() }.add(packageName)
        }
        return packages.takeIf { it.isNotEmpty() }?.mapValues { it.value.toSet() }
    }
}
