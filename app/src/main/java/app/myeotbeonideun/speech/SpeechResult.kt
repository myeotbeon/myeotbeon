package app.myeotbeonideun.speech

import android.media.AudioAttributes

// 이 파일은 안드로이드 상수 값(AudioAttributes.USAGE_*)만 쓰고 안드로이드를 부르지 않는다(JVM 시험).

/**
 * 말 한 번의 결과. [SpeechController.speak] 가 말마다 꼭 한 번 돌려준다.
 * 기록의 횟수는 [spoken] 이 true 인 것만 센다(엔진의 onUtteranceResult).
 */
enum class SpeechResult(val spoken: Boolean) {
    /** 끝까지 소리 내 읽었다. */
    SPOKEN(true),

    /** 쓸 수 있는 음성 엔진이 없다. */
    NO_ENGINE(false),

    /** 내비 길 안내 음성이 끝나기를 기다렸지만 끝나지 않았다. 소리 내지 않고 화면에만 남긴다. */
    NAVIGATION_BUSY(false),

    /** 통화(벨 포함) 중이라 기다렸지만 끝나지 않았다. 소리 내지 않고 화면에만 남긴다. */
    CALL_BUSY(false),

    /** 오디오 포커스를 얻지 못했다(Gleo·다른 앱이 소리를 쥐고 있다). */
    FOCUS_DENIED(false),

    /** 음성 엔진이 읽다가 실패했다. */
    ENGINE_FAILED(false),

    /** 더 급한 말에 끊기거나, 기다리다가 버려졌다(포커스를 잃어 멈춘 것도 여기). */
    INTERRUPTED(false),

    /** '잠시 조용히'로 멈췄다. */
    SILENCED(false),

    /** 기다리는 사이 사실이 바뀌어(차가 섰거나 도착함) 맞지 않게 된 말이라 소리 내지 않았다. */
    STALE(false),
    ;

    /**
     * 소리를 아예 내지 못한 까닭인지. 이때는 운전자 화면에 그 까닭을 한 줄로 알린다.
     * 끊김(INTERRUPTED)과 조용히(SILENCED)는 운전자가 시켰거나 더 급한 말이 나간 것이라 따로 알리지 않는다.
     */
    val notVoiced: Boolean
        get() = this == NO_ENGINE || this == NAVIGATION_BUSY || this == CALL_BUSY ||
            this == FOCUS_DENIED || this == ENGINE_FAILED
}

/**
 * 다른 소리(내비 길 안내·통화)가 나는 동안 말을 미루는 규칙(명세 3.6 R5, 결함 10).
 *
 * AAOS 기본 오디오 포커스 규칙에서 음성 비서 용도(USAGE_ASSISTANT)의 요청은 내비 안내에 거절당하지 않고 오히려
 * 내비 안내를 끊을 수 있다. 그래서 포커스를 묻기 전에, 지금 재생 중인 소리의 용도를 보고 내비 안내나 통화가
 * 나는 동안은 기다린다. Gleo 의 목소리는 우리 음성과 같은 USAGE_ASSISTANT 로 날 수 있어 용도로는 가려내지 못하고,
 * 포커스 요청이 거절되는 것으로 기다린다(Gleo 는 부르면 다른 앱의 포커스를 거둔다 — Pleos 오디오 포커스 가이드).
 */
object AudioYield {
    enum class Busy { NAVIGATION, CALL }

    /** 내비 길 안내. */
    const val USAGE_NAVIGATION = AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE

    /** 통화: 통화 음성, 통화 신호음, 전화 벨. */
    val CALL_USAGES: Set<Int> = setOf(
        AudioAttributes.USAGE_VOICE_COMMUNICATION,
        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING,
        AudioAttributes.USAGE_NOTIFICATION_RINGTONE,
    )

    /** 지금 재생 중인 소리의 용도 가운데 말을 미뤄야 할 것. 통화가 앞선다. 없으면 null */
    fun busy(activeUsages: Collection<Int>): Busy? = when {
        activeUsages.any { it in CALL_USAGES } -> Busy.CALL
        activeUsages.any { it == USAGE_NAVIGATION } -> Busy.NAVIGATION
        else -> null
    }

    fun Busy.toResult(): SpeechResult = when (this) {
        Busy.NAVIGATION -> SpeechResult.NAVIGATION_BUSY
        Busy.CALL -> SpeechResult.CALL_BUSY
    }

    /**
     * 기다리지 않고 바로 말하는 급한 안전 알림인지. 안전 알림 우선순위의 말 가운데 창문·안전띠 권유는 몇 초 기다려도
     * 되므로 기다리고, 그 밖(주행 중 문 열림, 운전자가 없는 동안 문 열림·자리 비움 등)은 기다리지 않는다.
     */
    fun isUrgent(safetyPriority: Boolean, situation: String): Boolean =
        safetyPriority && situation !in GENTLE_SAFETY_SITUATIONS

    /** 안전 알림이지만 몇 초 기다려도 되는 부드러운 권유. */
    val GENTLE_SAFETY_SITUATIONS = setOf("window_open_p", "belt_off_p")
}
