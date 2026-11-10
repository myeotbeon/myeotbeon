package app.myeotbeonideun.engine.script

/**
 * 대본 파일을 읽기 위한 작은 JSON 해석기.
 * 엔진이 안드로이드(org.json)나 외부 라이브러리에 기대지 않도록 직접 둔다.
 * 결과: Map<String, Any?>, List<Any?>, String, Double, Boolean, null
 */
internal class MiniJson private constructor(private val src: String) {
    private var pos = 0

    companion object {
        fun parse(text: String): Any? {
            val p = MiniJson(text)
            p.skipWs()
            val v = p.value()
            p.skipWs()
            if (p.pos != text.length) p.fail("끝에 남은 글자가 있습니다")
            return v
        }
    }

    private fun fail(msg: String): Nothing =
        throw IllegalArgumentException("JSON 오류(위치 $pos): $msg")

    private fun skipWs() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
        // 맨 앞 BOM 허용
        if (pos == 0 && src.startsWith("﻿")) pos = 1
    }

    private fun value(): Any? {
        skipWs()
        if (pos >= src.length) fail("값이 없습니다")
        return when (val c = src[pos]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> str()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            else -> if (c == '-' || c.isDigit()) num() else fail("알 수 없는 글자 '$c'")
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        if (!src.startsWith(word, pos)) fail("'$word' 가 와야 합니다")
        pos += word.length
        return v
    }

    private fun num(): Double {
        val start = pos
        while (pos < src.length && (src[pos].isDigit() || src[pos] in "+-.eE")) pos++
        return src.substring(start, pos).toDoubleOrNull() ?: fail("숫자가 아닙니다")
    }

    private fun str(): String {
        pos++ // 여는 따옴표
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) fail("문자열이 닫히지 않았습니다")
            val c = src[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= src.length) fail("잘못된 이스케이프")
                    when (val e = src[pos++]) {
                        '"', '\\', '/' -> sb.append(e)
                        'n' -> sb.append('\n')
                        't' -> sb.append('\t')
                        'r' -> sb.append('\r')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > src.length) fail("잘못된 \\u 이스케이프")
                            sb.append(src.substring(pos, pos + 4).toInt(16).toChar())
                            pos += 4
                        }
                        else -> fail("잘못된 이스케이프 '\\$e'")
                    }
                }
                else -> sb.append(c)
            }
        }
    }

    private fun arr(): List<Any?> {
        pos++
        val list = ArrayList<Any?>()
        skipWs()
        if (pos < src.length && src[pos] == ']') { pos++; return list }
        while (true) {
            list.add(value())
            skipWs()
            if (pos >= src.length) fail("배열이 닫히지 않았습니다")
            when (src[pos++]) {
                ',' -> continue
                ']' -> return list
                else -> fail("',' 나 ']' 가 와야 합니다")
            }
        }
    }

    private fun obj(): Map<String, Any?> {
        pos++
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (pos < src.length && src[pos] == '}') { pos++; return map }
        while (true) {
            skipWs()
            if (pos >= src.length || src[pos] != '"') fail("키 문자열이 와야 합니다")
            val key = str()
            skipWs()
            if (pos >= src.length || src[pos] != ':') fail("':' 가 와야 합니다")
            pos++
            map[key] = value()
            skipWs()
            if (pos >= src.length) fail("객체가 닫히지 않았습니다")
            when (src[pos++]) {
                ',' -> continue
                '}' -> return map
                else -> fail("',' 나 '}' 가 와야 합니다")
            }
        }
    }
}
