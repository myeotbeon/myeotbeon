package app.myeotbeonideun.engine.model

/** 가서 할 일. 화제 전환 한마디(redirect_*)를 고르는 데 쓴다. */
enum class Purpose(val situation: String?) {
    CLINIC("redirect_clinic"),
    MEAL("redirect_meal"),
    FAMILY("redirect_family"),
    REST("redirect_rest"),
    /** 사용자가 직접 쓴 한마디를 그대로 읽는다. */
    CUSTOM(null),
    NONE(null),
}

/** 먼저 말해 주기 간격. */
enum class IntervalMode { AUTO, FIVE_MIN, TEN_MIN, OFF }

/** 출발 전에 정하는 것. '자주 가는 길' 한 건과 같다. */
data class TripConfig(
    val honorific: String = "어머니",
    val place: String = "",
    val purpose: Purpose = Purpose.NONE,
    /** purpose 가 CUSTOM 일 때 읽을 한마디. */
    val customPurposeLine: String = "",
    val driver: String = "따님",
    val seat: SeatPosition = SeatPosition.REAR_RIGHT,
    val interval: IntervalMode = IntervalMode.AUTO,
)
