package app.myeotbeonideun.speech

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import android.os.Handler
import android.os.Looper
import app.myeotbeonideun.engine.intent.IntentMatcher
import app.myeotbeonideun.engine.intent.PassengerIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class ListenState { OFF, STARTING, LISTENING, UNAVAILABLE }

/**
 * '물으면 대답하기(실험 기능)'. 이 인터페이스 뒤에 두어, 음성 인식이 되지 않아도
 * 앱의 나머지(먼저 말해 주기, 운전자가 시키기)는 그대로 돈다.
 */
interface PassengerListener {
    val state: StateFlow<ListenState>

    /** 듣기를 시작한다. 물음의 종류를 알아냈을 때만 [onIntent] 를 부른다. */
    fun start(onIntent: (PassengerIntent) -> Unit)

    /** 차가 말하는 동안에는 듣지 않는다(제 목소리를 물음으로 알아듣지 않게). */
    fun pause()
    fun resume()
    fun stop()
}

/**
 * Pleos SpeechToText SDK(ON_DEVICE: 기기 안 엔진, 네트워크와 앱 등록이 필요 없다).
 *
 * 사생활: 인식 문장은 [IntentMatcher] 에 넘겨 물음의 종류(다섯 가지 중 하나)만 얻고 곧바로 버린다.
 * 문장도 음성도 저장하지 않고, 로그에도 남기지 않는다.
 */
class PleosPassengerListener(context: Context) : PassengerListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(ListenState.OFF)
    override val state: StateFlow<ListenState> = _state.asStateFlow()

    private var stt: SpeechToText? = null
    private var onIntent: ((PassengerIntent) -> Unit)? = null
    private var running = false
    private var paused = false
    private var errors = 0

    private val request = Runnable {
        if (running && !paused) runCatching { stt?.request() }.onFailure { giveUp() }
    }

    private val listener = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() {
            main.post { if (running) _state.value = ListenState.LISTENING }
        }

        override fun onUpdated(stt: String, completed: Boolean) {
            if (!completed) return
            val intent = IntentMatcher.match(stt) ?: return // 문장은 여기서 버린다
            main.post {
                errors = 0
                if (running && !paused) onIntent?.invoke(intent)
            }
        }

        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {}

        override fun onEndedRecognition() {
            main.postDelayed(request, RESTART_MS)
        }

        override fun onError(errorType: ErrorType) {
            main.post {
                if (!running) return@post
                if (++errors >= MAX_ERRORS) giveUp() else main.postDelayed(request, RETRY_MS)
            }
        }
    }

    override fun start(onIntent: (PassengerIntent) -> Unit) {
        if (running) return
        this.onIntent = onIntent
        running = true
        paused = false
        errors = 0
        _state.value = ListenState.STARTING
        try {
            val engine = SpeechToText(appContext, Mode.ON_DEVICE)
            stt = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        if (!running) return@post
                        runCatching {
                            engine.setLocale(Locale.KOREA)
                            engine.addListener(listener)
                        }.onFailure { giveUp() }
                        main.post(request)
                    }
                }

                override fun onFailure(errorType: ErrorType) {
                    main.post { giveUp() }
                }
            })
        } catch (t: Throwable) {
            giveUp()
        }
    }

    override fun pause() {
        if (!running || paused) return
        paused = true
        main.removeCallbacks(request)
        runCatching { stt?.stop() }
    }

    override fun resume() {
        if (!running || !paused) return
        paused = false
        main.postDelayed(request, RESTART_MS)
    }

    override fun stop() {
        running = false
        main.removeCallbacks(request)
        release()
        _state.value = ListenState.OFF
    }

    /** 음성 인식을 쓸 수 없다. 조용히 물러나고 앱의 나머지는 그대로 둔다. */
    private fun giveUp() {
        running = false
        main.removeCallbacks(request)
        release()
        _state.value = ListenState.UNAVAILABLE
    }

    private fun release() {
        val engine = stt ?: return
        stt = null
        runCatching { engine.stop() }
        runCatching { engine.removeListener(listener) }
        runCatching { engine.release() }
    }

    private companion object {
        const val RESTART_MS = 600L
        const val RETRY_MS = 2_000L
        const val MAX_ERRORS = 3
    }
}
