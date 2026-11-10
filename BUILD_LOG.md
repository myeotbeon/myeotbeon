# 제작 기록 (다음 담당에게)

「몇 번이든」 앱을 만들면서 한 일, 빌드 방법, 남은 일을 단계별로 적습니다.
제품 명세의 번호(3.1, 3.6, 10절 등)는 기획 문서 `제품명세_몇번이든_v1.md` 의 것입니다.

## 지금 상태 (2026-11-10, 통합·검수 뒤)

- `./gradlew clean` 뒤 `./gradlew testDebugUnitTest assembleDebug assembleRelease` 통과.
- 단위 테스트 170개 통과(엔진 120, 앱 50). lint 는 오류 없음(경고 10건: 의존성 최신 버전 안내 7, 시험 코드의 이름 규칙 2, 리소스 폴더 버전 표기 1).
- 대본 완성본(35개 상황, 118줄)이 들어갔다. 대본 검사기 오류 0건, 주의 3건(운전자 알림 세 상황의 변형이 2개).
- 제출용 APK: `apk/개인참가_몇번이든_release.apk` (38,295,093바이트, 서명 v2).
- 화면 그림 17장을 `docs/screens/` 에 다시 뽑음(Roborazzi, 대본 교체로 9장이 바뀜).
- **에뮬레이터·기기에서 실행해 보지 못했음.** 개발 PC 에 에뮬레이터 가속이 없어서다. Pleos API 는 문서 시그니처와 컴파일로 맞췄고,
  앱 흐름은 Robolectric(`AppFlowTest`)으로 체험 모드 신호를 넣어 출발~도착~기록까지 확인했다.

## 빌드 방법

```bash
# local.properties (저장소에 올리지 않음)
#   sdk.dir=<Android SDK 경로>
#   myeotbeon.signing=<저장소 밖 keystore.properties 경로>     ← 릴리스 서명을 할 때만
./gradlew testDebugUnitTest assembleDebug assembleRelease --console=plain
./gradlew --stop
```

- JDK 17, Gradle 8.9(wrapper), AGP 8.5.2, Kotlin 2.0.20. `gradle.properties` 에 `org.gradle.jvmargs=-Xmx2g`.
- 릴리스 서명: `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)를 저장소 밖에 두고
  `-Pmyeotbeon.signing=`, 환경변수 `MYEOTBEON_SIGNING`, `local.properties` 의 `myeotbeon.signing=` 중 하나로 알려 준다.
  없으면 서명 없는 release 가 나온다. 키스토어와 비밀번호의 실제 위치는 개발 환경 기록(TOOLCHAIN.md, 저장소 밖)에 있다.
- 산출물: `app/build/outputs/apk/debug/app-debug.apk`, `app/build/outputs/apk/release/app-release.apk`
- 화면 그림 다시 뽑기: `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true` → `docs/screens/*.png`
- 병합 매니페스트: `app/build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml`

## 단계별로 한 일

### 1. 골격
- 시험 앱(probe)의 통과한 버전 조합을 그대로 가져옴. 모듈 둘: `:engine`(Kotlin JVM), `:app`(안드로이드).
- wrapper 의 `distributionUrl` 은 공식 https 주소. `.gitignore` 에 local.properties, *.jks, keystore.properties, build, .gradle.
- Pleos SDK 는 Vehicle·NaviHelper·TextToSpeech·SpeechToText·FusedLocation 다섯 개(LLM 은 뺌). `abiFilters` x86_64, arm64-v8a.
- `:app:testDebugUnitTest` 가 `:engine:test` 에 의존하게 해서 명령 하나로 전체 테스트가 돈다.

### 2. engine
- 구간(`StageCalculator`), 간격(`IntervalPolicy`), 멈춤 이유(`StopClassifier`), 대본(`ScriptBook`·`ScriptEngine`·`Josa`),
  대본 검사기(`ScriptValidator`), 물음 종류(`IntentMatcher`), 기록(`RecordAggregator`), 이들을 묶는 `CompanionEngine`.
- 시각을 바깥에서 넣는 구조라 테스트가 그대로 재현된다. 판단 규칙과 기준값은 `docs/architecture.md`.
- 정한 것(명세에 없던 세부):
  - 구간 경계: 10% / 40% / 75%(그리고 남은 20분 이하) / 남은 5분. 구간은 앞으로만 간다.
  - '다시 말씀드리기'는 지금의 사실을 말한다: 도착했으면 `arrive`, 서 있으면 그 이유, 출발 직후면 `ask_where`, 아니면 그 구간 문장.
  - '잠시 조용히' 중에도 문 열림·도착·운전자 이탈 안내는 한다(운전자가 차에 없을 때가 가장 불안한 때라서).
  - 정지 판단: 속도 신호가 살아 있다고 확인되기 전에는 브레이크 페달로만 본다(에뮬레이터는 속도가 0 에 머묾).
  - 화제 전환은 `ask_*`·`stage_*` 대답에만 붙인다(멈춤·안전·도착 안내에는 붙이지 않음).

### 3. signal
- `SignalSource` 인터페이스 + `PleosSignalSource`(Vehicle·NaviHelper·FusedLocation·Car API) + `DemoSignalSource`(체험 모드).
- 모든 Pleos 호출은 `check*Capabilities` → `getCurrent*` → `register*`, 각 `onFailed` 처리, 전체를 try/catch 로 감쌈.
- 차량용 기기가 아니거나 4초 안에 신호를 하나도 못 얻으면 체험 모드로 넘어간다.
- 신호 → 기능 → API → 문서 주소는 `docs/signals.md`.

### 4. speech
- `SpeechController`: Pleos TTS(ON_DEVICE) → 안드로이드 TTS, 오디오 포커스(GAIN_TRANSIENT_MAY_DUCK), 선행 묵음 200ms, 문장 사이 350ms.
- `PleosPassengerListener`: STT(ON_DEVICE). 실패하면 조용히 물러난다. 인식 문장은 저장하지 않는다.

### 5. ui · data · service · gleo
- 화면: 출발 전 설정, 동행 중, 동승자 카드, 오늘의 기록, 환경설정, 개발자 패널. 화면은 상태를 갖지 않고 `…Ui` 값만 받아 그린다.
- `AppStore`(SharedPreferences): 자주 가는 길 8건, 기록 60건, 환경설정. 모두 지우기.
- `TripController` + `CompanionService`(동행 중에만 포그라운드).
- Gleo: `assets/schema.json`(함수 3개), 브로드캐스트 수신(`GleoActionReceiver`), 액티비티 수신(`START_COMPANION_TRIP`), 응답 브로드캐스트.

### 6. 테스트와 화면 그림
- 엔진 107개, 앱 42개(통합 뒤에는 엔진 120개, 앱 50개). `ScreenshotTest` 는 실제 엔진을 돌려 얻은 문장으로 화면을 그린다.
- 화면 크기는 Pleos Connect 창 크기(가로 1700x1212, 세로 840x1212, 160dpi)로 잡았다.

### 7. 통합·검수

대본 완성본을 넣고 명세 3절과 하나씩 견주어 고친 것.

- **대본**: 대본 담당의 `scripts_ko.json` 을 그대로 복사했다(내용을 고치지 않음). 엔진 쪽을 맞췄다.
  - 화제 전환 줄이 `{호칭}, ` 으로 시작한다 → 대답 뒤에 붙일 때 엔진이 호칭 부름을 뗀다(한 번의 말에 호칭 한 번).
    검사기도 화제 전환 줄에 호칭을 요구하게 바꿨다.
  - `ScriptExpansionTest`: 호칭 6 x 운전자 5 x 곳 4 = 120가지로 대본 전체(14,160줄)를 펼쳐 조사와 문장 길이를 본다.
    정답은 `Josa` 와 따로 손으로 적은 표다. 가장 긴 문장 17음절, 틀린 조사 0건.
- **분 숫자**: "5분쯤"(`stage_5min`)을 남은 시간 5분 이하이면 언제나 쓰던 것을, 3분 넘고 5분 이하일 때만 쓰게 했다.
  그보다 가깝거나 남은 시간을 모르면(근접 알림) `stage_near`. 명세 6절 8번(남은 2분 → "거의 다 왔어요")과 맞는다.
- **출발 안내**: 5분이 안 되는 짧은 길에서 출발 안내 없이 "5분쯤 남았어요"부터 말하던 것을 고쳤다. 첫마디는 언제나 `depart`.
- **우선순위**: 문과 창문이 함께 열리면 창문 안내가 문 안내를 끊던 것을 고쳤다(안전 알림끼리는 차례로).
  운전자 화면의 알림 한 줄도 급한 것(문)이 덜 급한 것(창문)에 밀리지 않게 했다.
- **집에 가자**: 가는 곳이 집이면 "집을 들렀다가 집에 가요"가 되던 것을 `ask_where`("집에 가요")로 대답하게 했다.
- **화면**: 주행 중 화면의 글 세 줄·버튼 둘·스크롤 없음과 동승자 카드 두 줄을 그려서 세는 시험(`CompanionLayoutTest`)을 더했다.
- **문구**: 앱 안 개인정보 안내의 "인터넷으로 아무것도 보내지 않아요"를 "서버가 없어요. 말하기와 알아듣기는 기기 안 음성 엔진만 써요"로
  바꿨다(SDK 가 병합 매니페스트에 INTERNET 을 넣으므로). "사람이 쓴 고정 대본"은 "미리 써 둔 고정 대본"으로 바꿨다
  (사람이 소리 내어 읽는 검수를 아직 하지 않았다).
- **저장소**: `gradlew` 에 실행 권한(100755)을 줬다. README 의 예문을 실제 대본과 맞추고, 실제로 돌려 본 범위를 적었다.

검사 방법과 결과 전체는 기획 폴더의 `QA_보고.md` 에 있다.

## 문서·명세와 실제가 달랐던 점

1. 명세 3.1 의 소개말 예시("저는 가는 길을 알려 드리는 목소리예요")는 19음절이라 명세 3.6 R2(18음절)를 넘는다 → 대본에는 "저는 길을 알려 드리는 목소리예요"(17음절)로 넣었다.
2. 명세 3.3 의 도착·운전자 이탈 예시는 세 문장인데 R2 는 두 문장까지다 → 두 문장으로 줄였다.
3. 가이드의 `door.registerDoorPosition(doorPositionListener = l)` 은 실제로 `registerDoorPosition(area, listener)` 다(API 참조와 컴파일로 확인).
4. `check*Capabilities` 는 오버로드가 둘(옛 것은 deprecated)이라 람다 인자 타입을 적어야 새 것(`CapabilityConfig` 포함)이 골라진다.
5. `CarAudioManager` 의 오디오 존 조회 API 가 공개 스텁에 없어 좌석 → 존 번호를 코드로 알아낼 수 없다 → 환경설정에서 번호를 고르게 했다.
6. Pleos TTS 에는 말 빠르기 API 가 없다 → 문장 단위로 끊어 읽고 쉼을 둔다.
7. 주행 중 사용 가능(DO) 선언 방법이 Pleos 문서에 없다 → AAOS 표준 `distractionOptimized` 메타데이터를 썼다.
8. 병합 매니페스트에 `android.permission.INTERNET` 이 들어간다(TextToSpeech, SpeechToText, universal-access). 앱은 선언하지 않았다.
9. 에뮬레이터 화면은 2560x1440 에 160dpi 라 1dp = 1px 이다. 글자·버튼 크기를 이 기준으로 잡았다.
10. 명세 3.2 는 "남은 시간이 5분 이하일 때만 5분쯤"이라고 하는데, 명세 6절 8번은 남은 2분에 "거의 다 왔어요"다.
    남은 1~2분에 "5분쯤"은 사실과 다르므로 3분 넘고 5분 이하일 때만 쓰게 했다.
11. 명세 10절은 동승자 줄이 모두 호칭으로 시작한다고 하고, 3.2 는 화제 전환을 대답 뒤에 붙인다고 한다.
    그대로 붙이면 호칭이 두 번 들려, 붙일 때는 호칭을 뗀다.

## 남은 일

### 통합 단계에서
- [x] 대본 완성본을 `app/src/main/assets/scripts_ko.json` 에 복사, 엔진을 맞춤(위 7).
      - 대본 담당의 검사기(`lint_scripts.py`)는 낱말 규칙이 더 엄격하다(구간·안내·목적지 같은 말 금지 등). 앱의 검사기는 명세 3.6·10절까지만 본다.
        대본을 고치면 두 검사기를 모두 돌린다.
- [x] 화면 그림 다시 뽑기.
- [x] 제출용 APK 를 `apk/개인참가_몇번이든_release.apk` 로 복사(`.gitignore` 가 `apk/*.apk` 는 허용). 소스를 고치면 APK 도 다시 만들어 넣는다.
- [ ] 원격 저장소는 아직 없다(저장소 묶음으로 넘김). 올릴 때 커밋 작성자는 이미 `myeotbeon-dev` 로 되어 있다.
      `git push` 전에 `git log --format='%an %ae'` 로 작성자를 한 번 더 본다.
- [ ] 대본을 사람이 소리 내어 읽어 보고 고친다(고친 뒤 `./gradlew testDebugUnitTest`, APK 다시 만들기).

### 에뮬레이터가 준비되면(우선순위순)
- [ ] 설치·실행, `환경설정 > 차량 신호`와 `개발자 패널 > 연결 기록`으로 각 신호가 잡히는지 확인.
- [ ] 가상 콘솔로 `docs/demo.md` A 순서를 그대로 해 보기.
- [ ] 기어 D 에서 `CarUxRestrictions`·`GEAR_SELECTION` 이 오는지. 안 오면 `adb … driving_regulation on` 으로 대신.
- [ ] 창문·문 위치 값의 방향(지금은 최솟값 = 닫힘으로 가정. `PleosSignalSource.openPercent`).
- [ ] Pleos TTS(ON_DEVICE)가 소리를 내는지, `setAudioZoneId` 에 어떤 번호가 맞는지.
- [ ] NaviHelper 가 에뮬레이터 내비에서 `onDestinationInfo`·`onDestinationArrived` 를 주는지. 목적지가 없을 때 `DestinationInfo` 값이 무엇인지.
- [ ] STT(ON_DEVICE) 인식, Gleo 호출(스키마 승인 전에도 오는지).
- [ ] 주행 중 CPU 5% 미만·RAM 400MB 이하(Pleos 성능 기준) 측정.

### 하면 좋은 것
- [ ] R8 축소(`isMinifyEnabled`): APK 38MB → 줄일 수 있으나 Pleos SDK 난독화 규칙을 실기에서 확인한 뒤에.
- [ ] 가족 목소리 녹음, 좌석별 다인 안내(명세 7 의 확대 계획).
