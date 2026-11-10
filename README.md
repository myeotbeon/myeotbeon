# 몇 번이든

> 내비의 남은 길과 좌석·문·창문 신호를 연동해, 기억이 흐려진 부모님이 "어디 가?"를 몇 번 물으셔도
> 차가 그 자리에서 매번 처음처럼 대답하고, 운전하는 가족은 앞만 보게 하는 동행 앱.

현대자동차그룹 Pleos Connect(AAOS) 용 앱입니다. 패키지 `app.myeotbeonideun`, 버전 1.0.0.

| 동행 중(주행 중에도 쓰는 화면) | 주행 중 문 열림 |
|---|---|
| ![동행 중](docs/screens/03_companion_half.png) | ![문 열림](docs/screens/05_companion_door_open.png) |

| 출발 전 10초 설정 | 동승자 카드 |
|---|---|
| ![출발 전 설정](docs/screens/01_setup.png) | ![동승자 카드](docs/screens/12_passenger_card.png) |

화면 그림 17장은 [`docs/screens/`](docs/screens)에 있습니다. 에뮬레이터 캡처가 아니라, 실제 Compose 화면을
JVM 에서 그려 뽑은 것입니다(`ScreenshotTest`, Roborazzi). 화면 속 안내 문장은 엔진에 신호를 넣어 얻은 실제 출력입니다.

## 무엇을 하나

차만 아는 사실을, 차가 대신 말합니다.

| 순간 | 차가 아는 것 | 차가 하는 말(예) |
|---|---|---|
| 몇 분마다 "어디 가?", "언제 도착해?" | 내비의 목적지와 남은 시간 | "어머니, 절반쯤 왔어요. 큰딸 집에 가는 길이에요." |
| 차가 서면 "다 왔어?" 하며 내리려 함 | 주행 단인데 20초 넘게 정지 | "어머니, 잠깐 신호를 기다려요. 아직 가는 중이에요." |
| 운전자가 잠깐 내림 | P단 + 운전석 문 열림 + 운전석 비움 + 동승 좌석 점유 | "어머니, 따님이 잠깐 내렸어요. 금방 돌아와요." |
| 주행 중 문 손잡이를 당김 | 그 좌석 문 열림 | 운전자: 화면과 알림음 / 동승자: "문은 닫아 주세요." |
| 창문을 내림 | 그 좌석 창문 30% 이상 | "창문이 열렸어요. 손은 안에 두세요." |

대답의 경로는 셋입니다.

1. **먼저 말해 주기** — 구간(방금 출발 / 가는 중 / 절반 / 거의 다 옴 / 곧 도착 / 도착)이 바뀔 때와 일정 간격마다.
2. **운전자가 시키기** — 큰 버튼 `다시 말씀드리기`, 또는 Gleo 로 "어머니께 다시 말씀드려 줘".
3. **물으면 대답하기(실험 기능)** — 음성 인식으로 다섯 가지 물음을 알아듣고 대답. 꺼져 있어도 1·2로 앱이 섭니다.

말은 사람이 쓴 고정 대본(`app/src/main/assets/scripts_ko.json`)입니다. 실행 중에 생성형 AI 로 문장을 만들지 않습니다.
같은 물음에는 같은 사실을 표현만 바꿔 말하고, "아까 말씀드렸잖아요" 같은 말은 대본 검사기가 막습니다.

## 실행 방법

필요한 것: JDK 17, Android SDK(platform 34, build-tools 34). 인터넷(의존성과 Pleos SDK 를 Maven 에서 받습니다).

```bash
# local.properties 에 SDK 위치를 적는다:  sdk.dir=<Android SDK 경로>
./gradlew testDebugUnitTest      # 단위 테스트(엔진 + 앱)
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease        # app/build/outputs/apk/release/app-release.apk
```

설치와 실행(Pleos Connect 에뮬레이터 또는 기기):

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n app.myeotbeonideun/.ui.MainActivity
```

릴리스 서명 값은 저장소 밖의 `keystore.properties`(storeFile, storePassword, keyAlias, keyPassword)에서 읽습니다.
위치는 `-Pmyeotbeon.signing=<파일>`, 환경변수 `MYEOTBEON_SIGNING`, `local.properties` 의 `myeotbeon.signing=<파일>`,
프로젝트 루트의 `keystore.properties` 순으로 찾습니다. 없으면 서명 없는 release 가 만들어집니다.

## 테스트 환경과 필요한 설정

| 항목 | 값 |
|---|---|
| 대상 | Pleos Connect Emulator 2.1.0 (API 34, x86_64) / 실차(arm64-v8a) |
| 빌드 | Gradle 8.9, Android Gradle Plugin 8.5.2, Kotlin 2.0.20, JDK 17 |
| SDK | compileSdk·targetSdk 34, minSdk 28 |
| Pleos SDK | Vehicle 2.3.2, NaviHelper 3.0.5, TextToSpeech 3.3.1, SpeechToText 3.3.1, FusedLocation 1.1.3 |
| UI | Jetpack Compose (BOM 2024.09.03), Material 3 |

에뮬레이터 설정(Pleos 문서 기준)

- AVD: Automotive > Pleos Connect v2.1.0, **Cold boot**(빠른 부팅이면 차량 신호를 쓸 수 없음), RAM 4GB 이상.
- 가상 콘솔: `adb forward tcp:8088 tcp:8088` 뒤 브라우저에서 `http://127.0.0.1:8088/`.
- 음성 인식을 시험하려면 Extended Controls > Microphone 에서 호스트 마이크를 켭니다.
- 주행 규제만 켜고 끄기: `adb shell cmd hmg_car_service --service drivingstate driving_regulation on|off`.

시연 순서는 [`docs/demo.md`](docs/demo.md)에 있습니다.

## APK 위치

- 빌드 산출물: `app/build/outputs/apk/release/app-release.apk` (서명됨, x86_64 + arm64-v8a)
- 제출용 사본: `apk/` 폴더

## 차량 신호 대응 표

읽기(조회)만 합니다. 차량 제어(set), 경고등(telltale), LLM 은 쓰지 않습니다.
API 이름·권한·문서 주소 전체는 [`docs/signals.md`](docs/signals.md)에 있습니다.

| 기능 | 신호 | SDK·API |
|---|---|---|
| 앉은 자리 자동 선택, 운전자 이탈·복귀 | 좌석 점유(운전석 포함) | Vehicle `getSeat()` · `registerSeatOccupancy` |
| 주행 중 문 열림 알림 | 문 위치 | Vehicle `getDoor()` · `registerDoorPosition` |
| 창문 열림 안내 | 창문 위치 | Vehicle `getWindow()` · `registerWindowPosition` |
| 안전띠 권유(지원 차종만) | 안전띠 체결 | Vehicle `getSeat()` · `registerSeatBeltBuckledState` |
| 신호 대기·정체 판단 | 브레이크 페달, 변속단, 속도 | Vehicle `getBrake()` · `registerBrakePedalStatus`, AAOS `CarPropertyManager`(GEAR_SELECTION, PERF_VEHICLE_SPEED) |
| 주행 중 화면 잠금 | 주행 규제 | AAOS `CarUxRestrictionsManager` |
| 어디·얼마나, 도착 | 목적지 이름·남은 시간·도착·정체 요약 | NaviHelper `NaviHelperEventListener` |
| 내비 없이 자주 가는 곳 | 근접 알림(300m) | FusedLocation `addProximityAlert` |
| 말하기 | — | TextToSpeech(ON_DEVICE) → 실패하면 안드로이드 TextToSpeech, 오디오 포커스 |
| 알아듣기(실험) | — | SpeechToText(ON_DEVICE) |
| 음성 비서 | — | Gleo App Schema 함수 3개(`assets/schema.json`) |

모든 Pleos 호출은 `check*Capabilities` 와 `onFailed` 를 거칩니다. 차가 주지 않는 신호가 있으면 그 신호를 쓰는
기능만 꺼지고 나머지는 그대로 동작합니다(환경설정 > 차량 신호에서 무엇이 잡혔는지 보입니다).

## 체험 모드

Pleos 서비스가 없는 기기에서도 같은 엔진을 모의 신호로 돌려 볼 수 있습니다.

- 차량용 기기가 아니거나 차량 신호를 하나도 얻지 못하면 자동으로 체험 모드가 됩니다. 환경설정에서 직접 켤 수도 있습니다.
- 화면에 항상 `체험 모드` 표시가 붙습니다. 실제 차량 신호가 아님을 밝히기 위해서입니다.
- 환경설정 > 개발자 패널에서 기어·브레이크·좌석·문·창문·안전띠·내비 값을 손으로 바꾸거나,
  `시연 재생`으로 명세의 시연 순서(약 2분 30초)를 그대로 돌립니다.
- 넓은 창에서는 개발자 패널 옆에 동행 화면이 함께 보여, 신호를 바꾸면서 반응을 바로 확인할 수 있습니다.
- 신호 출처만 다르고(`DemoSignalSource` ↔ `PleosSignalSource`) 엔진·음성·화면·저장은 같은 코드입니다.

## 알려진 한계

정직하게 적습니다.

- **에뮬레이터 실행 검증을 하지 못했습니다.** 개발 PC 에 에뮬레이터 가속이 없어, 빌드·단위 테스트·JVM 화면 렌더까지만
  확인했습니다. Pleos API 는 문서의 시그니처와 컴파일로 맞췄습니다. 아래는 에뮬레이터에서 처음 확인해야 할 것입니다.
  - NaviHelper 이벤트(남은 시간·도착)가 에뮬레이터 내비에서 실제로 오는지. 오지 않으면 구간 안내는 시간 기준으로만 동작합니다.
  - 창문 위치 값의 방향(최솟값 = 닫힘으로 가정), 문 위치 값(최솟값 = 닫힘으로 가정).
  - 기어를 D 로 바꿨을 때 `CarUxRestrictions` 와 `GEAR_SELECTION` 이 일반 앱에 전달되는지.
  - Pleos TextToSpeech(ON_DEVICE)가 에뮬레이터에서 소리를 내는지. 내지 못하면 안드로이드 TTS 로 넘어갑니다.
- **좌석별 스피커**: `TextToSpeech.setAudioZoneId(zoneId)` 는 넣었지만 차종별 존 번호가 문서에 없어, 환경설정에서 번호를 고르게 했습니다. 기본은 차 전체 스피커입니다.
- **말 빠르기**: Pleos TTS 에는 빠르기를 정하는 API 가 없어, 문장을 하나씩 끊어 읽고 사이에 쉼을 두는 방식으로 천천히 전합니다.
- **안전띠**: 가상 콘솔에는 좌석별 안전띠 값이 없어, 안전띠 권유는 체험 모드(모의 입력)로만 시연할 수 있습니다.
- **신호 대기와 정체의 구별**은 추정입니다. "20초 넘게 정지"는 사실이지만 그 이유가 신호인지는 차도 모릅니다. 내비가 경로 정체를 알리면 "길이 막혀요"로 말합니다.
- **Gleo**: `schema.json` 과 수신·응답 코드는 문서대로 넣었지만, 스키마는 "검토·승인 후 활성화"라 승인 전 에뮬레이터에서 호출이 오는지는 확인하지 못했습니다.
- **인터넷 권한**: 앱 매니페스트에는 넣지 않았습니다. 다만 Pleos TextToSpeech·SpeechToText SDK(와 그 안의 universal-access)가 병합 매니페스트에 `android.permission.INTERNET` 을 넣습니다. 앱은 두 SDK 를 ON_DEVICE 모드로만 쓰고, 앱 코드에는 네트워크 호출이 없습니다.
- **APK 크기**: release 약 38MB. Pleos SDK 의 난독화 규칙을 실차·에뮬레이터에서 검증하지 못해 코드 축소(R8)를 켜지 않았습니다.
- 이 앱은 **안전장치가 아닙니다.** 문과 창문이 열리는 것을 막지 못하고 알릴 뿐입니다. 뒷문 어린이 보호 잠금과 창문 잠금을 함께 쓰도록 앱 안에서 안내합니다.
- 효과에 대해서는 "운전자가 직접 대답하지 않아도 된 횟수"까지만 말합니다. 불안이 줄어든다거나 치료 효과가 있다고 주장하지 않습니다.

## 개인정보

- 서버가 없습니다. 앱 코드는 네트워크로 아무것도 보내지 않습니다.
- 목소리를 녹음하지 않습니다. 음성 인식(실험 기능)의 인식 문장은 물음의 종류 다섯 가지 중 하나로 바꾼 뒤 곧바로 버리고, 저장하지도 로그에 남기지도 않습니다.
- 기기에 남는 것은 자주 가는 길(호칭·가는 곳 이름·목적·운전하는 사람·자리·간격), 이동 기록(횟수와 시간), 환경설정뿐입니다. `환경설정 > 모두 지우기`로 지웁니다.
- 클라우드 백업과 기기 간 이동에서 앱 데이터를 제외했습니다.
- 앱 이름과 화면에 병명이나 환자라는 말이 없습니다(단위 테스트로 검사).

자세한 내용은 [`docs/privacy.md`](docs/privacy.md).

## 테스트

```bash
./gradlew testDebugUnitTest                              # 전체
./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true   # 화면 그림을 docs/screens 에 다시 뽑기
```

- **엔진(순수 Kotlin, 105개)**: 구간 경계, 간격 계산, 쿨다운, 멈춤 이유 분류의 각 경우, 운전자 이탈·복귀, 조사 처리,
  대본 선택의 결정성, 전체 대본의 규칙 검사, 금지어 검출, 우선순위와 '잠시 조용히'.
- **앱(42개)**: 색 대비 4.5:1, 화면 글 검사, 저장소, Gleo 스키마·응답, 실제 Application 으로 출발~도착~기록 흐름,
  화면 그림 17장.

## 저장소 구조

```
engine/   순수 Kotlin. 여정 상태 기계, 멈춤 이유 분류, 대본 엔진, 대본 검사기. 안드로이드 의존 없음
app/      안드로이드 앱
  signal/   신호 인터페이스 + Pleos 구현 + 체험 모드 구현
  speech/   Pleos TTS → 안드로이드 TTS, 오디오 포커스, 음성 인식(실험)
  data/     자주 가는 길, 기록, 환경설정(기기 안)
  service/  동행 진행(TripController), 포그라운드 서비스
  gleo/     Gleo 함수 수신과 응답
  ui/       Compose 화면
docs/     architecture.md, signals.md, demo.md, privacy.md, screens/
```

구조와 판단 규칙은 [`docs/architecture.md`](docs/architecture.md)에 있습니다.

Pleos SDK 바이너리는 저장소에 넣지 않았습니다(라이선스). 빌드할 때 Pleos Maven 저장소에서 받습니다.
