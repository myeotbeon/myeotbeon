package app.myeotbeonideun.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
 * - 오디오 포커스: 말하기 전에 AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK 으로 요청하고, 끝나면 돌려준다.
 *   음악은 끊지 않고 낮춘다. 통화·내비 안내·Gleo 때문에 거절되면 잠깐 기다렸다가 다시 묻고,
 *   끝내 얻지 못하면 그 말은 하지 않는다(화면에는 남는다).
 * - 포커스를 잃으면(LOSS, LOSS_TRANSIENT) 즉시 멈추고 돌려준다. CAN_DUCK 은 시스템이 낮추도록 둔다.
 * - 선행 묵음 약 200ms, 문장 사이 쉼 350ms 로 천천히 전한다(명세 3.6 R5).
 * - 말마다 끝까지 소리 냈는지(또는 건너뛰었는지)를 [speak] 의 onResult 로 돌려준다. 기록의 횟수는 그것으로만 센다.
 */
class SpeechController(
    context: Context,
    private val scope: CoroutineScope,
    private val speakers: List<Speaker> = listOf(PleosSpeaker(context), AndroidSpeaker(context)),
) {
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** 안내 음성. Pleos 오디오 정책에서 VOICE_COMMAND 컨텍스트(USAGE_ASSISTANT)에 해당한다. */
    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                hasFocus = false
                stop()
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
    private class Pending(val text: String, val onResult: ((Boolean) -> Unit)?)

    private val queue = ArrayDeque<Pending>()
    private var worker: Job? = null
    private var generation = 0
    private var hasFocus = false
    private var active: Speaker? = null
    private var nextSpeakerIndex = 0
    private var audioZoneId: Int? = null
    private val prepareLock = Mutex()

    private val _speaking = MutableStateFlow(false)
    private val _engineName = MutableStateFlow<String?>(null)
    private val _lastSkipped = MutableStateFlow<String?>(null)
    private val _engineMissing = MutableStateFlow(false)
    private var lastPrepareFailedAt = 0L

    /** 말하는 중이거나 말하려고 기다리는 중이면 true */
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** 지금 쓰는 음성 엔진 이름. 아직 준비 전이면 null */
    val engineName: StateFlow<String?> = _engineName.asStateFlow()

    /** 오디오 포커스를 얻지 못해 소리로 내지 못한 마지막 말. */
    val lastSkipped: StateFlow<String?> = _lastSkipped.asStateFlow()

    /** 쓸 수 있는 음성 엔진을 찾지 못했다(화면으로만 알린다). */
    val engineMissing: StateFlow<Boolean> = _engineMissing.asStateFlow()

    /** 음성 엔진을 미리 준비해 첫 말이 늦지 않게 한다. */
    fun warmUp() {
        if (active == null) scope.launch { ensureSpeaker() }
    }

    /**
     * @param interrupt true 면 하던 말과 기다리던 말을 버리고 이 말을 한다
     * @param onResult 끝까지 소리 냈으면 true, 소리 내지 못했으면(포커스 거절, 음성 엔진 없음, 끊김·버림) false.
     *   메인 스레드에서 한 번 불린다
     */
    fun speak(text: String, interrupt: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        if (interrupt) cancelCurrent()
        queue.addLast(Pending(text, onResult))
        _speaking.value = true
        if (worker?.isActive != true) worker = scope.launch { drain() }
    }

    /** 하던 말을 멈추고 기다리던 말도 버린다. */
    fun stop() {
        cancelCurrent()
    }

    private fun cancelCurrent() {
        generation++
        val dropped = queue.toList()
        queue.clear()
        active?.stop()
        dropped.forEach { it.onResult?.invoke(false) }
    }

    /** 운전자에게 주는 짧은 알림음. 어르신을 놀라게 하지 않도록 낮은 음량의 짧은 두 음을 쓴다. */
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
        cancelCurrent()
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
                val speaker = ensureSpeaker()
                if (speaker == null || !acquireFocus()) {
                    _lastSkipped.value = next.text
                    next.onResult?.invoke(false)
                    continue
                }
                _lastSkipped.value = null
                next.onResult?.invoke(say(next.text))
            }
        } finally {
            _speaking.value = false
            abandonFocus()
        }
    }

    /** @return 모든 문장을 끝까지 소리 냈으면 true(끊기거나 엔진이 실패하면 false) */
    private suspend fun say(text: String): Boolean {
        val gen = generation
        val sentences = text.split(SENTENCE_BREAK).filter { it.isNotBlank() }
        for ((i, sentence) in sentences.withIndex()) {
            if (gen != generation) return false
            // 선행 묵음: 엔진이 오디오 앞에 무음을 직접 넣지 못하면 재생을 그만큼 늦춘다.
            val preroll = if (active?.addsPrerollSilence == true) 0L else PREROLL_SILENCE_MS
            delay(if (i == 0) preroll else SENTENCE_GAP_MS)
            if (gen != generation) return false
            var ok = active?.speak(sentence) ?: false
            if (!ok && gen == generation) {
                // 끊긴 것이 아니라 엔진이 실패했다. 남은 엔진이 있으면 바꿔서 한 번 더 읽는다.
                if (nextSpeakerIndex >= speakers.size) return false
                active?.release()
                active = null
                ok = ensureSpeaker()?.speak(sentence) ?: false
            }
            if (!ok) return false
        }
        return gen == generation && sentences.isNotEmpty()
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

    private suspend fun acquireFocus(): Boolean {
        if (hasFocus) return true
        repeat(FOCUS_ATTEMPTS) { attempt ->
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

    private companion object {
        /** 문장부호 뒤의 공백에서 끊는다(부호는 남긴다). */
        val SENTENCE_BREAK = Regex("(?<=[.!?])\\s+")
        const val PREROLL_SILENCE_MS = 200L
        const val SENTENCE_GAP_MS = 350L
        const val FOCUS_ATTEMPTS = 4
        const val FOCUS_RETRY_MS = 1_500L
        const val RETRY_PREPARE_MS = 60_000L
        const val CHIME_VOLUME = 60
        const val CHIME_MS = 350
    }
}
