package app.myeotbeonideun.speech

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import android.speech.tts.TextToSpeech as AndroidTts

/** 한 문장을 소리 내어 읽는 엔진. 구현은 Pleos TTS 와 안드로이드 기본 TTS 둘이다. */
interface Speaker {
    val name: String

    /** 쓸 수 있게 준비한다. 쓸 수 없으면 false */
    suspend fun prepare(attributes: AudioAttributes): Boolean

    /** 다 읽을 때까지 기다린다. 읽지 못했으면 false */
    suspend fun speak(text: String): Boolean

    fun stop()
    fun release()

    /** 동승 좌석 쪽 오디오 존으로 보낸다. 지원하지 않으면 아무 일도 하지 않는다. */
    fun setAudioZone(zoneId: Int) {}

    /** 오디오 데이터 앞에 무음(선행 묵음)을 직접 넣는 엔진인지. */
    val addsPrerollSilence: Boolean get() = false
}

/**
 * Pleos TextToSpeech SDK.
 *
 * - ON_DEVICE 모드만 쓴다: 네트워크가 필요 없고 앱 등록(클라이언트 ID)도 필요 없다(Mode.ON_DEVICE 문서).
 * - SDK 에 말 빠르기를 정하는 API 가 없어, 문장을 하나씩 끊어 읽고 사이에 쉼을 두는 방식으로 천천히 전한다
 *   ([SpeechController]).
 */
class PleosSpeaker(context: Context) : Speaker {
    override val name = "Pleos TextToSpeech (ON_DEVICE)"

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    private var current: CompletableDeferred<Boolean>? = null

    private val listener = object : EventListener {
        override fun onReady() {}
        override fun onStart() {}
        override fun onDone() { current?.complete(true) }
        override fun onStop() { current?.complete(false) }
        override fun onError(errorType: ErrorType) { current?.complete(false) }
        override fun onUpdatedRms(rms: Double) {}
    }

    override suspend fun prepare(attributes: AudioAttributes): Boolean {
        return try {
            val engine = TextToSpeech(appContext, Mode.ON_DEVICE)
            val ready = CompletableDeferred<Boolean>()
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { ready.complete(true) }
                override fun onFailure(errorType: ErrorType) { ready.complete(false) }
            })
            val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
            if (!ok) {
                runCatching { engine.release() }
                return false
            }
            engine.setLocale(Locale.KOREA)
            engine.setAudioAttributes(attributes)
            engine.addEventListener(listener)
            tts = engine
            true
        } catch (t: Throwable) {
            false
        }
    }

    override suspend fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val done = CompletableDeferred<Boolean>()
        current = done
        return try {
            engine.speak(text)
            withTimeoutOrNull(speakTimeoutMs(text)) { done.await() } ?: false
        } catch (t: Throwable) {
            false
        } finally {
            if (current === done) current = null
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        current?.complete(false)
    }

    override fun setAudioZone(zoneId: Int) {
        runCatching { tts?.setAudioZoneId(zoneId) }
    }

    override fun release() {
        runCatching { tts?.removeEventListener(listener) }
        runCatching { tts?.release() }
        tts = null
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 4_000L
    }
}

/**
 * 안드로이드 기본 TextToSpeech. Pleos TTS 를 쓸 수 없을 때(체험 모드, 일반 AAOS 기기) 대신 쓴다.
 * 느리고 낮게 말하고(명세 3.6 R5), 오디오 데이터 앞에 200ms 무음을 넣는다(Pleos 선행 묵음 가이드).
 */
class AndroidSpeaker(context: Context) : Speaker {
    override val name = "Android TextToSpeech"
    override val addsPrerollSilence = true

    private val appContext = context.applicationContext
    private var tts: AndroidTts? = null
    private var current: CompletableDeferred<Boolean>? = null
    private var currentId: String? = null
    private var seq = 0

    override suspend fun prepare(attributes: AudioAttributes): Boolean {
        return try {
            val ready = CompletableDeferred<Boolean>()
            val engine = AndroidTts(appContext) { status -> ready.complete(status == AndroidTts.SUCCESS) }
            val ok = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false
            if (!ok) {
                runCatching { engine.shutdown() }
                return false
            }
            val lang = engine.setLanguage(Locale.KOREAN)
            if (lang == AndroidTts.LANG_MISSING_DATA || lang == AndroidTts.LANG_NOT_SUPPORTED) {
                runCatching { engine.shutdown() }
                return false
            }
            engine.setSpeechRate(SPEECH_RATE)
            engine.setPitch(PITCH)
            engine.setAudioAttributes(attributes)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { finish(utteranceId, true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { finish(utteranceId, false) }
                override fun onError(utteranceId: String?, errorCode: Int) { finish(utteranceId, false) }
                override fun onStop(utteranceId: String?, interrupted: Boolean) { finish(utteranceId, false) }
            })
            tts = engine
            true
        } catch (t: Throwable) {
            false
        }
    }

    private fun finish(utteranceId: String?, ok: Boolean) {
        if (utteranceId != null && utteranceId == currentId) current?.complete(ok)
    }

    override suspend fun speak(text: String): Boolean {
        val engine = tts ?: return false
        val done = CompletableDeferred<Boolean>()
        val id = "u${++seq}"
        current = done
        currentId = id
        return try {
            engine.playSilentUtterance(PREROLL_SILENCE_MS, AndroidTts.QUEUE_FLUSH, "$id-pre")
            val result = engine.speak(text, AndroidTts.QUEUE_ADD, Bundle(), id)
            if (result != AndroidTts.SUCCESS) return false
            withTimeoutOrNull(speakTimeoutMs(text)) { done.await() } ?: false
        } catch (t: Throwable) {
            false
        } finally {
            if (current === done) {
                current = null
                currentId = null
            }
        }
    }

    override fun stop() {
        runCatching { tts?.stop() }
        current?.complete(false)
    }

    override fun release() {
        runCatching { tts?.shutdown() }
        tts = null
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 5_000L
        const val SPEECH_RATE = 0.85f
        const val PITCH = 0.92f
        const val PREROLL_SILENCE_MS = 200L
    }
}

/** 한 문장을 읽는 데 줄 수 있는 가장 긴 시간. 넘으면 실패로 본다. */
internal fun speakTimeoutMs(text: String): Long = 6_000L + text.length * 450L
