package dev.attrkit.core

import java.time.Instant
import java.time.format.DateTimeFormatter

internal object Json {
    fun stringify(value: Any?): String = buildString { appendValue(value) }

    /**
     * The reader the writer never had, for the one body this SDK must read back: the attribution
     * answer. Objects come back as `Map<String, Any?>`, arrays as `List<Any?>`, numbers as `Long`
     * when written without a fraction or exponent and as `Double` otherwise. It refuses anything
     * that is not exactly one JSON value, and nesting past [MAX_DEPTH], because the bytes come off
     * a network we do not control and a hostile or corrupted body must fail rather than recurse
     * until the host app's stack is gone.
     */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(0)
        reader.skipWhitespace()
        require(reader.atEnd()) { "trailing characters after the JSON value" }
        return value
    }

    private const val MAX_DEPTH = 32

    private class Reader(private val text: String) {
        private var position = 0

        fun atEnd(): Boolean = position >= text.length

        fun skipWhitespace() {
            while (position < text.length && text[position].let { it == ' ' || it == '\t' || it == '\n' || it == '\r' }) {
                position += 1
            }
        }

        fun readValue(depth: Int): Any? {
            require(depth <= MAX_DEPTH) { "JSON nesting is too deep" }
            require(!atEnd()) { "unexpected end of JSON" }
            return when (val character = text[position]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> {
                    require(character == '-' || character in '0'..'9') { "unexpected character in JSON" }
                    readNumber()
                }
            }
        }

        private fun readLiteral(word: String, value: Any?): Any? {
            require(text.startsWith(word, position)) { "invalid JSON literal" }
            position += word.length
            return value
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            position += 1
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                position += 1
                return result
            }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "JSON object keys must be strings" }
                val key = readString()
                skipWhitespace()
                require(peek() == ':') { "expected ':' in JSON object" }
                position += 1
                skipWhitespace()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    '}' -> {
                        position += 1
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' in JSON object")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            position += 1
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                position += 1
                return result
            }
            while (true) {
                skipWhitespace()
                result += readValue(depth + 1)
                skipWhitespace()
                when (peek()) {
                    ',' -> position += 1
                    ']' -> {
                        position += 1
                        return result
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' in JSON array")
                }
            }
        }

        private fun peek(): Char {
            require(!atEnd()) { "unexpected end of JSON" }
            return text[position]
        }

        private fun readString(): String {
            position += 1
            val builder = StringBuilder()
            while (true) {
                require(!atEnd()) { "unterminated JSON string" }
                val character = text[position]
                position += 1
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(readEscape())
                    character.code < 0x20 -> throw IllegalArgumentException("control character in JSON string")
                    else -> builder.append(character)
                }
            }
        }

        private fun readEscape(): Char {
            require(!atEnd()) { "unterminated JSON escape" }
            val escape = text[position]
            position += 1
            return when (escape) {
                '"' -> '"'
                '\\' -> '\\'
                '/' -> '/'
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    require(position + 4 <= text.length) { "truncated JSON unicode escape" }
                    val code = text.substring(position, position + 4).toIntOrNull(16)
                    require(code != null) { "invalid JSON unicode escape" }
                    position += 4
                    code.toChar()
                }
                else -> throw IllegalArgumentException("invalid JSON escape")
            }
        }

        private fun readNumber(): Any {
            val start = position
            if (text[position] == '-') position += 1
            require(!atEnd() && text[position] in '0'..'9') { "invalid JSON number" }
            if (text[position] == '0') {
                position += 1
            } else {
                while (!atEnd() && text[position] in '0'..'9') position += 1
            }
            var integral = true
            if (!atEnd() && text[position] == '.') {
                integral = false
                position += 1
                require(!atEnd() && text[position] in '0'..'9') { "invalid JSON number" }
                while (!atEnd() && text[position] in '0'..'9') position += 1
            }
            if (!atEnd() && (text[position] == 'e' || text[position] == 'E')) {
                integral = false
                position += 1
                if (!atEnd() && (text[position] == '+' || text[position] == '-')) position += 1
                require(!atEnd() && text[position] in '0'..'9') { "invalid JSON number" }
                while (!atEnd() && text[position] in '0'..'9') position += 1
            }
            val literal = text.substring(start, position)
            if (integral) literal.toLongOrNull()?.let { return it }
            val parsed = literal.toDouble()
            require(parsed.isFinite()) { "JSON number is out of range" }
            return parsed
        }
    }

    private fun StringBuilder.appendValue(value: Any?) {
        when (value) {
            null -> append("null")
            is String -> appendQuoted(value)
            is Boolean -> append(if (value) "true" else "false")
            is Byte, is Short, is Int, is Long -> append(value.toString())
            is Float -> appendFiniteNumber(value.toString().toDouble()) // shortest form, not the widened expansion
            is Double -> appendFiniteNumber(value)
            is Map<*, *> -> {
                append('{')
                var first = true
                value.forEach { (key, item) ->
                    require(key is String) { "JSON object keys must be strings" }
                    if (!first) append(',')
                    first = false
                    appendQuoted(key)
                    append(':')
                    appendValue(item)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                value.forEach { item ->
                    if (!first) append(',')
                    first = false
                    appendValue(item)
                }
                append(']')
            }
            else -> error("unsupported JSON value: ${value::class.java.name}")
        }
    }

    private fun StringBuilder.appendFiniteNumber(value: Double) {
        require(value.isFinite()) { "JSON numbers must be finite" }
        if (value == Math.rint(value) && value >= Long.MIN_VALUE.toDouble() && value < Long.MAX_VALUE.toDouble()) {
            append(value.toLong())
        } else {
            append(value)
        }
    }

    private fun StringBuilder.appendQuoted(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> {
                    if (character.code < 0x20) {
                        append("\\u")
                        append(character.code.toString(16).padStart(4, '0'))
                    } else {
                        append(character)
                    }
                }
            }
        }
        append('"')
    }
}

internal fun Instant.toWireTimestamp(): String = DateTimeFormatter.ISO_INSTANT.format(this)
