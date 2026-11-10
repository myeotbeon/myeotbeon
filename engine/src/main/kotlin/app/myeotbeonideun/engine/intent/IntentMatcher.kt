package app.myeotbeonideun.engine.intent

/** 동승자가 묻는 다섯 가지(명세 3.2-3). */
enum class PassengerIntent { WHERE, WHEN, ARRIVED_Q, WHY_STOPPED, GO_HOME }

/**
 * 음성 인식 결과에서 물음의 종류만 알아낸다.
 * 인식 문장은 이 함수 안에서만 쓰고, 어디에도 저장하거나 남기지 않는다.
 */
object IntentMatcher {
    // 더 구체적인 물음을 먼저 본다.
    private val RULES: List<Pair<PassengerIntent, List<String>>> = listOf(
        PassengerIntent.GO_HOME to listOf("집에가", "집에갈", "집으로가", "집에보내", "집갈래", "돌아가자", "집에데려"),
        PassengerIntent.WHY_STOPPED to listOf("왜안가", "왜서", "왜섰", "왜멈", "안가고", "왜안움직"),
        PassengerIntent.ARRIVED_Q to listOf("다왔", "도착했", "다온", "여기야", "내려도", "내릴까", "내리자"),
        PassengerIntent.WHEN to listOf("언제", "얼마나", "멀었", "몇분", "아직이", "아직멀", "오래걸"),
        PassengerIntent.WHERE to listOf("어디가", "어디로", "어디에가", "어디야", "어딜가", "어디", "어데"),
    )

    fun match(recognized: String): PassengerIntent? {
        val text = recognized.filter { it.isLetterOrDigit() }
        if (text.isEmpty()) return null
        return RULES.firstOrNull { (_, keys) -> keys.any { text.contains(it) } }?.first
    }
}
