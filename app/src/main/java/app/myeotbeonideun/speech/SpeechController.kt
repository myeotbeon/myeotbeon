package app.myeotbeonideun.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import app.myeotbeonideun.speech.AudioYield.toResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 말하기를 맡는다. 메인 스레드에서만 부른다.
 *
 * - 엔진: Pleos TextToSpeech(ON_DEVICE) → 실패하면 안드로이드 기본 TextToSpeech.
 * - 다른 소리에 양보(명세 3.6 R5, 결함 10): 말하기 전에 지금 재생 중인 소리의 용도를 보고, 내비 길 안내나 통화가
 *   나는 동안은 끝나기를 [MAX_YIELD_MS] 까지 기다린다([AudioYield]). 그래도 끝나지 않으면 그 말은 소리 내지 않고
 *   화면에만 남기며 '건너뜀'([SpeechResult.NAVIGATION_BUSY]·[SpeechResult.CALL_BUSY])으로 돌려준다.
 *   급한 안전 알림(주행 중 문 열림 등, urgent)은 기다리지 않는다.
 * - 오디오 포커스: 그다음 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 으로 요청하고, 끝나면 돌려준다. 음악은 끊지 않고 낮춘다.
 *   거절되면(Gleo 가 말하는 중 등) 1.5초 간격으로 세 번 더 묻고, 끝내 얻지 못하면 소리 내지 않는다(화면에는 남는다).
 * - 포커스를 잃으면(LOSS, LOSS_TRANSIENT) 즉시 멈추고 돌려준다. CAN_DUCK 은 시스템이 낮추도록 둔다.
 * - 선행 묵음 약 200ms, 문장 사이 쉼 350ms 로 천천히 전한다(명세 3.6 R5).
 * - 말마다 결과([SpeechResult]: 말함, 또는 건너뜀과 그 까닭)를 [speak] 의 onResult 로 꼭 한 번 돌려준다.
 *   기록의 횟수는 끝까지 소리 낸 말([SpeechResult.SPOKEN])만 센다.
 *
 * @param activeUsages 지금 재생 중인 소리의 용도(AudioAttributes.USAGE_*). 기본은 AudioManager.getActivePlaybackConfigurations().
 *   시스템 앱이 아니면 재생 중(started)인 것만 익명으로 받는다. 시험에서 바꿔 끼운다
 */
class SpeechController(
    context: Context,
    private val scope: CoroutineScope,
    private val speakers: List<Speaker> = listOf(PleosSpeaker(context), AndroidSpeaker(context)),
    activeUsages: (() -> List<Int>)? = null,
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val usages: () -> List<Int> = activeUsages ?: {
        audio.activePlaybackConfigurations.mapNotNull { it.audioAttributes?.usage }
    }

    /** 안내 음성. Pleos 오디오 정책에서 VOICE_COMMAND 컨텍스트(USAGE_ASSISTANT)에 해당한다. */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                stop(SpeechResult.INTERRUPTED)
            }
            else -> Unit // GAIN, LOSS_TRANSIENT_CAN_DUCK
        }
    }

    private val focusRequest: AudioFocusRequest =
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener(focusListener, Handler(Looper.getMainLooper()))
            .build()

    /** 기다리는 말. onResult 는 말이 끝나거나 버려질 때 꼭 한 번 불린다. */
    private class Pending(
        val text: String,
        val urgent: Boolean,
        val onResult: ((SpeechResult) -> Unit)?,
        val stillValid: (() -> Boolean)? = null,
    )

    private val queue = ArrayDeque<Pending>()
    private var worker: Job? = null
    private var generation = 0
    /** 마지막으로 하던 말을 멈춘 까닭(끊김·조용히). 멈춘 말의 결과로 돌려준다. */
    private var stopReason = SpeechResult.INTERRUPTED
    private var hasFocus = false
    private var active: Speaker? = null
    private var nextSpeakerIndex = 0
    private var audioZoneId: Int? = null
    private val prepareLock = Mutex()

    private val _speaking = MutableStateFlow(false)
    private val _engineName = MutableStateFlow<String?>(null)
    private val _lastSkip = MutableStateFlow<SpeechResult?>(null)
    private val _engineMissing = MutableStateFlow(false)
    private var lastPrepareFailedAt = 0L

    /** 말하는 중이거나 말하려고 기다리는 중이면 true(다른 소리가 끝나기를 기다리는 동안도) */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 지금 쓰는 음성 엔진 이름. 아직 준비 전이면 null */
    val engineName: StateFlow<String?> = _engineName.asStateFlow()

    /**
     * 마지막 말을 소리 내지 못한 까닭([SpeechResult.notVoiced] 인 것). 다음 말을 소리 내기 시작하면 null 로 돌아간다.
     * 운전자 화면의 한 줄("내비 안내 중이라 소리 내지 않았어요" 등)에 쓴다.
     */
    val lastSkip: StateFlow<SpeechResult?> = _lastSkip.asStateFlow()

    /** 쓸 수 있는 음성 엔진을 찾지 못했다(화면으로만 알린다). */
    val engineMissing: StateFlow<Boolean> = _engineMissing.asStateFlow()

    /** 음성 엔진을 미리 준비해 첫 말이 늦지 않게 한다. */
    fun warmUp() {
        if (active == null) scope.launch { ensureSpeaker() }
    }

    /**
     * @param interrupt true 면 하던 말과 기다리던 말을 버리고 이 말을 한다(버린 말은 [SpeechResult.INTERRUPTED])
     * @param urgent true 면 내비 안내·통화가 끝나기를 기다리지 않는다(급한 안전 알림)
     * @param onResult 말마다 한 번, 메인 스레드에서 불린다
     * @param stillValid 소리 내기 직전에 한 번 묻는다. false 면 그새 사실이 바뀐 말이라 소리 내지 않는다([SpeechResult.STALE])
     */
    fun speak(
        text: String,
        interrupt: Boolean,
        urgent: Boolean = false,
        stillValid: (() -> Boolean)? = null,
        onResult: ((SpeechResult) -> Unit)? = null,
    ) {
        if (interrupt) cancelCurrent(SpeechResult.INTERRUPTED)
        queue.addLast(Pending(text, urgent, onResult, stillValid))
        _speaking.value = true
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /**
     * 하던 말을 멈추고 기다리던 말도 버린다.
     * @param reason 멈추고 버린 말의 결과('잠시 조용히'면 [SpeechResult.SILENCED])
     */
    fun stop(reason: SpeechResult = SpeechResult.INTERRUPTED) {
        cancelCurrent(reason)
    }

    /** 운전자 화면에 보이던 '소리 내지 못함' 표시를 지운다(동행을 새로 시작할 때). */
    fun clearSkipStatus() {
        _lastSkip.value = null
    }

    private fun cancelCurrent(reason: SpeechResult) {
        generation++
        stopReason = reason
        val dropped = queue.toList()
        queue.clear()
        active?.stop()
        dropped.forEach { it.onResult?.invoke(reason) }
    }

    /**
     * 짧고 낮은 알림음(급한 운전자 알림). STREAM_NOTIFICATION 으로 차의 스피커에서 나므로 동승자에게도 들린다
     * (운전석 쪽으로만 보내지 못한다). 어르신을 놀라게 하지 않도록 낮은 음량의 짧은 두 음을 쓴다.
     */
    fun chime() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, CHIME_VOLUME)
            tone.startTone(ToneGenerator.TONE_PROP_ACK, CHIME_MS)
            Handler(Looper.getMainLooper()).postDelayed({ runCatching { tone.release() } }, CHIME_MS + 300L)
        }
    }

    /** TextToSpeech.setAudioZoneId 로 동승 좌석 쪽에서 소리가 나게 한다(Pleos TTS 일 때만). */
    fun setAudioZone(zoneId: Int?) {
        audioZoneId = zoneId
        if (zoneId != null) active?.setAudioZone(zoneId)
    }

    fun release() {
        cancelCurrent(SpeechResult.INTERRUPTED)
        worker?.cancel()
        abandonFocus()
        speakers.forEach { it.release() }
        active = null
        _engineName.value = null
        _speaking.value = false
    }

    // ───────────────────────── 안쪽 ─────────────────────────

    private suspend fun drain() {
        try {
            while (true) {
                val next = queue.removeFirstOrNull() ?: break
                val result = voice(next, generation)
                if (result.notVoiced) _lastSkip.value = result
                next.onResult?.invoke(result)
            }
        } finally {
            _speaking.value = false
            abandonFocus()
        }
    }

    /** 한 말을 소리 낸다. 그사이 멈추면(끊김·조용히) 멈춘 까닭을 돌려준다. */
    private suspend fun voice(p: Pending, gen: Int): SpeechResult {
        ensureSpeaker() ?: return SpeechResult.NO_ENGINE
        if (gen != generation) return stopReason
        if (!p.urgent) waitForOtherAudio(gen)?.let { return it }
        if (p.stillValid?.invoke() == false) return SpeechResult.STALE
        if (!acquireFocus(gen)) return if (gen != generation) stopReason else SpeechResult.FOCUS_DENIED
        _lastSkip.value = null
        return when (say(p.text, gen)) {
            Said.ALL -> SpeechResult.SPOKEN
            Said.STOPPED -> stopReason
            Said.FAILED -> SpeechResult.ENGINE_FAILED
        }
    }

    /**
     * 내비 길 안내·통화가 나는 동안 기다린다. 끝나면 null, [MAX_YIELD_MS] 를 넘기면 그 까닭(건너뜀),
     * 기다리는 사이 멈추면 멈춘 까닭.
     */
    private suspend fun waitForOtherAudio(gen: Int): SpeechResult? {
        var waited = 0L
        while (true) {
            val busy = AudioYield.busy(runCatching { usages() }.getOrDefault(emptyList())) ?: return null
            if (waited >= MAX_YIELD_MS) return busy.toResult()
            delay(YIELD_POLL_MS)
            waited += YIELD_POLL_MS
            if (gen != generation) return stopReason
        }
    }

    private enum class Said { ALL, STOPPED, FAILED }

    private suspend fun say(text: String, gen: Int): Said {
        val sentences = text.split(SENTENCE_BREAK).filter { it.isNotBlank() }
        if (sentences.isEmpty()) return Said.FAILED
        for ((i, sentence) in sentences.withIndex()) {
            if (gen != generation) return Said.STOPPED
            // 선행 묵음: 엔진이 오디오 앞에 무음을 직접 넣지 못하면 재생을 그만큼 늦춘다.
            val preroll = if (active?.addsPrerollSilence == true) 0L else PREROLL_SILENCE_MS
            delay(if (i == 0) preroll else SENTENCE_GAP_MS)
            if (gen != generation) return Said.STOPPED
            var ok = active?.speak(sentence) ?: false
            if (!ok && gen == generation) {
                // 끊긴 것이 아니라 엔진이 실패했다. 남은 엔진이 있으면 바꿔서 한 번 더 읽는다.
                if (nextSpeakerIndex >= speakers.size) return Said.FAILED
                active?.release()
                active = null
                ok = ensureSpeaker()?.speak(sentence) ?: false
            }
            if (gen != generation) return Said.STOPPED
            if (!ok) return Said.FAILED
        }
        return if (gen == generation) Said.ALL else Said.STOPPED
    }

    /** 쓸 수 있는 엔진을 순서대로 찾는다(Pleos → 안드로이드). */
    private suspend fun ensureSpeaker(): Speaker? = prepareLock.withLock {
        active?.let { return@withLock it }
        // 모두 실패했더라도 얼마 뒤에는 처음부터 다시 찾아본다(부팅 직후에는 엔진이 늦게 뜰 수 있다).
        if (nextSpeakerIndex >= speakers.size &&
            SystemClock.elapsedRealtime() - lastPrepareFailedAt >= RETRY_PREPARE_MS
        ) {
            nextSpeakerIndex = 0
        }
        while (nextSpeakerIndex < speakers.size) {
            val candidate = speakers[nextSpeakerIndex++]
            if (candidate.prepare(attributes)) {
                audioZoneId?.let { candidate.setAudioZone(it) }
                active = candidate
                _engineName.value = candidate.name
                _engineMissing.value = false
                return@withLock candidate
            }
        }
        if (!_engineMissing.value) lastPrepareFailedAt = SystemClock.elapsedRealtime()
        _engineName.value = null
        _engineMissing.value = true
        null
    }

    private suspend fun acquireFocus(gen: Int): Boolean {
        if (hasFocus) return true
        repeat(FOCUS_ATTEMPTS) { attempt ->
            if (gen != generation) return false
            if (audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                hasFocus = true
                return true
            }
            if (attempt < FOCUS_ATTEMPTS - 1) delay(FOCUS_RETRY_MS)
        }
        return false
    }

    private fun abandonFocus() {
        if (hasFocus) {
            runCatching { audio.abandonAudioFocusRequest(focusRequest) }
            hasFocus = false
        }
    }

    companion object {
        /** 문장부호 뒤의 공백에서 끊는다(부호는 남긴다). */
        private val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
        private const val PREROLL_SILENCE_MS = 200L
        private const val SENTENCE_GAP_MS = 350L
        private const val FOCUS_ATTEMPTS = 4
        private const val FOCUS_RETRY_MS = 1_500L
        private const val RETRY_PREPARE_MS = 60_000L
        private const val CHIME_VOLUME = 60
        private const val CHIME_MS = 350

        /** 내비 길 안내·통화가 끝나기를 기다리는 가장 긴 시간. 내비 안내 한 마디는 보통 2~5초다. */
        const val MAX_YIELD_MS = 8_000L
        const val YIELD_POLL_MS = 250L
    }
}
