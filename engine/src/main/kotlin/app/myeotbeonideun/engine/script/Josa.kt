package app.myeotbeonideun.engine.script

/**
 * 받침에 따라 달라지는 조사를 고른다(명세 10절).
 * 대본의 `{운전자:이}` 는 '따님' → "따님이", '사위' → "사위가" 가 된다.
 */
object Josa {
    /** 표기(받침 있을 때의 꼴) → (받침 있을 때, 받침 없을 때) */
    private val PAIRS: Map<String, Pair<String, String>> = mapOf(
        "이" to ("이" to "가"),
        "은" to ("은" to "는"),
        "을" to ("을" to "를"),
        "과" to ("과" to "와"),
        "으로" to ("으로" to "로"),
        "이에요" to ("이에요" to "예요"),
    )

    /** 대본에서 쓸 수 있는 조사 표기. */
    val MARKERS: Set<String> = PAIRS.keys

    /** 숫자로 끝나는 말은 읽는 소리로 받침을 본다. 0영 1일 3삼 6육 7칠 8팔 */
    private const val DIGITS_WITH_FINAL = "013678"
    private const val DIGITS_WITH_RIEUL = "178"

    private enum class Final { NONE, RIEUL, OTHER }

    private fun finalOf(word: String): Final {
        val c = word.lastOrNull { it.isLetterOrDigit() } ?: return Final.NONE
        return when {
            c in '가'..'힣' -> when ((c - '가') % 28) {
                0 -> Final.NONE
                8 -> Final.RIEUL
                else -> Final.OTHER
            }
            c.isDigit() -> when {
                c in DIGITS_WITH_RIEUL -> Final.RIEUL
                c in DIGITS_WITH_FINAL -> Final.OTHER
                else -> Final.NONE
            }
            // 로마자: L·M·N 으로 끝나면 받침이 있는 것으로 읽는다.
            c.lowercaseChar() == 'l' -> Final.RIEUL
            c.lowercaseChar() in "mn" -> Final.OTHER
            else -> Final.NONE
        }
    }

    /** 받침이 있는지. 글자가 없으면 false */
    fun hasFinal(word: String): Boolean = finalOf(word) != Final.NONE

    /**
     * @param marker 조사 표기(이·은·을·과·으로·이에요)
     * @return 알맞은 조사. 모르는 표기면 null
     */
    fun pick(word: String, marker: String): String? {
        val (withFinal, withoutFinal) = PAIRS[marker] ?: return null
        return when (finalOf(word)) {
            Final.NONE -> withoutFinal
            // '으로'는 ㄹ받침 뒤에서도 '로' 가 된다(서울로, 큰딸 집으로).
            Final.RIEUL -> if (marker == "으로") withoutFinal else withFinal
            Final.OTHER -> withFinal
        }
    }

    /** word 뒤에 알맞은 조사를 붙인다. 모르는 표기면 표기 그대로 붙인다. */
    fun attach(word: String, marker: String): String = word + (pick(word, marker) ?: marker)
}
