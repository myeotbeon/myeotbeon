package app.myeotbeonideun.engine.script

import app.myeotbeonideun.engine.TestScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JosaTest {
    @Test
    fun `받침이 있으면 이 은 을 과`() {
        assertEquals("따님이", Josa.attach("따님", "이"))
        assertEquals("따님은", Josa.attach("따님", "은"))
        assertEquals("아드님을", Josa.attach("아드님", "을"))
        assertEquals("선생님과", Josa.attach("선생님", "과"))
    }

    @Test
    fun `받침이 없으면 가 는 를 와`() {
        assertEquals("사위가", Josa.attach("사위", "이"))
        assertEquals("며느리는", Josa.attach("며느리", "은"))
        assertEquals("사위를", Josa.attach("사위", "을"))
        assertEquals("며느리와", Josa.attach("며느리", "과"))
    }

    @Test
    fun `으로는 받침이 없거나 ㄹ받침이면 로가 된다`() {
        assertEquals("큰딸 집으로", Josa.attach("큰딸 집", "으로"))
        assertEquals("병원으로", Josa.attach("병원", "으로"))
        assertEquals("센터로", Josa.attach("센터", "으로"))
        assertEquals("서울로", Josa.attach("서울", "으로"))
    }

    @Test
    fun `이에요와 예요`() {
        assertEquals("큰딸 집이에요", Josa.attach("큰딸 집", "이에요"))
        assertEquals("센터예요", Josa.attach("센터", "이에요"))
    }

    @Test
    fun `숫자와 문장부호로 끝나는 말`() {
        assertEquals("병원 3이", Josa.attach("병원 3", "이")) // 삼
        assertEquals("병원 2가", Josa.attach("병원 2", "이")) // 이
        assertEquals("7로", Josa.attach("7", "으로")) // 칠 → ㄹ받침
        assertEquals("큰딸 집(2층)이", Josa.attach("큰딸 집(2층)", "이"))
    }

    @Test
    fun `모르는 조사 표기는 null 이다`() {
        assertNull(Josa.pick("따님", "께서"))
        assertTrue(Josa.hasFinal("따님"))
        assertFalse(Josa.hasFinal("사위"))
    }
}

class ScriptEngineTest {
    private val slots = SlotValues("아버지", "주간보호센터", "사위")

    @Test
    fun `빈칸을 채우고 조사를 고른다`() {
        assertEquals(
            "아버지, 사위가 잠깐 내렸어요.",
            ScriptEngine.render("{호칭}, {운전자:이} 잠깐 내렸어요.", slots),
        )
        assertEquals(
            "어머니, 따님이 잠깐 내렸어요.",
            ScriptEngine.render("{호칭}, {운전자:이} 잠깐 내렸어요.", SlotValues.SAMPLE),
        )
        assertEquals("주간보호센터로 가요.", ScriptEngine.render("{곳:으로} 가요.", slots))
    }

    @Test
    fun `모르는 슬롯은 그대로 둔다`() {
        assertEquals("{할일} 하러 가요.", ScriptEngine.render("{할일} 하러 가요.", slots))
    }

    @Test
    fun `변형을 순서대로 돌려 쓰고 연달아 같은 문장이 나오지 않는다`() {
        val engine = ScriptEngine(TestScripts.book, SlotValues.SAMPLE)
        val n = engine.count("ask_where")
        assertTrue(n >= 2)
        val said = List(n * 2) { engine.next("ask_where")!! }
        for (i in 1 until said.size) assertNotEquals(said[i - 1], said[i])
        assertEquals(said.take(n), said.drop(n)) // 한 바퀴 돌면 처음으로
        assertEquals(n, said.toSet().size)
    }

    @Test
    fun `같은 순서로 부르면 같은 문장이 나온다`() {
        fun run(): List<String?> {
            val e = ScriptEngine(TestScripts.book, slots)
            return listOf("depart", "ask_where", "ask_where", "stop_signal", "ask_where", "depart").map { e.next(it) }
        }
        assertEquals(run(), run())
    }

    @Test
    fun `같은 상황의 변형은 모두 같은 곳과 같은 사람을 말한다`() {
        val engine = ScriptEngine(TestScripts.book, slots)
        repeat(engine.count("ask_where")) {
            val line = engine.next("ask_where")!!
            assertTrue(line, line.startsWith("아버지"))
            assertTrue(line, line.contains("주간보호센터"))
        }
    }

    @Test
    fun `없는 상황은 null 이다`() {
        assertNull(ScriptEngine(TestScripts.book, slots).next("no_such_situation"))
    }
}

class ScriptBookTest {
    @Test
    fun `대본 파일을 읽는다`() {
        val book = TestScripts.book
        assertEquals(1, book.version)
        assertEquals(Audience.DRIVER, book.situation("door_open_d")!!.to)
        assertEquals(Audience.CARD, book.situation("card_title")!!.to)
    }

    @Test
    fun `작은 JSON 해석기가 이스케이프와 중첩을 읽는다`() {
        val v = MiniJson.parse("""{"a":[1,2.5,true,null,"x\"y\né"],"b":{"c":"한글"}}""") as Map<*, *>
        val a = v["a"] as List<*>
        assertEquals(listOf(1.0, 2.5, true, null, "x\"y\né"), a)
        assertEquals("한글", (v["b"] as Map<*, *>)["c"])
    }

    @Test(expected = IllegalArgumentException::class)
    fun `형식이 틀린 대본은 읽지 않는다`() {
        ScriptBook.parse("""{"version":1,"situations":{"intro":{"to":"nobody","lines":[]}}}""")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `닫히지 않은 JSON 은 오류다`() {
        MiniJson.parse("""{"a":[1,2""")
    }
}

class ScriptValidatorTest {
    private fun errors(key: String, to: Audience, line: String) =
        ScriptValidator.validateLine(key, to, line).filter { it.severity == Severity.ERROR }.map { it.rule }

    @Test
    fun `앱에 들어가는 대본 전체가 규칙을 통과한다`() {
        val findings = ScriptValidator.validate(TestScripts.book)
        val errors = findings.filter { it.severity == Severity.ERROR }
        assertTrue(errors.joinToString("\n"), errors.isEmpty())
    }

    @Test
    fun `명세의 상황이 모두 있고 변형이 둘 이상이다`() {
        for (key in ScriptValidator.REQUIRED.keys) {
            val s = TestScripts.book.situation(key)
            assertTrue("$key 없음", s != null)
            if (!key.startsWith("card_")) assertTrue("$key 변형 수", s!!.lines.size >= 2)
        }
    }

    @Test
    fun `R1 동승자에게 하는 줄은 호칭으로 시작한다`() {
        assertEquals(listOf("R1"), errors("ask_where", Audience.PASSENGER, "{곳}에 가요."))
        assertTrue(errors("ask_where", Audience.PASSENGER, "{호칭}, {곳}에 가요.").isEmpty())
    }

    @Test
    fun `R2 한 문장은 18음절까지다`() {
        // 어머니(3) + 15음절 = 18
        assertTrue(errors("x", Audience.PASSENGER, "{호칭}, 가나다라마바사아자차카타파하가.").isEmpty())
        assertEquals(listOf("R2"), errors("x", Audience.PASSENGER, "{호칭}, 가나다라마바사아자차카타파하가나."))
    }

    @Test
    fun `R2 한 줄은 두 문장까지이고 화제 전환은 한 문장이다`() {
        assertEquals(listOf("R2"), errors("x", Audience.PASSENGER, "{호칭}, 가요. 곧 가요. 잘 가요."))
        assertEquals(listOf("R2"), errors("redirect_meal", Audience.PASSENGER, "{호칭}, 가서 드세요. 맛있어요."))
        assertTrue(errors("redirect_meal", Audience.PASSENGER, "{호칭}, 가서 같이 드세요.").isEmpty())
        assertEquals("화제 전환 줄도 호칭으로 시작한다", listOf("R1"), errors("redirect_meal", Audience.PASSENGER, "가서 같이 드세요."))
    }

    @Test
    fun `음절 수는 예시값으로 채우고 문장부호와 공백을 빼고 센다`() {
        assertEquals(12, ScriptValidator.countSyllables(ScriptEngine.render("{호칭}, 잠깐 신호를 기다려요.", SlotValues.SAMPLE)))
        assertEquals(listOf("가요", "곧 가요"), ScriptValidator.splitSentences("가요. 곧 가요!"))
    }

    @Test
    fun `R3 금지어를 잡는다`() {
        val bad = listOf(
            "{호칭}, 아까 들으셨지요.",
            "{호칭}, 또 물으시네요.",
            "{호칭}, 다시 말할게요.",
            "{호칭}, 제가 말했어요.",
            "{호칭}, 말씀드렸어요.",
            "{호칭}, 기억나세요.",
            "{호칭}, 몇 번 물으세요.",
            "{호칭}, 왜 자꾸 그러세요.",
            "{호칭}, 잊으셨어요.",
            "{호칭}, 모르시겠어요.",
            "{호칭}, 그건 틀렸어요.",
        )
        for (line in bad) assertEquals(line, listOf("R3"), errors("x", Audience.PASSENGER, line))
    }

    @Test
    fun `R3 금지어는 띄어쓰기를 바꿔도 잡는다`() {
        assertEquals("몇 번", ScriptValidator.findForbidden("몇번이나 물으세요"))
        assertEquals("왜 자꾸", ScriptValidator.findForbidden("왜자꾸 그래요"))
    }

    @Test
    fun `R3 아니에요는 문장 처음에만 금지다`() {
        assertEquals(listOf("R3"), errors("x", Audience.PASSENGER, "{호칭}, 아니에요. 아직 가요."))
        assertEquals(listOf("R3"), errors("x", Audience.PASSENGER, "{호칭}, 가는 중이에요. 아니에요."))
        assertTrue(errors("x", Audience.PASSENGER, "{호칭}, 아직 다 온 건 아니에요.").isEmpty())
    }

    @Test
    fun `몸을 내밀게 할 수 있는 말과 병명은 쓰지 못한다`() {
        assertEquals(listOf("S"), errors("window_open_p", Audience.PASSENGER, "{호칭}, 손을 내밀어 보세요."))
        assertEquals(listOf("D"), errors("x", Audience.DRIVER, "치매 어르신 문 열림."))
    }

    @Test
    fun `직접 쓴 화제 전환 한마디도 같은 규칙으로 검사하되 호칭은 요구하지 않는다`() {
        fun rules(line: String) = ScriptValidator.validateCustomRedirect(line).map { it.rule }
        assertTrue(rules("가서 손주 얼굴 봐요.").isEmpty())
        assertEquals(listOf("F-슬롯"), rules("{곳}에서 쉬어요."))
        assertEquals(listOf("R2"), rules("가요. 손주 봐요."))
        assertEquals(listOf("R2"), rules("가나다라마바사아자차카타파하가나다라마."))
        assertEquals(listOf("R3"), rules("아까처럼 맛있게 드세요."))
        assertEquals(listOf("F-빈줄"), rules("  "))
        assertTrue(ScriptValidator.validateCustomRedirect("또 가요.").all { it.situation == ScriptValidator.CUSTOM_REDIRECT_KEY })
    }

    @Test
    fun `10절 슬롯과 조사 표기를 검사한다`() {
        assertTrue("F-슬롯" in errors("x", Audience.PASSENGER, "{호칭}, {할일} 하러 가요."))
        assertEquals(listOf("F-조사"), errors("x", Audience.PASSENGER, "{호칭}, {운전자:께서} 와요."))
        assertEquals(listOf("F-운전자"), errors("door_open_d", Audience.DRIVER, "{호칭}, 문이 열렸어요."))
        assertEquals(listOf("F-카드"), errors("card_title", Audience.CARD, "{곳}에 가는 아주 길고 긴 길 위에서 있어요"))
    }

    @Test
    fun `R4 변형이 하나뿐이거나 같은 문장이 겹치면 오류다`() {
        val one = Situation("ask_where", Audience.PASSENGER, listOf("{호칭}, {곳}에 가요."))
        assertTrue(ScriptValidator.validateSituation(one).any { it.rule == "R4" && it.severity == Severity.ERROR })
        val dup = Situation("ask_where", Audience.PASSENGER, listOf("{호칭}, {곳}에 가요.", "{호칭}, {곳}에 가요."))
        assertTrue(ScriptValidator.validateSituation(dup).any { it.rule == "R4" && it.severity == Severity.ERROR })
    }

    @Test
    fun `빠진 상황을 알려 준다`() {
        val book = ScriptBook(1, mapOf("intro" to TestScripts.book.situation("intro")!!))
        val missing = ScriptValidator.validate(book).filter { it.rule == "F-상황" && it.severity == Severity.ERROR }
        // 필수 상황 가운데 intro 만 있고, 카드 구간 문구도 없다
        assertEquals(ScriptValidator.REQUIRED.size - 1 + 1, missing.size)
    }

    private fun card(key: String, vararg lines: String) = key to Situation(key, Audience.CARD, lines.toList())

    private fun bookWithCards(vararg cards: Pair<String, Situation>): ScriptBook {
        val base = TestScripts.book.situations.filterKeys {
            !it.startsWith("card_stage") && it != "card_stop" && it != "card_driver_away"
        }
        return ScriptBook(1, base + cards)
    }

    @Test
    fun `카드 구간 문구는 한 상황에 6줄이어도 되고 구간별 상황이어도 된다`() {
        fun errors(book: ScriptBook) = ScriptValidator.validate(book).filter { it.severity == Severity.ERROR }
        val single = bookWithCards(card("card_stage", "출발", "가는 중", "절반", "거의", "곧", "도착"))
        assertTrue(errors(single).toString(), errors(single).isEmpty())

        val perStage = bookWithCards(
            card("card_stage_depart", "이제 출발해요"), card("card_stage_onway", "잘 가고 있어요"),
            card("card_stage_half", "절반쯤 왔어요"), card("card_stage_near", "거의 다 왔어요"),
            card("card_stage_arrive", "{곳}에 다 왔어요"), card("card_stop", "잠깐 멈췄어요"),
            card("card_driver_away", "{운전자:이} 곧 와요"),
        )
        assertTrue(errors(perStage).toString(), errors(perStage).isEmpty())
        assertTrue(ScriptValidator.validate(perStage).none { it.rule == "F-상황" })

        assertEquals(listOf("F-상황"), errors(bookWithCards()).map { it.rule })
        assertEquals(listOf("F-카드"), errors(bookWithCards(card("card_stage", "출발", "도착"))).map { it.rule })
    }
}
