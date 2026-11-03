package dev.rboza.android.opensyncforproton.crypto

/**
 * A small JSON reader for the committed fixtures: Android's org.json is only a stub in JVM tests, and :crypto has no
 * JSON library. Objects become maps, arrays lists, numbers doubles.
 */
internal fun parseJson(text: String): Any? = JsonReader(text).run { value().also { skipSpace(); check(i == text.length) } }

@Suppress("UNCHECKED_CAST")
internal fun Any?.obj(name: String) = (this as Map<String, Any?>).getValue(name)

@Suppress("UNCHECKED_CAST")
internal fun Any?.list() = this as List<Any?>

private class JsonReader(private val text: String) {
    var i = 0

    fun skipSpace() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    fun value(): Any? {
        skipSpace()
        return when (text[i]) {
            '{' -> members()
            '[' -> elements()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> number()
        }
    }

    private fun members(): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        i++
        skipSpace()
        if (text[i] == '}') return map.also { i++ }
        while (true) {
            skipSpace()
            val key = string()
            skipSpace()
            check(text[i++] == ':')
            map[key] = value()
            skipSpace()
            if (text[i++] == '}') return map
        }
    }

    private fun elements(): List<Any?> {
        val list = mutableListOf<Any?>()
        i++
        skipSpace()
        if (text[i] == ']') return list.also { i++ }
        while (true) {
            list += value()
            skipSpace()
            if (text[i++] == ']') return list
        }
    }

    private fun string(): String {
        check(text[i++] == '"')
        val out = StringBuilder()
        while (true) {
            val c = text[i++]
            when (c) {
                '"' -> return out.toString()
                '\\' -> when (val e = text[i++]) {
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000c')
                    'u' -> out.append(text.substring(i, i + 4).toInt(16).toChar()).also { i += 4 }
                    else -> out.append(e)
                }
                else -> out.append(c)
            }
        }
    }

    private fun literal(word: String, value: Any?): Any? {
        check(text.startsWith(word, i))
        i += word.length
        return value
    }

    private fun number(): Double {
        val start = i
        while (i < text.length && (text[i].isDigit() || text[i] in "+-.eE")) i++
        return text.substring(start, i).toDouble()
    }
}
