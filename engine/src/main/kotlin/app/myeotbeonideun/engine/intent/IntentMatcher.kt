package app.myeotbeonideun.engine.intent

/** 동승자가 묻는 다섯 가지(명세 3.2-3). */
enum class PassengerIntent { WHERE, WHEN, ARRIVED_Q, WHY_STOPPED, GO_HOME }

/**
 * 음성 인식 결과에서 물음의 종류만 알아낸다.
 * 인식 문장은 이 함수 안에서만 쓰고, 어디에도 저장하거나 남기지 않는다.
 *
 * 띄어쓰기와 문장부호를 뺀 글에서 '물음의 꼴'을 찾는다(결함 9). '어디'·'언제'·'얼마나' 같은 낱말 하나만으로는
 * 대답하지 않는다 — 운전자의 통화("너 지금 어디야?", "엄마 언제 와?")나 라디오·노래("언제나", "얼마나 좋을까")에도
 * 흔히 나오기 때문이다. "어디 가", "언제 도착", "얼마나 남" 처럼 가는 길을 묻는 꼴만 받는다.
 * 그래도 누가 한 말인지는 가리지 못한다(같은 꼴을 운전자가 통화로 말하면 대답한다). 그래서 실험 기능이다.
 */
object IntentMatcher {
    /** 찾을 꼴. [atEnd] 면 말끝에 올 때만("왜 서?" — "왜 서운해"와 가리려고). */
    private data class Key(val text: String, val atEnd: Boolean = false)

    private fun keys(vararg texts: String) = texts.map { Key(it) }

    // 더 구체적인 물음을 먼저 본다.
    private val RULES: List<Pair<PassengerIntent, List<Key>>> = listOf(
        PassengerIntent.GO_HOME to keys(
            "집에가자", "집에갈래", "집에가고싶", "집에가야", "집에갈까", "집으로가자", "집으로갈래",
            "집에보내", "집에데려", "집갈래", "돌아가자", "언제집에", "집에언제",
        ),
        PassengerIntent.WHY_STOPPED to keys("왜안가", "왜섰", "왜서있", "왜멈", "왜안움직", "왜안떠나") + Key("왜서", atEnd = true),
        PassengerIntent.ARRIVED_Q to keys("다왔", "도착했", "다온", "여기야", "내려도돼", "내려도되", "여기서내려", "이제내려", "내리자"),
        PassengerIntent.WHEN to keys(
            "언제도착", "언제쯤도착", "언제다와", "언제다가", "언제내려",
            "얼마나남", "얼마나더", "얼마나걸려", "얼마나가야",
            "멀었", "몇분남", "몇분더", "몇분걸", "아직이야", "아직멀", "오래걸",
        ),
        PassengerIntent.WHERE to keys("어디가", "어디로가", "어디에가", "어딜가", "어데가", "여기어디", "여기가어디"),
    )

    fun match(recognized: String): PassengerIntent? {
        val text = recognized.filter { it.isLetterOrDigit() }
        if (text.isEmpty()) return null
        return RULES.firstOrNull { (_, keys) ->
            keys.any { k -> if (k.atEnd) text.endsWith(k.text) else text.contains(k.text) }
        }?.first
    }
}
