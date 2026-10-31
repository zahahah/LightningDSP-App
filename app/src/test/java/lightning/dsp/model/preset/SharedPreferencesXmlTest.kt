package lightning.dsp.model.preset

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

class SharedPreferencesXmlTest {
    @Test
    fun parsesAllAndroidSharedPreferencesValueTypes() {
        val xml = """
            <map>
                <string name="text">speaker &amp; headphones</string>
                <boolean name="enabled" value="false" />
                <int name="count" value="7" />
                <long name="timestamp" value="1234567890123" />
                <float name="gain" value="1.25" />
                <set name="items"><string>left</string><string>right</string></set>
                <null name="removed" />
            </map>
        """.trimIndent().byteInputStream()

        assertEquals(
            listOf(
                SharedPreferencesXml.Value.StringValue("text", "speaker & headphones"),
                SharedPreferencesXml.Value.BooleanValue("enabled", false),
                SharedPreferencesXml.Value.IntValue("count", 7),
                SharedPreferencesXml.Value.LongValue("timestamp", 1234567890123),
                SharedPreferencesXml.Value.FloatValue("gain", 1.25f),
                SharedPreferencesXml.Value.StringSetValue("items", setOf("left", "right")),
                SharedPreferencesXml.Value.NullValue("removed"),
            ),
            SharedPreferencesXml.parse(xml),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnsupportedPreferenceValueTypes() {
        SharedPreferencesXml.parse(ByteArrayInputStream("<map><double name=\"gain\" value=\"1\"/></map>".toByteArray()))
    }

    @Test(expected = Exception::class)
    fun rejectsDocumentTypeDeclarations() {
        SharedPreferencesXml.parse(
            ByteArrayInputStream("<!DOCTYPE map [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><map/>".toByteArray())
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInternalEntityDeclarationsBeforeParsing() {
        SharedPreferencesXml.parse(
            "<!DOCTYPE map [<!ENTITY value 'expanded'>]><map><string name='x'>&value;</string></map>"
                .byteInputStream()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUtf16DocumentTypeDeclarations() {
        SharedPreferencesXml.parse(
            "<!DOCTYPE map [<!ENTITY value 'expanded'>]><map/>"
                .toByteArray(Charsets.UTF_16LE).inputStream()
        )
    }
}
