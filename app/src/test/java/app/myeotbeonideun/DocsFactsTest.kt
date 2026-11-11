package app.myeotbeonideun

import app.myeotbeonideun.engine.script.Audience
import app.myeotbeonideun.engine.script.ScriptBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 문서(README.md, docs 아래 .md)의 수치와 문장이 저장소의 실제와 맞는지 본다(독립 검토의 '사실과 다른 주장' 15건, 결함 13·19).
 *
 * - 대본 상황·줄 수와 변형 수, 시험 수(@Test), 화면 그림 수는 소스에서 세어 문서의 글과 견준다.
 *   시험을 더하거나 대본을 고치면 이 시험이 깨진다 → 실패 메시지에 나온 문장으로 README·docs/architecture.md 를 고친다.
 * - 고친 옛 주장(부풀린 효과, 'check*Capabilities 를 모두 거친다', '변형 4개', '횟수뿐' 등)이 다시 들어오지 않게 막는다.
 * - 개인정보 문서가 앱이 실제로 저장하는 항목([PrivacyWordingTest.STORED_ROUTE] 등)을 빠뜨리지 않는지 본다.
 * - 문서가 가리키는 저장소 안의 파일이 실제로 있는지 본다.
 */
class DocsFactsTest {

    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").exists() }

    private fun text(path: String) = File(root, path).readText()

    private val readme get() = text("README.md")
    private val architecture get() = text("docs/architecture.md")

    private fun docs(): List<File> =
        listOf(File(root, "README.md")) + File(root, "docs").listFiles { f -> f.extension == "md" }!!.sortedBy { it.name }

    private fun assertMentions(doc: String, name: String, phrase: String, why: String) =
        assertTrue("$name 에 \"$phrase\" 가 없다 — $why", doc.contains(phrase))

    @Test
    fun `README 의 대본 수치는 대본 파일에서 센 값과 같다`() {
        val book = ScriptBook.parse(text("app/src/main/assets/scripts_ko.json"))
        val situations = book.situations.values
        val lines = situations.sumOf { it.lines.size }
        assertMentions(readme, "README", "${situations.size}개 상황 ${lines}줄", "대본 상황·줄 수")

        fun variants(audience: Audience): Pair<Int, Int> {
            val list = situations.filter { it.to == audience }
            val counts = list.map { it.lines.size }.distinct()
            assertEquals("$audience 상황마다 변형 수가 같아야 README 한 문장으로 적을 수 있다: $counts", 1, counts.size)
            return list.size to counts.single()
        }
        val (_, passenger) = variants(Audience.PASSENGER)
        val (driverSituations, driver) = variants(Audience.DRIVER)
        val (cardSituations, card) = variants(Audience.CARD)
        assertEquals("운전자 알림과 카드의 변형 수", driver, card)
        assertMentions(
            readme, "README",
            "동승자에게 하는 말은 상황마다 ${passenger}개, 운전자 알림 ${driverSituations}개 상황과 카드 ${cardSituations}개 상황은 ${driver}개씩",
            "변형 수(사실과 다른 주장 9)",
        )
    }

    @Test
    fun `README 와 구조 문서의 시험 수는 소스의 Test 수와 같다`() {
        val engine = countTests(File(root, "engine/src/test"))
        val app = countTests(File(root, "app/src/test"))
        assertMentions(readme, "README", "JVM 단위 테스트 ${engine + app}개(엔진 $engine, 앱 $app)", "테스트 환경 절의 시험 수")
        assertMentions(readme, "README", "**엔진(순수 Kotlin, ${engine}개)**", "테스트 절의 엔진 시험 수")
        assertMentions(readme, "README", "**앱(${app}개)**", "테스트 절의 앱 시험 수")
        assertMentions(architecture, "docs/architecture.md", "| `engine/src/test` | $engine |", "시험 표의 엔진")
        assertMentions(architecture, "docs/architecture.md", "| `app/src/test` | $app |", "시험 표의 앱")
    }

    @Test
    fun `화면 그림 수와 이름은 그림 폴더와 ScreenshotTest 와 문서가 같다`() {
        val pngs = File(root, "docs/screens").listFiles { f -> f.extension == "png" }!!.map { it.nameWithoutExtension }.toSet()
        val shotSource = text("app/src/test/java/app/myeotbeonideun/ScreenshotTest.kt")
        val shots = SHOT_NAME.findAll(shotSource).map { it.groupValues[1] }.toSet()
        assertEquals("docs/screens 의 그림과 ScreenshotTest 가 그리는 그림(옛 그림이 남았거나 다시 뽑지 않음)", shots, pngs)
        assertEquals("ScreenshotTest 의 시험 수", pngs.size, shotSource.lines().count { TEST_LINE.matches(it) })
        assertMentions(readme, "README", "화면 그림 ${pngs.size}장", "화면 그림 수")
        assertMentions(architecture, "docs/architecture.md", "| ${pngs.size}장 |", "시험 표의 화면 그림 수")
    }

    @Test
    fun `README 와 docs 에 고친 옛 주장이 남아 있지 않다`() {
        val problems = mutableListOf<String>()
        for (file in docs()) {
            val doc = file.readText()
            for ((phrase, why) in STALE) if (doc.contains(phrase)) problems += "${file.name}: \"$phrase\" — $why"
        }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun `개인정보 문서는 앱이 저장하는 항목을 빠뜨리지 않는다`() {
        // 저장 항목 목록이 바뀌면(PrivacyWordingTest) 여기의 이름표도 함께 정해야 한다.
        assertEquals(PrivacyWordingTest.STORED_ROUTE, ROUTE_LABELS.keys)
        assertEquals(PrivacyWordingTest.STORED_RECORD, RECORD_LABELS.keys)
        assertEquals(PrivacyWordingTest.STORED_SETTINGS, SETTINGS_LABELS.keys)
        val privacy = text("docs/privacy.md")
        for (label in (ROUTE_LABELS.values + RECORD_LABELS.values + SETTINGS_LABELS.values).filterNotNull().toSet()) {
            assertMentions(privacy, "docs/privacy.md", label, "저장 항목(결함 13)")
        }
        val readmePrivacy = readme.substringAfter("## 개인정보").substringBefore("\n## ")
        for (label in listOf("직접 쓴 한마디", "마지막으로 쓴 시각", "도착 지점 좌표", "시작 시각", "걸린 시간", "환경설정", "모두 지우기")) {
            assertMentions(readmePrivacy, "README '개인정보'", label, "저장 항목(사실과 다른 주장 3)")
        }
    }

    @Test
    fun `README 의 헤드업 대상은 엔진이 급하다고 내는 운전자 알림을 모두 적는다`() {
        // 엔진 소스에서 급한(urgent = true) 운전자 알림 상황을 모은다(검토 NEW-9 — 안전띠가 빠져 있었다).
        val engine = text("engine/src/main/kotlin/app/myeotbeonideun/engine/CompanionEngine.kt")
        val urgent = URGENT_NOTIFY.findAll(engine).map { it.groupValues[1] }.toSet()
        assertEquals("급한 운전자 알림 상황이 바뀌면 README 문구 표도 함께 정한다", URGENT_NOTICE_WORDS.keys, urgent)
        val bullet = readme.lines().first { it.startsWith("- **운전자 알림**") }
        for ((situation, words) in URGENT_NOTICE_WORDS) {
            assertMentions(bullet, "README '운전자 알림'", words, "헤드업 대상($situation)")
        }
    }

    @Test
    fun `문서가 가리키는 저장소 안의 파일이 있다`() {
        val missing = mutableListOf<String>()
        var checked = 0
        for (file in docs()) {
            val doc = file.readText()
            // 링크는 그 문서가 있는 폴더 기준, 백틱 안의 경로는 저장소 뿌리 기준이다.
            val links = LINK.findAll(doc).map { it.groupValues[1] }.filterNot { it.startsWith("http") }
                .map { File(file.parentFile, it) to it }
            val paths = CODE_PATH.findAll(doc).map { it.groupValues[1] }.filterNot { "/build/" in it }
                .map { File(root, it) to it }
            for ((target, written) in (links + paths).toSet()) {
                checked++
                if (!target.exists()) missing += "${file.name}: $written"
            }
        }
        assertTrue("문서에서 저장소 경로를 하나도 찾지 못했다(정규식 확인)", checked > 10)
        assertEquals(emptyList<String>(), missing)
    }

    private fun countTests(dir: File): Int = dir.walkTopDown().filter { it.isFile && it.extension in setOf("kt", "java") }
        .sumOf { f -> f.readLines().count { TEST_LINE.matches(it) } }

    companion object {
        /** `@Test` 나 `@Test(expected = …)` 로 시작하는 줄(`@TestOnly` 같은 것은 아님). */
        private val TEST_LINE = Regex("""^\s*@Test(?![A-Za-z]).*""")
        private val SHOT_NAME = Regex("""shot\(\s*"([0-9]{2}_[a-z_]+)"""")
        /** 마크다운 링크의 대상(저장소 안 상대 경로). */
        private val LINK = Regex("""\]\(([^)#\s]+)\)""")
        /** 백틱 안의 저장소 경로(docs/, tools/, app/, engine/, apk/ 로 시작, 줄임표·자리표시 없는 것). */
        private val CODE_PATH = Regex("""`((?:docs|tools|app|engine|apk)/[^`\s<>…*]+)`""")

        /** 엔진이 급한 운전자 알림을 내는 줄: notify("상황", urgent = true, …). */
        private val URGENT_NOTIFY = Regex("""notify\("([a-z_]+)", urgent = true""")

        /** 급한 운전자 알림 상황 → README '운전자 알림' 줄에 있어야 하는 말. */
        private val URGENT_NOTICE_WORDS = mapOf(
            "door_open_d" to "주행 중 문 열림",
            "away_door_d" to "운전자가 없는 동안의 문·자리",
            "away_seat_d" to "운전자가 없는 동안의 문·자리",
            "belt_off_d" to "안전띠",
        )

        /** 고친 옛 주장의 특징적인 조각 → 왜 틀렸는지. */
        private val STALE = mapOf(
            "직접 대답하지 않아도" to "앱이 세는 것은 '차가 대신 말한 횟수'다(결함 8, 사실과 다른 주장 13)",
            "모든 Pleos 호출은" to "check*Capabilities·onFailed 를 거치는 것은 Vehicle SDK 뿐이다(사실 2)",
            "상황마다 변형 4개)" to "운전자 알림·카드는 2개씩이다(사실 9)",
            "횟수뿐" to "기록에는 시작 시각·걸린 시간이, 자주 가는 길에는 좌표·직접 쓴 한마디도 남는다(결함 13, 사실 3·4)",
            "기록뿐" to "자주 가는 길에 좌표·직접 쓴 한마디도 남는다(결함 13)",
            "알림음은 운전자에게만" to "알림음은 차 스피커로 나 동승자에게도 들린다(사실 5)",
            "스스로 체험 모드로 실행" to "차량용 기기는 신호가 없어도 체험 모드로 넘어가지 않는다(결함 6·19)",
            "자동으로 체험 모드가 됩" to "차량용 기기는 신호가 없어도 체험 모드로 넘어가지 않는다(결함 6)",
            "SDK 바이너리는 저장소에 넣지 않았" to "제출용 APK 에는 SDK 가 빌드 결과로 들어 있다(사실 10)",
            "속도를 바꿀 수 없" to "가상 콘솔로 속도를 바꿀 수 있다(2026-11-11 실기)",
            "에뮬레이터와 실차에서는 아직" to "2026-11-11 에뮬레이터에서 확인한 범위를 적는다(결함 1)",
            "에뮬레이터 실행 검증을 하지 못했" to "2026-11-11 에뮬레이터에서 확인한 범위를 적는다(결함 1)",
            "폴링이 아니라 리스너로" to "동행 중 내비 30초 조회와 엔진의 1초 타이머가 있다(사실 6)",
            "는 사실이지만" to "속도 권한이 없으면 '정지'는 브레이크를 밟고 있음이다(사실 1)",
            "주행 단인데 20초 넘게 정지" to "속도 권한이 없으면 '정지'는 브레이크를 밟고 있음이다(사실 1)",
            "에뮬레이터에서 실제로 해 본 기록은 아직 없" to "2026-11-11 에뮬레이터에서 A 순서를 돌렸다",
        )

        /** 자주 가는 길의 저장 항목 → docs/privacy.md 에 있어야 하는 말(null 은 내부 번호). */
        private val ROUTE_LABELS = mapOf(
            "id" to null, "honorific" to "호칭", "place" to "가는 곳 이름", "purpose" to "가서 할 일",
            "customPurposeLine" to "직접 쓴 화제 전환 한마디", "driver" to "운전하는 사람", "seat" to "자리", "interval" to "간격",
            "rememberArrival" to "도착 지점 기억하기를 켰는지", "arrivalLat" to "도착 지점 좌표", "arrivalLon" to "도착 지점 좌표",
            "useCount" to "그 길을 쓴 횟수", "lastUsedAtMs" to "마지막으로 쓴 시각",
        )
        private val RECORD_LABELS = mapOf(
            "startedAtMs" to "시작 시각", "durationSec" to "걸린 시간", "proactive" to "먼저 말함", "driverRequested" to "다시 말씀드리기",
            "answered" to "물음에 대답", "stopNotices" to "멈춤 안내", "safetyNotices" to "문·창문·안전띠 안내", "skipped" to "끝까지 소리 내지 못한 말",
        )
        private val SETTINGS_LABELS = mapOf(
            "demoMode" to "체험 모드", "listenEnabled" to "물으면 대답하기", "windowThresholdPercent" to "창문 안내 기준",
            "audioZoneId" to "오디오 존 번호", "noticeAccepted" to "첫 실행 안내를 읽었는지", "seatReading" to "좌석 값 읽기",
        )
    }
}
