package app.myeotbeonideun.engine.script

enum class Severity { ERROR, WARNING }

/** 검사에서 찾은 한 건. lineIndex 가 -1 이면 상황 전체에 대한 것. */
data class Finding(
    val severity: Severity,
    val rule: String,
    val situation: String,
    val lineIndex: Int,
    val message: String,
) {
    override fun toString(): String =
        "[$severity $rule] $situation" + (if (lineIndex >= 0) "#${lineIndex + 1}" else "") + ": $message"
}

/**
 * 대본 검사기. 명세 3.6 '말의 원칙'과 10절 형식 보정을 규칙으로 옮긴 것이다.
 *
 * - R1 동승자에게 하는 줄은 호칭으로 시작한다(화제 전환 줄도 같다. 엔진이 대답 뒤에 붙일 때 호칭을 뗀다).
 * - R2 한 문장 18음절 이하, 한 줄에 최대 두 문장(화제 전환은 한 문장).
 * - R3 고치거나 따지거나 시험하는 말(금지어)을 쓰지 않는다.
 * - R4 상황마다 변형이 둘 이상이고 서로 달라야 한다(같은 사실, 다른 표현).
 * - F  10절 형식: 슬롯 3개, 조사 표기 6개, 운전자 줄은 호칭 없이, 카드 줄은 짧게.
 * - S  몸을 창밖으로 내밀게 할 수 있는 말을 쓰지 않는다(명세 3.4).
 * - D  병명·환자라는 말을 쓰지 않는다(명세 5).
 *
 * 음절 수는 슬롯을 예시값(어머니·큰딸 집·따님)으로 채운 뒤 문장부호와 공백을 빼고 센다.
 */
object ScriptValidator {
    const val MAX_SYLLABLES_PER_SENTENCE = 18
    const val MAX_SENTENCES = 2
    const val MAX_SENTENCES_REDIRECT = 1
    /** 카드 줄은 12자 안팎. 공백을 뺀 글자 수의 상한. */
    const val MAX_CARD_CHARS = 16

    /** card_stage 한 상황에 담을 때의 줄 수(구간 수). */
    const val CARD_STAGE_LINES = 6

    /** 명세 3.6 R3 의 금지어. '아니에요'는 문장 처음에 올 때만 금지라 따로 본다. */
    val FORBIDDEN: List<String> = listOf(
        "아까", "또", "다시 말", "말했", "말씀드렸", "기억", "몇 번", "왜 자꾸", "잊", "모르", "틀렸",
    )
    const val FORBIDDEN_AT_SENTENCE_START = "아니에요"

    /** 창밖으로 몸을 내밀게 할 수 있는 말. */
    val UNSAFE: List<String> = listOf("내밀", "밖으로")

    /** 화면과 말 어디에도 쓰지 않는 말. */
    val DIGNITY: List<String> = listOf("치매", "환자", "인지저하", "인지 저하", "인지장애", "인지 장애")

    /** 한 가지 변형만 있어도 되는 상황. 카드(card_*)도 하나면 된다. */
    private val SINGLE_VARIANT_OK = setOf("intro")

    private fun singleVariantOk(key: String) = key in SINGLE_VARIANT_OK || key.startsWith("card_")

    /**
     * 동승자 카드 둘째 줄은 두 가지 꼴 가운데 하나면 된다:
     * 한 상황(card_stage)에 구간 순서대로 6줄, 또는 구간별 상황(card_stage_depart 등).
     */
    val CARD_STAGE_KEYS = listOf(
        "card_stage_depart", "card_stage_onway", "card_stage_half", "card_stage_near", "card_stage_arrive",
    )

    /** 있어도 되고 없어도 되는 카드 상황. */
    val OPTIONAL_CARD_KEYS = setOf("card_stage", "card_stage_5min", "card_stop", "card_driver_away")

    /** 명세 9절의 상황 목록(10절에 따라 redirect 는 목적별 네 개로 바뀜). */
    val REQUIRED: Map<String, Audience> = linkedMapOf(
        "intro" to Audience.PASSENGER,
        "depart" to Audience.PASSENGER,
        "stage_onway" to Audience.PASSENGER,
        "stage_half" to Audience.PASSENGER,
        "stage_near" to Audience.PASSENGER,
        "stage_5min" to Audience.PASSENGER,
        "arrive" to Audience.PASSENGER,
        "ask_where" to Audience.PASSENGER,
        "ask_when_onway" to Audience.PASSENGER,
        "ask_when_near" to Audience.PASSENGER,
        "ask_gohome" to Audience.PASSENGER,
        "stop_signal" to Audience.PASSENGER,
        "stop_traffic" to Audience.PASSENGER,
        "stop_waypoint" to Audience.PASSENGER,
        "driver_away" to Audience.PASSENGER,
        "driver_away_again" to Audience.PASSENGER,
        "driver_back" to Audience.PASSENGER,
        "door_open_p" to Audience.PASSENGER,
        "window_open_p" to Audience.PASSENGER,
        "belt_off_p" to Audience.PASSENGER,
        "door_open_d" to Audience.DRIVER,
        "window_open_d" to Audience.DRIVER,
        "belt_off_d" to Audience.DRIVER,
        "redirect_clinic" to Audience.PASSENGER,
        "redirect_meal" to Audience.PASSENGER,
        "redirect_family" to Audience.PASSENGER,
        "redirect_rest" to Audience.PASSENGER,
        "card_title" to Audience.CARD,
    )

    private val SENTENCE_END = Regex("""[.!?…。]+""")

    /** 문장부호로 문장을 나눈다. 빈 조각은 버린다. */
    fun splitSentences(text: String): List<String> =
        text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

    /** 공백과 문장부호를 뺀 글자 수. 한글은 한 글자가 한 음절이다. */
    fun countSyllables(text: String): Int = text.count { it.isLetterOrDigit() }

    private fun squeeze(s: String) = s.filterNot { it.isWhitespace() }

    /** 금지어가 들어 있으면 그 말을 돌려준다. 띄어쓰기를 바꿔 써도 잡는다. */
    fun findForbidden(text: String): String? {
        val tight = squeeze(text)
        FORBIDDEN.firstOrNull { tight.contains(squeeze(it)) }?.let { return it }
        for (sentence in splitSentences(text)) {
            // 호칭 부름("어머니, ") 뒤에 바로 오는 것도 문장 처음으로 본다.
            val body = squeeze(sentence.substringAfter(',', sentence))
            if (body.startsWith(FORBIDDEN_AT_SENTENCE_START) ||
                squeeze(sentence).startsWith(FORBIDDEN_AT_SENTENCE_START)
            ) {
                return "$FORBIDDEN_AT_SENTENCE_START(문장 처음)"
            }
        }
        return null
    }

    fun validate(book: ScriptBook, sample: SlotValues = SlotValues.SAMPLE): List<Finding> {
        val out = ArrayList<Finding>()
        for ((key, to) in REQUIRED) {
            val s = book.situation(key)
            if (s == null) {
                out += Finding(Severity.ERROR, "F-상황", key, -1, "상황이 없습니다")
            } else if (s.to != to) {
                out += Finding(Severity.ERROR, "F-대상", key, -1, "to 는 ${to.id} 여야 합니다(지금 ${s.to.id})")
            }
        }
        // 카드 둘째 줄: card_stage(6줄) 또는 구간별 상황 가운데 하나는 있어야 한다.
        val perStage = CARD_STAGE_KEYS.all { book.situation(it) != null }
        val single = book.situation("card_stage")
        if (!perStage && single == null) {
            out += Finding(
                Severity.ERROR, "F-상황", "card_stage", -1,
                "카드 구간 문구가 없습니다(card_stage 또는 card_stage_depart 등)",
            )
        }
        if (!perStage && single != null && single.lines.size != CARD_STAGE_LINES) {
            out += Finding(
                Severity.ERROR, "F-카드", "card_stage", -1,
                "구간 순서대로 ${CARD_STAGE_LINES}줄이어야 합니다(지금 ${single.lines.size}줄)",
            )
        }
        for (s in book.situations.values) {
            val known = s.key in REQUIRED || s.key in CARD_STAGE_KEYS || s.key in OPTIONAL_CARD_KEYS
            if (!known) {
                out += Finding(Severity.WARNING, "F-상황", s.key, -1, "명세에 없는 상황입니다")
            } else if (s.key.startsWith("card_") && s.to != Audience.CARD) {
                out += Finding(Severity.ERROR, "F-대상", s.key, -1, "to 는 card 여야 합니다(지금 ${s.to.id})")
            }
            out += validateSituation(s, sample)
        }
        return out
    }

    fun validateSituation(s: Situation, sample: SlotValues = SlotValues.SAMPLE): List<Finding> {
        val out = ArrayList<Finding>()
        val min = if (singleVariantOk(s.key)) 1 else 2
        if (s.lines.size < min) {
            out += Finding(Severity.ERROR, "R4", s.key, -1, "변형이 ${s.lines.size}개입니다(최소 ${min}개)")
        } else if (s.lines.size < 3 && !singleVariantOk(s.key)) {
            out += Finding(Severity.WARNING, "R4", s.key, -1, "변형이 ${s.lines.size}개입니다(권장 3~4개)")
        }
        val rendered = s.lines.map { ScriptEngine.render(it, sample) }
        if (rendered.toSet().size != rendered.size) {
            out += Finding(Severity.ERROR, "R4", s.key, -1, "같은 문장이 두 번 들어 있습니다")
        }
        s.lines.forEachIndexed { i, line ->
            validateLine(s.key, s.to, line, sample).forEach { out += it.copy(lineIndex = i) }
        }
        return out
    }

    /** 한 줄을 검사한다. lineIndex 는 0 으로 채워 돌려준다. */
    fun validateLine(
        key: String,
        to: Audience,
        line: String,
        sample: SlotValues = SlotValues.SAMPLE,
    ): List<Finding> {
        val out = ArrayList<Finding>()
        fun err(rule: String, msg: String) { out += Finding(Severity.ERROR, rule, key, 0, msg) }

        if (line.isBlank()) {
            err("F-빈줄", "빈 줄입니다")
            return out
        }

        // 10절: 슬롯과 조사 표기
        for (m in ScriptEngine.SLOT_PATTERN.findAll(line)) {
            val name = m.groupValues[1].trim()
            if (name !in SlotValues.SLOT_NAMES) err("F-슬롯", "쓸 수 없는 슬롯 {$name}")
            val marker = m.groups[2]?.value?.trim()
            if (marker != null && marker !in Josa.MARKERS) err("F-조사", "쓸 수 없는 조사 표기 '$marker'")
        }
        val text = ScriptEngine.render(line, sample)
        if (text.contains('{') || text.contains('}')) err("F-슬롯", "채워지지 않은 빈칸이 있습니다")

        val isRedirect = key.startsWith("redirect")
        val sentences = splitSentences(text)

        when (to) {
            Audience.PASSENGER -> {
                if (!line.trimStart().startsWith("{호칭}")) {
                    err("R1", "호칭({호칭})으로 시작해야 합니다")
                }
                val max = if (isRedirect) MAX_SENTENCES_REDIRECT else MAX_SENTENCES
                if (sentences.size > max) err("R2", "문장이 ${sentences.size}개입니다(최대 ${max}개)")
            }
            Audience.DRIVER -> {
                if (line.trimStart().startsWith("{호칭}")) err("F-운전자", "운전자에게 하는 줄은 호칭 없이 씁니다")
                if (sentences.size > MAX_SENTENCES) err("R2", "문장이 ${sentences.size}개입니다(최대 ${MAX_SENTENCES}개)")
            }
            Audience.CARD -> {
                val n = squeeze(text).length
                if (n > MAX_CARD_CHARS) err("F-카드", "카드 글이 ${n}자입니다(최대 ${MAX_CARD_CHARS}자)")
                if (sentences.size > 1) err("F-카드", "카드 글은 한 구절이어야 합니다")
            }
        }
        if (to != Audience.CARD) {
            for (sentence in sentences) {
                val n = countSyllables(sentence)
                if (n > MAX_SYLLABLES_PER_SENTENCE) {
                    err("R2", "한 문장이 ${n}음절입니다(최대 ${MAX_SYLLABLES_PER_SENTENCE}): \"$sentence\"")
                }
            }
        }
        if (to != Audience.DRIVER) {
            findForbidden(text)?.let { err("R3", "금지어 '$it'") }
            UNSAFE.firstOrNull { text.contains(it) }?.let { err("S", "몸을 내밀게 할 수 있는 말 '$it'") }
        }
        DIGNITY.firstOrNull { text.contains(it) }?.let { err("D", "쓰지 않는 말 '$it'") }
        return out
    }
}
