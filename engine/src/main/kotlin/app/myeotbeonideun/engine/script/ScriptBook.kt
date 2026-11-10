package app.myeotbeonideun.engine.script

/** 그 줄을 듣거나 보는 사람. */
enum class Audience(val id: String) {
    PASSENGER("passenger"),
    DRIVER("driver"),
    CARD("card");

    companion object {
        fun of(id: String?): Audience? = entries.firstOrNull { it.id == id }
    }
}

/** 한 상황과 그 변형 문장들. */
data class Situation(val key: String, val to: Audience, val lines: List<String>)

/**
 * 대본 전체(scripts_ko.json). 대본은 사람이 쓴 고정 문장이고,
 * 실행 중에 문장을 새로 만들지 않는다(명세 3.6 R6).
 */
class ScriptBook(val version: Int, val situations: Map<String, Situation>) {

    fun situation(key: String): Situation? = situations[key]

    companion object {
        /** @throws IllegalArgumentException 형식이 맞지 않을 때 */
        fun parse(json: String): ScriptBook {
            val root = MiniJson.parse(json) as? Map<*, *>
                ?: throw IllegalArgumentException("대본의 최상위는 객체여야 합니다")
            val version = (root["version"] as? Double)?.toInt() ?: 1
            val raw = root["situations"] as? Map<*, *>
                ?: throw IllegalArgumentException("situations 가 없습니다")
            val out = LinkedHashMap<String, Situation>()
            for ((k, v) in raw) {
                val key = k as String
                val obj = v as? Map<*, *>
                    ?: throw IllegalArgumentException("$key: 객체여야 합니다")
                val to = Audience.of(obj["to"] as? String)
                    ?: throw IllegalArgumentException("$key: to 는 passenger·driver·card 중 하나여야 합니다")
                val lines = (obj["lines"] as? List<*>)?.map {
                    it as? String ?: throw IllegalArgumentException("$key: lines 는 문자열 배열이어야 합니다")
                } ?: throw IllegalArgumentException("$key: lines 가 없습니다")
                out[key] = Situation(key, to, lines)
            }
            return ScriptBook(version, out)
        }
    }
}
