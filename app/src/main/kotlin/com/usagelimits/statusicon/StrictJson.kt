package com.usagelimits.statusicon

/**
 * Checks that a text is strict JSON (RFC 8259) before a lenient reader sees it, the same check
 * the Kit's `StrictJSON` makes, so the shared glyph file is accepted or rejected alike on both
 * platforms. Rejects what the JSON readers would let through: number spellings such as `+24`,
 * `024` or `24f`, trailing commas, comments, single quotes, unquoted words, a byte-order mark,
 * duplicate keys in one object and anything after the root value.
 */
internal object StrictJson {

    private val NUMBER = Regex("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?")

    fun validate(text: String) {
        val reader = Reader(text)
        reader.skipWhitespace()
        reader.value()
        reader.skipWhitespace()
        require(reader.atEnd) { "content after the root value at ${reader.index}" }
    }

    private class Reader(val text: String) {
        var index = 0
        val atEnd get() = index == text.length

        fun skipWhitespace() {
            while (index < text.length && text[index] in " \t\n\r") index++
        }

        fun value() {
            require(!atEnd) { "a value is missing at $index" }
            when (text[index]) {
                '{' -> objectValue()
                '[' -> arrayValue()
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun objectValue() {
            index++
            val keys = HashSet<String>()
            skipWhitespace()
            if (peek() == '}') { index++; return }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "an object key must be a string at $index" }
                val key = string()
                require(keys.add(key)) { "duplicate key \"$key\"" }
                skipWhitespace()
                expect(':')
                skipWhitespace()
                value()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    '}' -> { index++; return }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $index")
                }
            }
        }

        private fun arrayValue() {
            index++
            skipWhitespace()
            if (peek() == ']') { index++; return }
            while (true) {
                skipWhitespace()
                value()
                skipWhitespace()
                when (peek()) {
                    ',' -> index++
                    ']' -> { index++; return }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $index")
                }
            }
        }

        /** Reads a string and returns its decoded content, so escaped keys compare as written. */
        fun string(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                require(!atEnd) { "unterminated string" }
                val c = text[index++]
                when {
                    c == '"' -> return out.toString()
                    c < ' ' -> throw IllegalArgumentException("control character in a string at ${index - 1}")
                    c == '\\' -> {
                        require(!atEnd) { "unterminated escape" }
                        when (val e = text[index++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                require(index + 4 <= text.length) { "short \\u escape" }
                                val hex = text.substring(index, index + 4)
                                require(hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) { "bad \\u escape" }
                                out.append(hex.toInt(16).toChar())
                                index += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e")
                        }
                    }
                    else -> out.append(c)
                }
            }
        }

        private fun number() {
            val match = NUMBER.matchAt(text, index)
            require(match != null) { "not a JSON value at $index" }
            index = match.range.last + 1
            // "24f", "024" and "24." leave a letter, digit or point right after the number.
            require(atEnd || text[index] !in "0123456789.eE+-" && !text[index].isLetter()) {
                "malformed number at ${match.range.first}"
            }
        }

        private fun literal(word: String) {
            require(text.startsWith(word, index)) { "not a JSON value at $index" }
            index += word.length
            require(atEnd || !text[index].isLetterOrDigit()) { "not a JSON value at $index" }
        }

        private fun peek(): Char? = text.getOrNull(index)

        private fun expect(c: Char) {
            require(peek() == c) { "expected '$c' at $index" }
            index++
        }
    }
}
