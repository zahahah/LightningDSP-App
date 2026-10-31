package lightning.dsp.model.preset

import android.content.Context
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.InputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Reads the SharedPreferences XML format used inside existing preset archives. */
internal object SharedPreferencesXml {
    internal sealed interface Value {
        val name: String
        data class StringValue(override val name: String, val value: String) : Value
        data class BooleanValue(override val name: String, val value: Boolean) : Value
        data class IntValue(override val name: String, val value: Int) : Value
        data class LongValue(override val name: String, val value: Long) : Value
        data class FloatValue(override val name: String, val value: Float) : Value
        data class StringSetValue(override val name: String, val value: Set<String>) : Value
        data class NullValue(override val name: String) : Value
    }

    fun parse(input: InputStream): List<Value> {
        val xml = input.readBytes()
        require(!containsDoctype(xml)) { "DOCTYPE is not allowed in preset preferences" }
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        builder.setEntityResolver { _, _ -> throw SAXException("External XML entities are not allowed") }
        val document = builder.parse(ByteArrayInputStream(xml))
        require(document.doctype == null) { "DOCTYPE is not allowed in preset preferences" }
        val root = document.documentElement
        require(root.tagName == "map") { "SharedPreferences XML root must be <map>" }

        return buildList {
            val children = root.childNodes
            for (index in 0 until children.length) {
                val element = children.item(index) as? Element ?: continue
                val name = element.getAttribute("name")
                require(name.isNotEmpty()) { "SharedPreferences entry has no name" }
                add(when (element.tagName) {
                    "string" -> Value.StringValue(name, element.textContent ?: "")
                    "boolean" -> Value.BooleanValue(name, element.getAttribute("value").toBooleanStrict())
                    "int" -> Value.IntValue(name, element.getAttribute("value").toInt())
                    "long" -> Value.LongValue(name, element.getAttribute("value").toLong())
                    "float" -> Value.FloatValue(name, element.getAttribute("value").toFloat())
                    "null" -> Value.NullValue(name)
                    "set" -> Value.StringSetValue(name, element.childNodes.let { nodes ->
                        buildSet {
                            for (childIndex in 0 until nodes.length) {
                                val child = nodes.item(childIndex) as? Element ?: continue
                                require(child.tagName == "string") { "Unsupported SharedPreferences set entry" }
                                add(child.textContent ?: "")
                            }
                        }
                    })
                    else -> throw IllegalArgumentException("Unsupported SharedPreferences value: ${element.tagName}")
                })
            }
        }
    }

    // Android's DOM parser does not support the Xerces disallow-doctype-decl
    // feature. Reject declarations before parsing, including XML's UTF-16/32
    // encodings, so entity expansion cannot run before the DOM is inspected.
    private fun containsDoctype(xml: ByteArray): Boolean {
        val marker = "<!DOCTYPE".toByteArray(Charsets.US_ASCII)
        val encodings = listOf(
            marker,
            marker.flatMap { listOf(it, 0.toByte()) }.toByteArray(),
            marker.flatMap { listOf(0.toByte(), it) }.toByteArray(),
            marker.flatMap { listOf(it, 0.toByte(), 0.toByte(), 0.toByte()) }.toByteArray(),
            marker.flatMap { listOf(0.toByte(), 0.toByte(), 0.toByte(), it) }.toByteArray(),
        )
        return encodings.any { encoded ->
            xml.indices.any { index ->
                index + encoded.size <= xml.size && encoded.indices.all { xml[index + it] == encoded[it] }
            }
        }
    }

    fun apply(context: Context, namespace: String, values: List<Value>) {
        val editor = context.getSharedPreferences(namespace, Context.MODE_PRIVATE).edit().clear()
        values.forEach { value ->
            when (value) {
                is Value.StringValue -> editor.putString(value.name, value.value)
                is Value.BooleanValue -> editor.putBoolean(value.name, value.value)
                is Value.IntValue -> editor.putInt(value.name, value.value)
                is Value.LongValue -> editor.putLong(value.name, value.value)
                is Value.FloatValue -> editor.putFloat(value.name, value.value)
                is Value.StringSetValue -> editor.putStringSet(value.name, value.value)
                is Value.NullValue -> editor.remove(value.name)
            }
        }
        check(editor.commit()) { "Failed to apply preferences for $namespace" }
    }
}
