package dev.rboza.android.opensyncforproton.build

// Reads `aapt2 dump` output, so the manifest check sees the resources that actually ship in the APK
// (after source-set overrides, qualifier folders, library resources, and R8's path shortening).

/**
 * One version of a resource: its qualifiers ("" for the default) and its file inside the APK, or null
 * when that version is an alias of another resource.
 */
data class ResourceFile(val version: String, val path: String?)

private val resourceLine = Regex("""^\s*resource 0x[0-9a-f]+ (\w+)/(\S+)$""")
private val typeLine = Regex("""^\s*type \S+ id=""")
private val fileLine = Regex("""^\s*\(([^)]*)\) \(file\) (\S+) type=XML$""")
private val aliasLine = Regex("""^\s*\(([^)]*)\) @\S+$""")

/** From `aapt2 dump resources`: every version of each `xml` resource. Any other line under one fails. */
fun xmlResourceFiles(resourceTable: String): Map<String, List<ResourceFile>> {
    val files = linkedMapOf<String, MutableList<ResourceFile>>()
    var current: String? = null
    resourceTable.lines().filter { it.isNotBlank() }.forEach { line ->
        resourceLine.find(line)?.let { current = if (it.groupValues[1] == "xml") it.groupValues[2] else null; return@forEach }
        if (typeLine.containsMatchIn(line)) { current = null; return@forEach }
        val name = current ?: return@forEach
        val file = fileLine.find(line)
        val alias = aliasLine.find(line)
        files.getOrPut(name) { mutableListOf() } += when {
            file != null -> ResourceFile(file.groupValues[1], file.groupValues[2])
            alias != null -> ResourceFile(alias.groupValues[1], null)
            else -> throw IllegalArgumentException("unexpected line under xml/$name")
        }
    }
    return files
}

private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
private val elementLine = Regex("""^E: (\S+) \(line=\d+\)$""")
private val attributeLine = Regex("""^A: ([^=(]+)(?:\(0x[0-9a-f]+\))?=(.*)$""")
private val textLine = Regex("""^T: '(.*)'$""")

private class Node(val indent: Int, val name: String) {
    val attributes = mutableListOf<Pair<String, String>>()
    val children = mutableListOf<Any>() // Node or text
}

/**
 * From `aapt2 dump xmltree`: the same document as XML text. Compiled values come back as `aapt2`
 * prints them (`false`, `@0x7f010000`). Anything unexpected fails, so a check never reads a partial file.
 */
fun xmltreeToXml(dump: String): String {
    var root: Node? = null
    val open = ArrayDeque<Node>()
    dump.lines().filter { it.isNotBlank() }.forEach { raw ->
        val indent = raw.length - raw.trimStart().length
        val line = raw.trim()
        if (line.startsWith("N: ")) return@forEach
        while (open.isNotEmpty() && open.last().indent >= indent) open.removeLast()
        val element = elementLine.find(line)
        if (element != null) {
            val node = Node(indent, element.groupValues[1])
            val parent = open.lastOrNull()
            when {
                parent != null -> parent.children += node
                root == null -> root = node
                else -> throw IllegalArgumentException("more than one root element")
            }
            open.addLast(node)
            return@forEach
        }
        val parent = open.lastOrNull() ?: throw IllegalArgumentException("content outside an element")
        val attribute = attributeLine.find(line)
        val text = textLine.find(line)
        when {
            attribute != null -> parent.attributes += attributeName(attribute.groupValues[1]) to attributeValue(attribute.groupValues[2])
            text != null -> parent.children += unescape(text.groupValues[1])
            else -> throw IllegalArgumentException("unexpected xmltree line")
        }
    }
    val document = root ?: throw IllegalArgumentException("no root element")
    return buildString { write(document, isRoot = true) }
}

private fun attributeName(name: String): String = when {
    name.startsWith("$ANDROID_NS:") -> "android:" + name.removePrefix("$ANDROID_NS:")
    ':' in name -> throw IllegalArgumentException("unexpected attribute namespace")
    else -> name
}

/**
 * A string prints as `"v" (Raw: "v")` and `aapt2` doesn't escape quotes, so the value is taken only if
 * that exact form rebuilds; a compiled value (`false`, `@0x7f010000`) is a single bare token.
 */
private fun attributeValue(value: String): String {
    if (!value.startsWith('"')) {
        require(value.isNotEmpty() && value.none { it == '"' || it.isWhitespace() }) { "unexpected attribute value" }
        return value
    }
    val length = (value.length - "\"\" (Raw: \"\")".length) / 2
    val text = value.substring(1, 1 + length.coerceAtLeast(0))
    require(length >= 0 && value == "\"$text\" (Raw: \"$text\")") { "attribute value doesn't match its raw form" }
    return text
}

private fun unescape(text: String): String = Regex("""\\(.)""").replace(text) { it.groupValues[1] }

private fun StringBuilder.write(node: Node, isRoot: Boolean = false) {
    append('<').append(node.name)
    if (isRoot && usesAndroidNamespace(node)) append(" xmlns:android=\"").append(ANDROID_NS).append('"')
    node.attributes.forEach { (name, value) -> append(' ').append(name).append("=\"").append(escape(value, attribute = true)).append('"') }
    if (node.children.isEmpty()) {
        append("/>")
        return
    }
    append('>')
    node.children.forEach { if (it is Node) write(it) else append(escape(it as String, attribute = false)) }
    append("</").append(node.name).append('>')
}

private fun usesAndroidNamespace(node: Node): Boolean =
    node.attributes.any { it.first.startsWith("android:") } || node.children.filterIsInstance<Node>().any(::usesAndroidNamespace)

private fun escape(text: String, attribute: Boolean): String {
    val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    return if (attribute) escaped.replace("\"", "&quot;") else escaped
}
