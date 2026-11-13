package lightning.dsp.flavor

import org.junit.Assert.*
import org.junit.Test

class ModuleUpdateCheckTest {
    @Test fun disabledChecksDoNotRequireRootOrNetwork() = kotlinx.coroutines.runBlocking {
        assertNull(ModuleUpdateCheck.check(enabled = false))
    }

    private fun release(draft: Boolean = false, prerelease: Boolean = false, tag: String = "v6.7", asset: String = "LightningDSP-v6.7.zip") =
        """{"id":42,"draft":$draft,"prerelease":$prerelease,"tag_name":"$tag","assets":[{"state":"uploaded","name":"$asset","browser_download_url":"https://github.com/zahahah/LightningDSP/releases/download/$tag/$asset"}]}"""

    private fun feed(version: String = "v6.7", code: Int = 159,
                     url: String = "https://github.com/zahahah/LightningDSP/releases/download/$version/LightningDSP-$version.zip",
                     changelog: String = "https://github.com/zahahah/LightningDSP/releases/tag/$version") =
        """{"version":"$version","versionCode":$code,"zipUrl":"$url","changelog":"$changelog"}"""

    @Test fun stableModuleReleasesOnly() {
        assertEquals(ModuleUpdateCheck.ReleaseInfo("42", "v6.7"), ModuleUpdateCheck.releaseInfo(release()))
        assertNull(ModuleUpdateCheck.releaseInfo(release(draft = true)))
        assertNull(ModuleUpdateCheck.releaseInfo(release(prerelease = true)))
        assertNull(ModuleUpdateCheck.releaseInfo(release(tag = "../bad")))
        assertNull(ModuleUpdateCheck.releaseInfo(release(asset = "LightningDSP.apk")))
        assertNull(ModuleUpdateCheck.releaseInfo("{}"))
    }

    @Test fun stableFeedRequiresCanonicalReleaseUrlsAndVersion() {
        assertEquals(ModuleUpdateCheck.UpdateFeed("v6.7", 159), ModuleUpdateCheck.updateFeed(feed()))
        assertNull(ModuleUpdateCheck.updateFeed(feed(version = "v6.7-beta-1")))
        assertNull(ModuleUpdateCheck.updateFeed(feed(url = "http://github.com/evil.zip")))
        assertNull(ModuleUpdateCheck.updateFeed(feed(code = 0)))
        assertNotNull(ModuleUpdateCheck.updateFeed(feed(
            changelog = "https://raw.githubusercontent.com/zahahah/LightningDSP/v6.7/changelog.md")))
        assertNull(ModuleUpdateCheck.updateFeed(feed(
            changelog = "https://evil.invalid/zahahah/LightningDSP/releases/tag/v6.7")))
    }

    @Test fun onlyStrictlyNewerModuleCodesAreOffered() {
        assertTrue(ModuleUpdateCheck.isNewer(208, 209))
        assertFalse(ModuleUpdateCheck.isNewer(208, 208))
        assertFalse(ModuleUpdateCheck.isNewer(208, 159))
    }

    @Test fun missingNetworkAndUnknownOrOldMetadataNeverPrompt() {
        val installed = "id=ainur_lightningdsp\nversion=v6.6\nversionCode=150"
        assertNull(ModuleUpdateCheck.findUpdate(installed) { null })
        assertNull(ModuleUpdateCheck.findUpdate(installed) { throw java.io.IOException("offline") })
        val feedUrl = "https://raw.githubusercontent.com/zahahah/LightningDSP/master/update.json"
        val apiUrl = "https://api.github.com/repos/zahahah/LightningDSP/releases/latest"
        val metadataUrl = "https://raw.githubusercontent.com/zahahah/LightningDSP/v6.7/module.prop"
        fun check(remote: String) = ModuleUpdateCheck.findUpdate(installed) { url ->
            when (url) {
                feedUrl -> feed()
                apiUrl -> release()
                metadataUrl -> remote
                else -> null
            }
        }
        assertNull(check("id=ainur_lightningdsp\nversion=v6.8\nversionCode=159"))
        assertNull(check("id=ainur_lightningdsp\nversion=v6.7\nversionCode=208"))
        assertNull(check("invalid"))
        assertEquals("42" to "v6.7", check("id=ainur_lightningdsp\nversion=v6.7\nversionCode=159"))
        assertNull(ModuleUpdateCheck.findUpdate("invalid") { feed() })
    }

    @Test fun moduleMetadataMustBeUnambiguous() {
        assertEquals(208, ModuleUpdateCheck.moduleCode("id=ainur_lightningdsp\nversionCode=208"))
        assertNull(ModuleUpdateCheck.moduleCode("id=other\nversionCode=208"))
        assertNull(ModuleUpdateCheck.moduleCode("id=ainur_lightningdsp\nversionCode=208\nversionCode=209"))
        assertNull(ModuleUpdateCheck.moduleCode("id=ainur_lightningdsp\nversionCode=nope"))
        assertNull(ModuleUpdateCheck.moduleCode("id=ainur_lightningdsp\nversion=v6.8\nversionCode=208", "v6.7"))
    }

    @Test fun updateRequiresFeedLatestReleaseAndModuleMetadataToAgree() {
        val apiUrl = "https://api.github.com/repos/zahahah/LightningDSP/releases/latest"
        val feedUrl = "https://raw.githubusercontent.com/zahahah/LightningDSP/master/update.json"
        val propUrl = "https://raw.githubusercontent.com/zahahah/LightningDSP/v6.7/module.prop"
        val payloads = mapOf(
            feedUrl to feed(),
            apiUrl to release(),
            propUrl to "id=ainur_lightningdsp\nversion=v6.7\nversionCode=159\n"
        )
        val fetch: (String) -> String? = { payloads[it] }

        assertEquals("42" to "v6.7", ModuleUpdateCheck.findUpdate(
            "id=ainur_lightningdsp\nversion=v6.6\nversionCode=150", fetch))
        assertNull(ModuleUpdateCheck.findUpdate(
            "id=ainur_lightningdsp\nversion=v6.7\nversionCode=159", fetch))
        // The experimental module is newer than the stable channel; never offer a downgrade.
        assertNull(ModuleUpdateCheck.findUpdate(
            "id=ainur_lightningdsp\nversion=v6.7.1-test\nversionCode=208", fetch))
        assertNull(ModuleUpdateCheck.findUpdate(
            "id=other\nversion=v6.6\nversionCode=150", fetch))

        assertNull(ModuleUpdateCheck.findUpdate(
            "id=ainur_lightningdsp\nversionCode=150", fetch = { url ->
                if (url == feedUrl) feed(version = "v6.8", code = 160) else payloads[url]
            }))
    }
}
