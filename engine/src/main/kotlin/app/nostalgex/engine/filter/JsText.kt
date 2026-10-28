package app.nostalgex.engine.filter

import java.util.Locale

/**
 * String operations with JavaScript's exact semantics, so the port answers as upstream's JS
 * engines do on every input. Regex classes are written out rather than left to `\b`, `\d`, `\s`
 * and `\w`: Android's regex engine is ICU, where those are Unicode-aware, while in a JS regex
 * without the `u` flag they are ASCII (`\s` excepted, which is the JS whitespace set below).
 */
object JsText {

    /** JS `toLowerCase()`: locale-independent full Unicode mapping. */
    fun lower(s: String): String = s.lowercase(Locale.ROOT)

    /** JS `toUpperCase()`: locale-independent full mapping (ß becomes SS). */
    fun upper(s: String): String = s.uppercase(Locale.ROOT)

    /** JS `\w`: [A-Za-z0-9_]. */
    fun isWordChar(c: Char): Boolean =
        (c in 'a'..'z') || (c in 'A'..'Z') || (c in '0'..'9') || c == '_'

    /** JS `\s` and the characters `String.prototype.trim` removes. */
    fun isJsSpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', ' ', ' ', ' ',
        ' ', ' ', ' ', ' ', '　', '﻿' -> true
        else -> c in ' '..' '
    }

    fun trim(s: String): String {
        var a = 0
        var b = s.length
        while (a < b && isJsSpace(s[a])) a++
        while (b > a && isJsSpace(s[b - 1])) b--
        return s.substring(a, b)
    }

    /** `s.replace(/\s+/g, "")`. */
    fun removeSpaces(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) if (!isJsSpace(c)) sb.append(c)
        return sb.toString()
    }

    /**
     * `new RegExp("\\b" + escape(needle.toLowerCase()) + "\\b").test(haystack)`: the needle as a
     * literal, with a word boundary (ASCII `\w` on one side and not the other) at both ends.
     * The haystack is taken as given; callers lowercase it.
     */
    fun containsWord(haystack: String, needle: String): Boolean = containsLoweredWord(haystack, lower(needle))

    /** [containsWord] for a needle already lowercased. */
    fun containsLoweredWord(haystack: String, n: String): Boolean {
        if (n.isEmpty()) return false
        val len = n.length
        var from = 0
        while (true) {
            val i = haystack.indexOf(n, from)
            if (i < 0) return false
            val j = i + len
            val startOk = isBoundary(haystack, i)
            if (startOk && isBoundary(haystack, j)) return true
            from = i + 1
        }
    }

    private fun isBoundary(s: String, p: Int): Boolean {
        val before = p > 0 && isWordChar(s[p - 1])
        val after = p < s.length && isWordChar(s[p])
        return before != after
    }

    /** `s.toLowerCase().replace(/ \(\d{4}\)$/, "")`. */
    fun stripYearSuffix(s: String): String {
        val l = lower(s)
        val n = l.length
        if (n >= 7 && l[n - 1] == ')' && l[n - 7] == ' ' && l[n - 6] == '(' &&
            (n - 5 until n - 1).all { l[it] in '0'..'9' }
        ) return l.substring(0, n - 7)
        return l
    }

    /** `parseInt(String(s), 10)` for a value already known to be a string. */
    fun parseIntJs(s: String): Int? {
        var i = 0
        while (i < s.length && isJsSpace(s[i])) i++
        var neg = false
        if (i < s.length && (s[i] == '+' || s[i] == '-')) { neg = s[i] == '-'; i++ }
        val start = i
        while (i < s.length && s[i] in '0'..'9') i++
        if (i == start) return null
        val v = s.substring(start, i).toLongOrNull() ?: return null
        return (if (neg) -v else v).toInt()
    }

    /** Leading `YYYY-MM-DD` of a date string, as `/^(\d{4})-(\d{2})-(\d{2})/` reads it. */
    fun leadingDate(s: String?): Triple<Int, Int, Int>? {
        if (s == null || s.length < 10) return null
        for (k in 0 until 10) {
            val c = s[k]
            val ok = if (k == 4 || k == 7) c == '-' else c in '0'..'9'
            if (!ok) return null
        }
        return Triple(s.substring(0, 4).toInt(), s.substring(5, 7).toInt(), s.substring(8, 10).toInt())
    }
}
