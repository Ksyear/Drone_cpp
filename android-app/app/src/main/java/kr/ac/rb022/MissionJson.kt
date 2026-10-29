package kr.ac.rb022

/** Strict JSON subset for the mission channel: no NaN/Infinity, duplicate keys, comments or deep nesting. */
object MissionJson {
    const val MAX_BYTES = 2048
    private const val MAX_DEPTH = 3
    private const val MAX_STRING = 64

    fun parseObject(text: String): Map<String, Any?> {
        require(text.length <= MAX_BYTES) { "message-too-large" }
        val parser = Parser(text)
        val value = parser.value(0)
        parser.skipSpace()
        require(parser.done()) { "trailing-data" }
        @Suppress("UNCHECKED_CAST")
        return value as? Map<String, Any?> ?: throw IllegalArgumentException("not-object")
    }

    fun quote(value: String): String = buildString {
        append('"')
        for (c in value) when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c.code < 0x20 -> append("\\u%04x".format(c.code))
            else -> append(c)
        }
        append('"')
    }

    fun number(value: Double): String {
        require(value.isFinite()) { "non-finite" }
        return "%.4f".format(java.util.Locale.ROOT, value).trimEnd('0').trimEnd('.').ifEmpty { "0" }
    }

    fun vector(v: Vec3): String = "[${number(v.x)},${number(v.y)},${number(v.z)}]"

    private class Parser(private val s: String) {
        private var i = 0
        fun done() = i == s.length
        fun skipSpace() { while (i < s.length && s[i] in " \t\r\n") i++ }

        fun value(depth: Int): Any? {
            skipSpace()
            require(i < s.length) { "unexpected-end" }
            return when (s[i]) {
                '{' -> obj(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> number()
            }
        }

        private fun obj(depth: Int): Map<String, Any?> {
            require(depth <= MAX_DEPTH) { "too-deep" }
            i++
            val out = LinkedHashMap<String, Any?>()
            skipSpace()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                skipSpace()
                require(i < s.length && s[i] == '"') { "expected-key" }
                val key = string()
                require(key !in out) { "duplicate-key" }
                skipSpace()
                require(i < s.length && s[i] == ':') { "expected-colon" }
                i++
                out[key] = value(depth)
                require(out.size <= 16) { "too-many-fields" }
                skipSpace()
                require(i < s.length) { "unexpected-end" }
                when (s[i++]) {
                    ',' -> continue
                    '}' -> return out
                    else -> throw IllegalArgumentException("expected-comma")
                }
            }
        }

        private fun array(depth: Int): List<Any?> {
            require(depth <= MAX_DEPTH) { "too-deep" }
            i++
            val out = ArrayList<Any?>()
            skipSpace()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                out.add(value(depth))
                require(out.size <= 32) { "array-too-long" }
                skipSpace()
                require(i < s.length) { "unexpected-end" }
                when (s[i++]) {
                    ',' -> continue
                    ']' -> return out
                    else -> throw IllegalArgumentException("expected-comma")
                }
            }
        }

        private fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated-string" }
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        require(i < s.length) { "bad-escape" }
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'n' -> out.append('\n')
                            't' -> out.append('\t')
                            'r' -> out.append('\r')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'u' -> {
                                require(i + 4 <= s.length) { "bad-escape" }
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw IllegalArgumentException("bad-escape"))
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad-escape")
                        }
                    }
                    c.code < 0x20 -> throw IllegalArgumentException("control-char")
                    else -> out.append(c)
                }
                require(out.length <= MAX_STRING) { "string-too-long" }
            }
        }

        private fun literal(word: String, value: Any?): Any? {
            require(s.startsWith(word, i)) { "bad-literal" }
            i += word.length
            return value
        }

        private fun number(): Double {
            val start = i
            if (i < s.length && s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val text = s.substring(start, i)
            require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(text)) { "bad-number" }
            val value = text.toDouble()
            require(value.isFinite()) { "non-finite" }
            return value
        }
    }
}
