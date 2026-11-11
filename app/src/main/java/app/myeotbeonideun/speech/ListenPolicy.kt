package app.myeotbeonideun.speech

import app.myeotbeonideun.engine.intent.PassengerIntent

// 이 파일은 안드로이드와 Pleos SDK 에 의존하지 않는다(순수 Kotlin). ListenPolicyTest 가 확인한다.

/** 음성 인식 오류의 종류. Pleos SpeechToText 의 ErrorType 이름으로 가른다(Pleos 문서의 목록). */
enum class ListenError {
    /** 시간 초과(말이 없음). 오류로 세지 않는다 — 차 안에서 아무도 말하지 않는 것은 흔한 일이다. */
    NO_SPEECH,

    /** 다시 해도 되지 않는 것(권한 거부, 지원하지 않는 API, 언어 없음, 인증). 그 동행에서는 바로 물러난다. */
    FATAL,

    /** 그 밖(마이크·오디오·기기 안 엔진 오류 등). 세고, 연달아 [ListenPolicy.maxErrors] 번이면 물러난다. */
    OTHER,
    ;

    companion object {
        /** @param errorTypeName ErrorType 의 이름(AUDIO_ERROR, MIC_ERROR, TIMEOUT …) */
        fun of(errorTypeName: String): ListenError = when (errorTypeName) {
            "TIMEOUT" -> NO_SPEECH
            "PERMISSION_DENIED", "NOT_SUPPORTED_API", "UNAVAILABLE_LANGUAGE", "UNAUTHENTICATED" -> FATAL
            // MIC_ERROR 는 Gleo 가 마이크를 잠깐 쓰는 때에도 날 수 있어 바로 물러나지 않고 센다.
            else -> OTHER
        }
    }
}

/**
 * '물으면 대답하기(실험 기능, 기본 꺼짐)'의 듣기 규칙(결함 9).
 *
 * - 오류 횟수는 인식이 정상으로 끝날 때마다(완성된 결과가 오거나, 오류 없이 끝나면) 0 으로 돌린다. 시간 초과·말 없음은
 *   오류로 세지 않는다. 그래서 '말이 없어 시간 초과'가 몇 번 나도 그 동행 내내 꺼지지 않는다.
 * - 같은 물음 종류에는 [cooldownMs] 안에 다시 대답하지 않는다. 운전자 통화나 라디오의 같은 말에 차가 거듭 끼어들지 않게.
 *   (운전자의 '다시 말씀드리기'는 이것과 상관없이 언제든 된다.)
 * - 이 규칙으로도 누가 한 말인지(동승자·운전자·라디오)는 가리지 못한다. 그래서 실험 기능이고 기본은 꺼져 있다.
 */
class ListenPolicy(
    private val cooldownMs: Long = COOLDOWN_MS,
    val maxErrors: Int = MAX_ERRORS,
) {
    private var errors = 0
    /** 이번 인식에서 센 오류가 났는지(그러면 끝나도 0 으로 돌리지 않는다). */
    private var sessionFailed = false
    private val lastAnswered = HashMap<PassengerIntent, Long>()

    /** 연달아 난 오류 수. */
    val errorCount: Int get() = errors

    /** 동행을 새로 시작한다. */
    fun reset() {
        errors = 0
        sessionFailed = false
        lastAnswered.clear()
    }

    fun onRecognitionStarted() {
        sessionFailed = false
    }

    /**
     * 완성된 인식 결과가 왔다(정상 종료). 오류 횟수를 0 으로 돌린다.
     * @param intent 그 문장에서 알아낸 물음(없으면 null)
     * @return 대답할 물음. 알아낸 것이 없거나 같은 종류를 쿨다운 안에 또 물었으면 null
     */
    fun onResult(intent: PassengerIntent?, nowMs: Long): PassengerIntent? {
        errors = 0
        sessionFailed = false
        if (intent == null) return null
        val last = lastAnswered[intent]
        if (last != null && nowMs - last < cooldownMs) return null
        lastAnswered[intent] = nowMs
        return intent
    }

    /** 인식 한 번이 끝났다. 센 오류 없이 끝났으면 정상 종료다. */
    fun onEnded() {
        if (!sessionFailed) errors = 0
        sessionFailed = false
    }

    /** @return true 면 이 동행에서는 그만 듣는다(물러난다) */
    fun onError(error: ListenError): Boolean = when (error) {
        ListenError.NO_SPEECH -> false
        ListenError.FATAL -> true
        ListenError.OTHER -> {
            sessionFailed = true
            ++errors >= maxErrors
        }
    }

    companion object {
        /** 같은 물음 종류에 다시 대답하기까지의 최소 간격(15~20초). */
        const val COOLDOWN_MS = 18_000L
        const val MAX_ERRORS = 3
    }
}
