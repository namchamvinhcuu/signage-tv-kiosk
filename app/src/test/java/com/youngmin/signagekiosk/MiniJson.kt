package com.youngmin.signagekiosk

/**
 * Strict RFC 8259 parser for tests only (no JSON library on the JVM test classpath; org.json in
 * android.jar is a stub). Objects keep key order; numbers are returned as [Num] so tests can check
 * both the raw text and the value. Throws [IllegalArgumentException] on any deviation.
 */
internal object MiniJson {
    data class Num(val raw: String) {
        fun toDouble(): Double = raw.toDouble()
    }

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        require(p.i == text.length) { "trailing data at ${p.i}" }
        return v
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && s[i] in " \t\n\r") i++
        }

        fun value(): Any? {
            require(i < s.length) { "unexpected end" }
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c in '0'..'9') num() else throw IllegalArgumentException("bad char '$c' at $i")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            require(s.startsWith(word, i)) { "bad literal at $i" }
            i += word.length
            return v
        }

        private fun num(): Num {
            val m = NUMBER.matchAt(s, i) ?: throw IllegalArgumentException("bad number at $i")
            i += m.value.length
            return Num(m.value)
        }

        private fun obj(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            i++
            ws()
            if (s.getOrNull(i) == '}') { i++; return out }
            while (true) {
                ws()
                require(s.getOrNull(i) == '"') { "expected key at $i" }
                val k = str()
                require(!out.containsKey(k)) { "duplicate key $k" }
                ws()
                require(s.getOrNull(i) == ':') { "expected ':' at $i" }
                i++
                ws()
                out[k] = value()
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $i")
                }
            }
        }

        private fun arr(): List<Any?> {
            val out = ArrayList<Any?>()
            i++
            ws()
            if (s.getOrNull(i) == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                when (s.getOrNull(i)) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $i")
                }
            }
        }

        private fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(i < s.length) { "unterminated string" }
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        require(i < s.length) { "bad escape" }
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
                                require(i + 4 <= s.length) { "short \\u escape" }
                                val hex = s.substring(i, i + 4)
                                require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "bad hex $hex" }
                                sb.append(hex.toInt(16).toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw IllegalArgumentException("raw control char ${c.code} in string")
                    else -> sb.append(c)
                }
            }
        }
    }
}
