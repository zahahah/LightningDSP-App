package lightning.dsp.model.preset

import android.content.Context
import android.content.SharedPreferences
import lightning.dsp.utils.Constants
import org.w3c.dom.Element
import org.xml.sax.SAXException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

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

    /** Snapshot loaded values, not XML files that may still have an apply() write pending. */
    fun write(preferences: SharedPreferences, output: OutputStream) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val root = document.createElement("map")
        document.appendChild(root)
        preferences.all.toSortedMap().forEach { (name, value) ->
            require(name.isNotEmpty()) { "SharedPreferences entry has no name" }
            val tag = when (value) {
                is String -> "string"
                is Boolean -> "boolean"
                is Int -> "int"
                is Long -> "long"
                is Float -> "float"
                is Set<*> -> "set"
                null -> "null"
                else -> throw IllegalArgumentException("Unsupported SharedPreferences value for $name")
            }
            require(value !is Float || value.isFinite()) { "Non-finite SharedPreferences value for $name" }
            val entry = document.createElement(tag)
            entry.setAttribute("name", name)
            when (value) {
                is String -> entry.appendChild(document.createTextNode(value))
                is Set<*> -> value.toSet().forEach { item ->
                    require(item is String) { "Unsupported SharedPreferences set entry" }
                    entry.appendChild(document.createElement("string").apply {
                        appendChild(document.createTextNode(item))
                    })
                }
                null -> Unit
                else -> entry.setAttribute("value", value.toString())
            }
            root.appendChild(entry)
        }
        TransformerFactory.newInstance().newTransformer().apply {
            setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        }.transform(DOMSource(document), StreamResult(output))
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

    /** A preset is a complete desired DSP state, even when an older archive omits default namespaces. */
    fun completeSnapshot(
        values: Map<String, List<Value>>,
        additionalNamespaces: Collection<String> = emptyList(),
    ): Map<String, List<Value>> =
        (Constants.DSP_PREFERENCE_NAMESPACES + additionalNamespaces + values.keys).distinct()
            .associateWith { values[it].orEmpty() }

    /** Restore loaded values if any namespace commit or follow-up preparation fails. */
    fun replaceAll(
        values: Map<String, List<Value>>,
        preferencesFor: (String) -> SharedPreferences,
        afterApply: () -> Unit = {},
    ) {
        val targets = values.keys.associateWith(preferencesFor)
        val previous = targets.mapValues { (_, preferences) -> snapshot(preferences) }
        try {
            values.forEach { (namespace, entries) -> apply(targets.getValue(namespace), entries, namespace) }
            afterApply()
        } catch (failure: Exception) {
            previous.forEach { (namespace, entries) ->
                try {
                    apply(targets.getValue(namespace), entries, namespace)
                } catch (rollbackFailure: Exception) {
                    failure.addSuppressed(rollbackFailure)
                }
            }
            throw failure
        }
    }

    private fun snapshot(preferences: SharedPreferences): List<Value> = preferences.all.map { (name, value) ->
        when (value) {
            is String -> Value.StringValue(name, value)
            is Boolean -> Value.BooleanValue(name, value)
            is Int -> Value.IntValue(name, value)
            is Long -> Value.LongValue(name, value)
            is Float -> Value.FloatValue(name, value)
            is Set<*> -> Value.StringSetValue(name, value.map { item ->
                require(item is String) { "Unsupported SharedPreferences set entry" }
                item
            }.toSet())
            null -> Value.NullValue(name)
            else -> throw IllegalArgumentException("Unsupported SharedPreferences value for $name")
        }
    }

    fun apply(context: Context, namespace: String, values: List<Value>) =
        apply(context.getSharedPreferences(namespace, Context.MODE_PRIVATE), values, namespace)

    private fun apply(preferences: SharedPreferences, values: List<Value>, namespace: String) {
        val editor = preferences.edit().clear()
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
