package lightning.dsp.flavor

import com.google.gson.JsonParser
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A stable module release is eligible only when its signed-off feed and tag agree. */
internal object ModuleUpdateCheck {
    private const val REPOSITORY = "zahahah/LightningDSP"
    private const val MODULE_ID = "ainur_lightningdsp"
    private const val FEED_URL = "https://raw.githubusercontent.com/$REPOSITORY/master/update.json"
    private const val LATEST_RELEASE_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    private val stableVersion = Regex("v\\d+\\.\\d+(?:\\.\\d+)?")
    private val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    suspend fun check(enabled: Boolean = true): Pair<String, String>? = withContext(Dispatchers.IO) {
        if (!enabled) return@withContext null
        try {
            val installed = Shell.cmd("cat /data/adb/modules/$MODULE_ID/module.prop").exec()
            if (!installed.isSuccess) return@withContext null
            findUpdate(installed.out.joinToString("\n"), ::get)
        } catch (_: Exception) {
            null // Offline, unavailable, or ambiguous metadata: do not interrupt startup.
        }
    }

    /** Fetch order is explicit for tests; all unexpected/missing data fails closed. */
    internal fun findUpdate(installed: String, fetch: (String) -> String?): Pair<String, String>? {
        return try {
            val installedCode = moduleCode(installed) ?: return null
            val feed = updateFeed(fetch(FEED_URL) ?: return null) ?: return null
            val release = releaseInfo(fetch(LATEST_RELEASE_URL) ?: return null) ?: return null
            if (feed.version != release.tag) return null
            val metadata = fetch("https://raw.githubusercontent.com/$REPOSITORY/${release.tag}/module.prop")
                ?: return null
            val remoteCode = moduleCode(metadata, release.tag) ?: return null
            if (remoteCode != feed.versionCode) return null
            if (isNewer(installedCode, feed.versionCode)) release.id to feed.version else null
        } catch (_: Exception) { null }
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url).header("User-Agent", "LightningDSP")
            .header("Accept", "application/vnd.github+json").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val source = response.body?.source() ?: return@use null
            source.request(1_048_577)
            if (source.buffer.size > 1_048_576) null else source.readUtf8()
        }
    }

    internal fun isNewer(installedCode: Int, remoteCode: Int) = remoteCode > installedCode

    internal fun moduleCode(properties: String, expectedVersion: String? = null): Int? {
        val entries = properties.lineSequence().map { it.trim() }.filter { '=' in it }
            .map { it.substringBefore('=') to it.substringAfter('=') }.toList()
        if (entries.count { it.first == "id" } != 1 ||
            entries.single { it.first == "id" }.second != MODULE_ID ||
            entries.count { it.first == "versionCode" } != 1) return null
        if (expectedVersion != null && (entries.count { it.first == "version" } != 1 ||
                entries.single { it.first == "version" }.second != expectedVersion)) return null
        return entries.single { it.first == "versionCode" }.second.toIntOrNull()?.takeIf { it > 0 }
    }

    internal data class UpdateFeed(val version: String, val versionCode: Int)
    internal data class ReleaseInfo(val id: String, val tag: String)

    internal fun updateFeed(json: String): UpdateFeed? {
        return try {
            val feed = JsonParser.parseString(json).asJsonObject
            val version = feed.get("version").asString.takeIf { stableVersion.matches(it) } ?: return null
            val codeValue = feed.get("versionCode").asJsonPrimitive
            if (!codeValue.isNumber) return null
            val versionCode = codeValue.asString.toIntOrNull()?.takeIf { it > 0 } ?: return null
            val expectedZip = "https://github.com/$REPOSITORY/releases/download/$version/LightningDSP-$version.zip"
            val validChangelogs = setOf(
                "https://github.com/$REPOSITORY/releases/tag/$version",
                "https://raw.githubusercontent.com/$REPOSITORY/$version/changelog.md"
            )
            if (feed.get("zipUrl").asString != expectedZip || feed.get("changelog").asString !in validChangelogs) null
            else UpdateFeed(version, versionCode)
        } catch (_: Exception) { null }
    }

    internal fun releaseInfo(json: String): ReleaseInfo? {
        return try {
            val release = JsonParser.parseString(json).asJsonObject
            if (release.get("draft").asBoolean || release.get("prerelease").asBoolean) null else {
                val id = release.get("id").asLong.takeIf { it > 0 }
                val tag = release.get("tag_name").asString.takeIf { stableVersion.matches(it) }
                val assetName = tag?.let { "LightningDSP-$it.zip" }
                val assetUrl = tag?.let { "https://github.com/$REPOSITORY/releases/download/$it/$assetName" }
                val archives = release.getAsJsonArray("assets").filter {
                    it.asJsonObject.get("name").asString.matches(Regex("LightningDSP-.*\\.zip", RegexOption.IGNORE_CASE))
                }
                val zip = archives.size == 1 && archives.single().asJsonObject.let { asset ->
                    asset.get("state").asString == "uploaded" && asset.get("name").asString == assetName &&
                        asset.get("browser_download_url").asString == assetUrl
                }
                if (id != null && tag != null && zip) ReleaseInfo(id.toString(), tag) else null
            }
        } catch (_: Exception) { null }
    }
}
