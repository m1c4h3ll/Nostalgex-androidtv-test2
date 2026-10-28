package app.nostalgex.engine.json

/**
 * A small, dependency-free JSON reader and writer.
 *
 * Values are plain Kotlin types: [Map] (insertion ordered), [List], [String], [Long] for whole
 * numbers that fit, [Double] for the rest, [Boolean] and `null`. The engine reads channels.json,
 * the membership manifest and the golden vectors with it, and the app reads server answers and
 * writes its caches with it, so the engine has no third-party dependency and can be tested on a
 * bare JVM.
 */
object Json {

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.value()
        p.skipWs()
        if (p.i != text.length) throw JsonException("Trailing characters at ${p.i}")
        return v
    }

    /** Like [parse], but `null` instead of an exception for text that is not JSON. */
    fun parseOrNull(text: String?): Any? = try {
        if (text.isNullOrEmpty()) null else parse(text)
    } catch (_: JsonException) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    fun stringify(value: Any?): String {
        val sb = StringBuilder()
        write(sb, value)
        return sb.toString()
    }

    fun write(sb: StringBuilder, value: Any?) {
        when (value) {
            null -> sb.append("null")
            is String -> quote(sb, value)
            is Boolean -> sb.append(if (value) "true" else "false")
            is Int, is Long, is Short, is Byte -> sb.append(value.toString())
            is Double -> if (value.isFinite()) {
                if (value == Math.floor(value) && Math.abs(value) < 9.0e15) sb.append(value.toLong().toString())
                else sb.append(value.toString())
            } else sb.append("null")
            is Float -> write(sb, value.toDouble())
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    write(sb, v)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    write(sb, v)
                }
                sb.append(']')
            }
            is Array<*> -> write(sb, value.asList())
            is IntArray -> write(sb, value.asList())
            is LongArray -> write(sb, value.asList())
            else -> quote(sb, value.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (c < ' ' || c == ' ' || c == ' ') {
                    sb.append("\\u")
                    sb.append(String.format("%04x", c.code))
                } else sb.append(c)
            }
        }
        sb.append('"')
    }

    private class Parser(val s: String) {
        var i = 0

        fun skipWs() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') i++ else break
            }
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonException("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || (c in '0'..'9')) num() else throw JsonException("Unexpected '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonException("Bad literal at $i")
            i += word.length
            return v
        }

        private fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            i++ // {
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                skipWs()
                if (i >= s.length || s[i] != '"') throw JsonException("Expected key at $i")
                val k = str()
                skipWs()
                if (i >= s.length || s[i] != ':') throw JsonException("Expected ':' at $i")
                i++
                skipWs()
                m[k] = value()
                skipWs()
                if (i >= s.length) throw JsonException("Unexpected end in object")
                when (s[i]) {
                    ',' -> i++
                    '}' -> { i++; return m }
                    else -> throw JsonException("Expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val l = ArrayList<Any?>()
            i++ // [
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                skipWs()
                l.add(value())
                skipWs()
                if (i >= s.length) throw JsonException("Unexpected end in array")
                when (s[i]) {
                    ',' -> i++
                    ']' -> { i++; return l }
                    else -> throw JsonException("Expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val start = i
            // Fast path: no escapes.
            while (i < s.length) {
                val c = s[i]
                if (c == '"') { val r = s.substring(start, i); i++; return r }
                if (c == '\\') break
                i++
            }
            val sb = StringBuilder().append(s, start, i)
            while (i < s.length) {
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException("Bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> throw JsonException("Bad escape '\\$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw JsonException("Unterminated string")
        }

        private fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && s[i] in '0'..'9') i++
            var isInt = true
            if (i < s.length && s[i] == '.') {
                isInt = false
                i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                isInt = false
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                while (i < s.length && s[i] in '0'..'9') i++
            }
            val t = s.substring(start, i)
            if (isInt) {
                t.toLongOrNull()?.let { return it }
            }
            return t.toDouble()
        }
    }
}

class JsonException(message: String) : RuntimeException(message)

// ── Accessors: lenient reads off parsed trees ───────────────────────────────

@Suppress("UNCHECKED_CAST")
fun Any?.asObj(): Map<String, Any?>? = this as? Map<String, Any?>

fun Any?.asArr(): List<Any?>? = this as? List<Any?>

fun Map<String, Any?>?.obj(key: String): Map<String, Any?>? = this?.get(key).asObj()

fun Map<String, Any?>?.arr(key: String): List<Any?>? = this?.get(key).asArr()

fun Map<String, Any?>?.objs(key: String): List<Map<String, Any?>> = this?.get(key).asArr()?.mapNotNull { it.asObj() } ?: emptyList()

/** A string value, or a number written as a string (Plex sends some ids either way). */
fun Map<String, Any?>?.str(key: String): String? = when (val v = this?.get(key)) {
    is String -> v
    is Long -> v.toString()
    is Double -> if (v == Math.floor(v) && Math.abs(v) < 9.0e15) v.toLong().toString() else v.toString()
    is Boolean -> v.toString()
    else -> null
}

fun Map<String, Any?>?.strings(key: String): List<String> =
    this?.get(key).asArr()?.mapNotNull { it as? String } ?: emptyList()

fun Map<String, Any?>?.long(key: String): Long? = when (val v = this?.get(key)) {
    is Long -> v
    is Double -> v.toLong()
    is String -> v.trim().toLongOrNull()
    else -> null
}

fun Map<String, Any?>?.int(key: String): Int? = long(key)?.toInt()

fun Map<String, Any?>?.double(key: String): Double? = when (val v = this?.get(key)) {
    is Long -> v.toDouble()
    is Double -> v
    is String -> v.trim().toDoubleOrNull()
    else -> null
}

/** A JSON number only (no strings), as JS `typeof v === "number"` reads it. */
fun Map<String, Any?>?.number(key: String): Double? = when (val v = this?.get(key)) {
    is Long -> v.toDouble()
    is Double -> v
    else -> null
}

fun Map<String, Any?>?.bool(key: String): Boolean? = this?.get(key) as? Boolean

/** JS truthiness of a parsed value. */
fun truthy(v: Any?): Boolean = when (v) {
    null -> false
    is Boolean -> v
    is String -> v.isNotEmpty()
    is Long -> v != 0L
    is Double -> v != 0.0 && !v.isNaN()
    else -> true
}
