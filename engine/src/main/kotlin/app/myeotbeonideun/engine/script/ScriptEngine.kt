package app.myeotbeonideun.engine.script

/** 대본의 빈칸에 들어갈 말. 슬롯은 {호칭}, {곳}, {운전자} 세 개다(명세 10절). */
data class SlotValues(val honorific: String, val place: String, val driver: String) {
    fun valueOf(slot: String): String? = when (slot) {
        "호칭" -> honorific
        "곳" -> place
        "운전자" -> driver
        else -> null
    }

    companion object {
        /** 대본 검사에서 음절 수를 셀 때 쓰는 예시값(명세 10절). */
        val SAMPLE = SlotValues(honorific = "어머니", place = "큰딸 집", driver = "따님")
        val SLOT_NAMES = setOf("호칭", "곳", "운전자")
    }
}

/**
 * 상황 이름을 받아 다음에 말할 문장을 돌려준다.
 *
 * - 같은 상황의 변형을 순서대로 돌려 쓴다(같은 사실, 다른 표현 — 명세 3.6 R4).
 *   변형이 둘 이상이면 같은 문장이 연달아 나오지 않는다.
 * - 무작위를 쓰지 않으므로 같은 순서로 부르면 같은 문장이 나온다.
 */
class ScriptEngine(private val book: ScriptBook, private val slots: SlotValues) {

    private val cursor = HashMap<String, Int>()

    fun has(key: String): Boolean = !book.situation(key)?.lines.isNullOrEmpty()

    /** 다음 변형을 돌려주고 순서를 한 칸 넘긴다. 상황이 없거나 비어 있으면 null */
    fun next(key: String): String? {
        val lines = book.situation(key)?.lines.orEmpty()
        if (lines.isEmpty()) return null
        val i = cursor[key] ?: 0
        cursor[key] = (i + 1) % lines.size
        return render(lines[i % lines.size], slots)
    }

    /** 순서를 넘기지 않고 index 번째 변형을 돌려준다(동승자 카드처럼 바뀌지 않아야 하는 글). */
    fun at(key: String, index: Int): String? {
        val lines = book.situation(key)?.lines.orEmpty()
        if (lines.isEmpty()) return null
        return render(lines[index.coerceIn(0, lines.size - 1)], slots)
    }

    fun count(key: String): Int = book.situation(key)?.lines?.size ?: 0

    companion object {
        /**
         * `{슬롯}` 또는 `{슬롯:조사}`.
         * 닫는 중괄호도 반드시 `\}` 로 이스케이프한다. JVM 정규식은 맨 끝의 `}` 를 글자로 받아 주지만
         * 안드로이드(ICU) 정규식은 컴파일 오류(U_REGEX_RULE_SYNTAX)를 내고, 이 클래스 초기화가 실패해
         * 앱이 바로 종료된다(2026-11-11 Pleos Connect 에뮬레이터에서 확인). [RegexPortabilityTest] 가 지킨다.
         */
        val SLOT_PATTERN = Regex("""\{([^{}:]+)(?::([^{}]*))?\}""")

        /** 빈칸을 채우고 조사를 고른다. 모르는 슬롯은 그대로 둔다(검사기가 잡는다). */
        fun render(template: String, slots: SlotValues): String =
            SLOT_PATTERN.replace(template) { m ->
                val value = slots.valueOf(m.groupValues[1].trim()) ?: return@replace m.value
                val marker = m.groups[2]?.value?.trim()
                if (marker.isNullOrEmpty()) value else Josa.attach(value, marker)
            }.replace(Regex("\\s+"), " ").trim()
    }
}
