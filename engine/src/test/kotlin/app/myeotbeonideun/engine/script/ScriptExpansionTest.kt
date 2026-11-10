package app.myeotbeonideun.engine.script

import app.myeotbeonideun.engine.TestScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 대본 완성본을 호칭 6개 x 운전자 5개 x 곳 4개(120가지)로 모두 펼쳐, 조사가 어색한 곳이 없는지 본다.
 *
 * 정답은 [Josa] 구현과 따로 손으로 적은 표([FINAL])로 만든다. 같은 구현으로 스스로를 검사하지 않기 위해서다.
 * 펼친 결과는 engine/build/josa-expansion.txt 에 적는다(사람이 눈으로 볼 수 있게).
 */
class ScriptExpansionTest {

    private val honorifics = listOf("어머니", "아버지", "할머니", "할아버지", "장모님", "엄마")
    private val drivers = listOf("따님", "아드님", "사위", "며느리", "선생님")
    private val places = listOf("큰딸 집", "병원", "주간보호센터", "성모병원")

    /** 낱말이 받침으로 끝나는지(손으로 적음). 이 가운데 ㄹ받침으로 끝나는 말은 없다. */
    private val FINAL: Map<String, Boolean> = mapOf(
        "어머니" to false, "아버지" to false, "할머니" to false, "할아버지" to false, "장모님" to true, "엄마" to false,
        "따님" to true, "아드님" to true, "사위" to false, "며느리" to false, "선생님" to true,
        "큰딸 집" to true, "병원" to true, "주간보호센터" to false, "성모병원" to true,
    )

    /** 조사 표기 → (받침 있을 때, 받침 없을 때). 손으로 적은 정답. */
    private val FORMS: Map<String, Pair<String, String>> = mapOf(
        "이" to ("이" to "가"),
        "은" to ("은" to "는"),
        "을" to ("을" to "를"),
        "과" to ("과" to "와"),
        "으로" to ("으로" to "로"),
        "이에요" to ("이에요" to "예요"),
    )

    private fun expected(word: String, marker: String): String {
        val (withFinal, withoutFinal) = FORMS.getValue(marker)
        return word + if (FINAL.getValue(word)) withFinal else withoutFinal
    }

    /** 엔진의 render 와 따로, 정답표만으로 빈칸을 채운다. */
    private fun fill(template: String, slots: SlotValues): String =
        Regex("""\{([^{}:]+)(?::([^{}]*))?}""").replace(template) { m ->
            val word = slots.valueOf(m.groupValues[1])!!
            val marker = m.groups[2]?.value
            if (marker == null) word else expected(word, marker)
        }

    /** 그 낱말 뒤에 오면 틀린 조사. 받침 있는 말 뒤의 '가·는·를·와·로·예요', 받침 없는 말 뒤의 '이·은·을·과·으로·이에요'. */
    private fun wrongForms(word: String): List<String> =
        if (FINAL.getValue(word)) {
            listOf("가 ", "는 ", "를 ", "와 ", "로 ", "예요").map { word + it }
        } else {
            listOf("이 ", "은 ", "을 ", "과 ", "으로 ", "이에요").map { word + it }
        }

    private fun combos(): List<SlotValues> =
        honorifics.flatMap { h -> drivers.flatMap { d -> places.map { p -> SlotValues(h, p, d) } } }

    @Test
    fun `조사 고르기는 열다섯 낱말과 여섯 표기 모두에서 손으로 적은 정답과 같다`() {
        for (word in honorifics + drivers + places) {
            for (marker in FORMS.keys) {
                assertEquals("$word + $marker", expected(word, marker), Josa.attach(word, marker))
            }
        }
        assertEquals("시험하는 낱말은 열다섯 개", 15, FINAL.size)
        assertEquals("조사 표기는 엔진이 아는 여섯 개", FORMS.keys, Josa.MARKERS)
    }

    @Test
    fun `대본 전체를 120가지로 펼쳐도 조사가 어색한 줄이 없다`() {
        val book = TestScripts.book
        val problems = ArrayList<String>()
        var rendered = 0
        val report = StringBuilder()
        for (slots in combos()) {
            val words = listOf(slots.honorific, slots.driver, slots.place)
            report.appendLine("== ${slots.honorific} / ${slots.driver} / ${slots.place}")
            for (s in book.situations.values) {
                s.lines.forEachIndexed { i, template ->
                    val text = ScriptEngine.render(template, slots)
                    rendered++
                    report.appendLine("${s.key}#${i + 1}\t$text")
                    val where = "${s.key}#${i + 1} [${words.joinToString("·")}] $text"
                    if (text != fill(template, slots)) problems += "정답표와 다름: $where"
                    if (text.contains('{') || text.contains('}')) problems += "빈칸이 남음: $where"
                    if (text.contains("  ")) problems += "공백이 겹침: $where"
                    for (word in words) {
                        // 낱말 뒤에 공백을 붙여 견주므로 문장 끝도 잡히게 끝에 공백을 하나 둔다
                        val padded = text.replace(Regex("[.,!?]"), " ") + " "
                        wrongForms(word).firstOrNull { padded.contains(it) }?.let { problems += "틀린 조사 '$it': $where" }
                    }
                }
            }
        }
        File("build").mkdirs()
        File("build/josa-expansion.txt").writeText(report.toString(), Charsets.UTF_8)
        assertEquals(120, combos().size)
        assertEquals(120 * book.situations.values.sumOf { it.lines.size }, rendered)
        assertTrue(problems.take(20).joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `받침에 따라 달라지는 조사는 모두 조사 표기로 적혀 있다`() {
        // {곳}에, {곳}까지 처럼 받침과 상관없는 조사만 빈칸 뒤에 바로 붙일 수 있다.
        val variable = Regex("""\{[^{}:]+}(이에요|예요|으로|이|가|은|는|을|를|과|와|로)(?![가-힣])""")
        for (s in TestScripts.book.situations.values) {
            s.lines.forEachIndexed { i, template ->
                assertFalse("${s.key}#${i + 1}: $template", variable.containsMatchIn(template))
            }
        }
        // 이 검사가 실제로 잡는지
        assertTrue(variable.containsMatchIn("{운전자}가 잠깐 내렸어요."))
        assertTrue(variable.containsMatchIn("곧 {곳}이에요."))
        assertFalse(variable.containsMatchIn("{곳}에 가요."))
        assertFalse(variable.containsMatchIn("{곳}까지 더 가야 해요."))
    }

    @Test
    fun `호칭은 부르는 말로만 쓰이고 동승자 줄마다 한 번씩 나온다`() {
        for (s in TestScripts.book.situations.values) {
            s.lines.forEachIndexed { i, template ->
                val n = Regex("""\{호칭[^}]*}""").findAll(template).count()
                when (s.to) {
                    Audience.PASSENGER -> {
                        assertEquals("${s.key}#${i + 1}", 1, n)
                        assertTrue("${s.key}#${i + 1}", template.startsWith("{호칭}, "))
                    }
                    else -> assertEquals("${s.key}#${i + 1}", 0, n)
                }
            }
        }
    }

    @Test
    fun `120가지로 펼쳐도 한 문장이 18음절을 넘지 않고 카드는 16자를 넘지 않는다`() {
        var longest = 0
        var longestText = ""
        for (slots in combos()) {
            for (s in TestScripts.book.situations.values) {
                for (template in s.lines) {
                    val text = ScriptEngine.render(template, slots)
                    if (s.to == Audience.CARD) {
                        val n = text.count { !it.isWhitespace() }
                        assertTrue("카드 ${n}자: $text", n <= ScriptValidator.MAX_CARD_CHARS)
                        continue
                    }
                    for (sentence in ScriptValidator.splitSentences(text)) {
                        val n = ScriptValidator.countSyllables(sentence)
                        if (n > longest) {
                            longest = n
                            longestText = sentence
                        }
                    }
                }
            }
        }
        assertTrue("가장 긴 문장 ${longest}음절: $longestText", longest <= ScriptValidator.MAX_SYLLABLES_PER_SENTENCE)
    }

    @Test
    fun `틀린 조사를 넣으면 이 검사가 잡는다`() {
        // 검사 자체의 반대 시험: 일부러 틀린 꼴을 만들어 wrongForms 가 잡는지 본다.
        val bad = listOf("사위이 잠깐 내렸어요", "따님가 잠깐 내렸어요", "주간보호센터으로 가요", "병원로 가요", "곧 주간보호센터이에요", "곧 큰딸 집예요")
        val words = honorifics + drivers + places
        for (text in bad) {
            val padded = text.replace(Regex("[.,!?]"), " ") + " "
            assertTrue(text, words.any { w -> wrongForms(w).any { padded.contains(it) } })
        }
        val good = listOf("사위가 잠깐 내렸어요", "따님이 잠깐 내렸어요", "주간보호센터로 가요", "병원으로 가요", "곧 주간보호센터예요", "곧 큰딸 집이에요", "며느리와 같이 가요", "큰딸 집에 가요")
        for (text in good) {
            val padded = text.replace(Regex("[.,!?]"), " ") + " "
            assertFalse(text, words.any { w -> wrongForms(w).any { padded.contains(it) } })
        }
    }
}
