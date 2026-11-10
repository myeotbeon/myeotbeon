# 차량 신호 → 기능

앱이 읽는 신호와 그 신호로 하는 일, 부르는 API, 필요한 권한, 근거 문서를 한곳에 모았습니다.
문서 주소의 앞부분 `https://document.pleos.ai/docs/pleos-only/` 는 `…/` 로 줄였습니다.

원칙

- **읽기만 합니다.** 차량 제어(`set*`, `CONTROL_CAR_*`), 경고등(`registerTelltales`, `PRIVATE_CAR_SAFETY`), LLM 은 쓰지 않습니다.
- 모든 Pleos 호출은 `check*Capabilities` → `getCurrent*`(현재 값) → `register*`(변화) 순서이고, 각각 `onFailed` 를 처리합니다.
- 지원하지 않거나 실패한 신호는 값이 비어 있을 뿐입니다. 엔진은 그 신호를 쓰는 기능만 끕니다.
- 구현: `app/src/main/java/app/myeotbeonideun/signal/PleosSignalSource.kt`, `CarApiBridge.kt`

## 1. Pleos Vehicle SDK (`ai.pleos.playground:Vehicle:2.3.2`)

| 신호 | 기능 | API | 권한(보호 수준) | 문서 |
|---|---|---|---|---|
| 좌석 점유(운전석·앞 옆자리·뒷좌석 좌우) | 앉은 자리 자동 선택, 운전자 이탈·복귀 판단, 동승자가 내렸는지 | `Vehicle.getSeat()` · `checkSeatOccupancyCapabilities` · `getCurrentSeatOccupancy(area, onComplete: (Boolean?) -> Unit, onFailed)` · `registerSeatOccupancy(area, SeatOccupancyListener)` · `unregisterSeatOccupancy` | `pleos.car.permission.CAR_SEATS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-occupancy` |
| 안전띠 체결 | 풀리면 부드러운 권유 2회 뒤 운전자에게 알림(지원 차종만) | `checkSeatBeltBuckledCapabilities` · `getCurrentSeatBeltBuckledState` · `registerSeatBeltBuckledState(area, SeatBeltBuckledStateListener)` | `pleos.car.permission.CAR_SEATS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-belt-buckled-state` |
| 문 위치 | 주행 중 동승 좌석 문 열림 → 운전자에게 즉시, 동승자에게 "문은 닫아 주세요". 운전석 문은 운전자 이탈 판단 | `Vehicle.getDoor()` · `checkDoorPositionCapabilities` · `getCurrentDoorPosition(area, onComplete: (Int?) -> Unit, onFailed)` · `registerDoorPosition(area, DoorPositionListener)` | `pleos.car.permission.CAR_DOORS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-door-position` |
| 창문 위치 | 동승 좌석 창문이 기준(기본 30%) 이상 열림 → "손은 안에 두세요", 운전자 화면 한 줄 | `Vehicle.getWindow()` · `checkWindowPositionCapabilities` · `getCurrentWindowPosition(area, onComplete: (Int?) -> Unit, onFailed)` · `registerWindowPosition(area, WindowPositionListener)` | `pleos.car.permission.CAR_WINDOWS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-position` |
| 브레이크 페달 | 주행 단에서 20초 이상 정지 → 신호 대기·정체 안내 | `Vehicle.getBrake()` · `checkBrakePedalStatusCapabilities` · `getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed)` · `registerBrakePedalStatus(BrakePedalStatusListener)` | `pleos.car.permission.CAR_DRIVE` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/register-brake-pedal-status` |

- 좌석 대응: 운전석 `ROW_1_LEFT`, 앞 옆자리 `ROW_1_RIGHT`, 뒷좌석 `ROW_2_LEFT`·`ROW_2_RIGHT` (운전석이 왼쪽인 차 기준).
- 창문·문 값의 범위는 `check*Capabilities` 가 주는 `AreaBounds(minValue, maxValue)` 로 받습니다. 최솟값을 '닫힘'으로 보고
  창문은 0~100% 로 바꿉니다(AAOS `WINDOW_POS`·`DOOR_POS` 와 같은 뜻으로 가정 — 에뮬레이터 실측 필요).
- 사용법 가이드: `…/connect-sdk-pleos/Vehicle/check-capability`, `…/get-property`, `…/callback`, `…/vehicle-permission`, `…/vehicle-error-code`

## 2. AAOS Car API (`android.car`)

Pleos 문서는 속도·변속단을 "AAOS Car API 로 얻는 표준 데이터"로 분류하고, 주행/정차 판정은 `CarUxRestrictions` 로 하도록 정합니다.
Pleos Vehicle SDK 2.3.2 에는 속도·변속단 API 가 없습니다.

| 신호 | 기능 | API | 권한 | 문서 |
|---|---|---|---|---|
| 주행 규제 | 주행 중에는 설정·기록·환경설정 화면을 잠그고 동행 화면만 연다 | `CarUxRestrictionsManager.registerListener` · `getCurrentCarUxRestrictions().isRequiresDistractionOptimization` | 없음 | `…/vehicle-app-developer-guide/driving-restriction-development-guide`, `…/vehicle-app-planning-guide/driving-restiction-policy` |
| 변속단 | P단(정차)·주행 단 구분: 출발, 경유지 정차, 운전자 이탈, 도착 | `CarPropertyManager.registerCallback(GEAR_SELECTION)` | `android.car.permission.CAR_POWERTRAIN` | `…/vehicle-app-planning-guide/pleos-connect-sdk-api`, `…/vehicle-app-planning-guide/api-compatibility-availability` |
| 속도 | 브레이크를 떼고 서 있을 때(오토홀드)도 정지를 안다 | `CarPropertyManager.registerCallback(PERF_VEHICLE_SPEED)` | `android.car.permission.CAR_SPEED` (사용자 허용 필요 — 환경설정에서 요청) | `…/vehicle-app-developer-guide/vehicle-app-faq` |

- 변속단을 읽을 수 없으면 주행 규제 상태로 주행/정차를 대신 판단합니다.
- 속도는 한 번이라도 움직임(5km/h 이상)이 보인 뒤에만 정지 판단에 씁니다. 에뮬레이터는 속도를 바꿀 수 없어 0 에 머무르므로,
  그 전에는 브레이크 페달로만 정지를 봅니다.
- 주행 중에도 쓰는 화면이 있어 액티비티에 `distractionOptimized` 메타데이터를 선언했습니다(AAOS 표준 방식.
  Pleos 문서에는 선언 방법이 적혀 있지 않습니다).

## 3. NaviHelper SDK (`ai.pleos.playground:NaviHelper:3.0.5`)

권한: `pleos.car.permission.NAVI_ROUTE`. 경로를 요청하거나 바꾸는 API(`NAVI_ROUTE_SEARCH`)는 쓰지 않습니다.

| 콜백(`NaviHelperEventListener`) | 쓰는 값 | 기능 |
|---|---|---|
| `onRouteStarted(RouteStartInfo)` | `name`, `duration` | 목적지 이름을 '가는 곳'의 기본값으로, 전체 예상 시간을 구간 계산에 |
| `onDestinationInfo(DestinationInfo)` | `name`, `remainTime`(초), `remainDistance`(m) | 남은 시간으로 구간(가는 중/절반/거의 다 옴/곧 도착) 결정 |
| `onDrivingInfo(DrivingInfo)` | `destination.duration` | 남은 시간 갱신 |
| `onRouteStateInfo(RouteStateInfo)` | `isRouting`, `trafficStatus.summary.totalCongestionTime` | 경로에 1분 넘는 정체가 있으면, 정지했을 때 "길이 막혀요"로 말함 |
| `onDestinationArrived(DestinationArrivedInfo)` | — | 도착 안내 |
| `onWayPointArrived(WaypointArrivedInfo)` | — | (경유지 도착 — 정차 판단은 P단으로) |
| `onRouteCancelled()`, `onNavigationStatus(NavigationStatus)` | — | 안내가 끝나면 남은 시간 정보를 비움 |

- 초기화: `NaviHelper(context).initialize(onConnected, onFailed)` → `addListener`.
- `getDestinationInfo()`·`getRouteStateInfo()` 는 값을 돌려주지 않고 콜백으로 줍니다. 동행 중에만 30초마다 다시 묻습니다.
- 문서: `…/connect-sdk-pleos/NaviHelper/event-listeners`, `…/NaviHelper/destinations`, `…/NaviHelper/initialization-and-release`, `…/NaviHelper/declaring-permissions`,
  `…/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper.listener/navi-helper-event-listener/entry`

## 4. FusedLocation SDK (`ai.pleos.playground:FusedLocation:1.1.3`)

권한: `pleos.car.permission.FUSED_LOCATION` (dangerous). 사용자가 '도착 지점 기억하기'를 켠 길에서만 묻습니다.

| API | 기능 |
|---|---|
| `isSupported()` → `initialize()` | 지원하는 차에서만 사용 |
| `getLastKnownLocation(FusedLocationListener)` | 도착했을 때의 위치를 그 길의 도착 지점으로 기억(기기 안) |
| `addProximityAlert(ProximityAlertRequest(lat, lon, 300f, setOf(ENTER)), ProximityAlertListener)` | 다음부터 내비 없이도 300m 앞에서 '곧 도착' |
| `removeProximityAlert(alertId)` | 동행이 끝나면 해제 |

- 문서: `…/connect-sdk-pleos/FusedLocation/location-data-access`, `…/FusedLocation/declaring-permissions`,
  `…/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/add-proximity-alert`

## 5. TextToSpeech SDK (`ai.pleos.playground:TextToSpeech:3.3.1`)

권한: `pleos.car.permission.TTS_SERVICE`.

| API | 기능 |
|---|---|
| `TextToSpeech(context, Mode.ON_DEVICE)` | 기기 안 엔진만 사용(네트워크·앱 등록 불필요) |
| `initialize(OnInitializeListener)` | 실패하면 안드로이드 기본 TextToSpeech 로 대체 |
| `setLocale(Locale.KOREA)`, `setAudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)` | |
| `setAudioZoneId(zoneId)` | 동승 좌석 쪽 오디오 존(환경설정에서 번호 선택. 차종별 번호는 문서에 없음) |
| `speak(text)`, `stop()`, `addEventListener(EventListener)` | 문장 단위로 읽고 `onDone` 을 기다림 |

오디오 정책

- 말하기 전에 `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` 로 포커스를 요청합니다(음악은 끊지 않고 낮춤).
- `AUDIOFOCUS_LOSS`·`LOSS_TRANSIENT` 를 받으면 즉시 멈추고 포커스를 돌려줍니다(Gleo 호출, 통화).
- 포커스를 얻지 못하면 1.5초 간격으로 세 번 더 묻고, 끝내 얻지 못하면 그 말은 소리 내지 않고 화면에만 남깁니다.
- 선행 묵음 약 200ms(안드로이드 TTS 는 오디오 앞 무음, Pleos TTS 는 재생 지연), 문장 사이 쉼 350ms.
- 문서: `…/connect-sdk-pleos/TextToSpeech/intro`, `…/TextToSpeech/start-and-stop`, `…/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development`,
  `…/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide`

## 6. SpeechToText SDK (`ai.pleos.playground:SpeechToText:3.3.1`) — 실험 기능

권한: `pleos.car.permission.STT_SERVICE`. 환경설정에서 켰을 때만, 동행 중에만, 차가 말하지 않을 때만 듣습니다.

| API | 기능 |
|---|---|
| `SpeechToText(context, Mode.ON_DEVICE)` · `initialize` · `addListener(ResultListener)` · `request()` · `stop()` | "어디 가", "언제 도착", "다 왔어", "왜 안 가", "집에 가자" 다섯 가지를 알아듣고 대답 |

- `onUpdated(stt, completed)` 의 문장은 물음의 종류로 바꾼 뒤 버립니다. 저장하지 않습니다.
- 초기화나 인식이 실패하면 조용히 물러나고, 앱의 나머지는 그대로 동작합니다.
- 문서: `…/connect-sdk-pleos/SpeechToText/start-and-stop`, `…/SpeechToText/result-listeners`, `…/SpeechToText/declaring-permissions`

## 7. Gleo Interface

| 함수(`assets/schema.json`) | component | 동작 |
|---|---|---|
| `REPEAT_TRIP_ANSWER` | service(브로드캐스트) | 다시 말씀드리기. 응답 `mode: "silent"`(앱이 직접 말함) |
| `PAUSE_VOICE_GUIDE` (`minutes`: number, 선택) | service(브로드캐스트) | 잠시 조용히(기본 10분) |
| `START_COMPANION_TRIP` (`destinationName`: string, 선택) | activity | 저장된 '자주 가는 길'로 동행 시작. 없으면 `status: "asking"` |

- 수신: Intent action = 함수 이름, 파라미터 = `ACTION_PARAM` 문자열(JSON).
- 응답: `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트(extra `packageName`, `response`).
- 문서: `…/app-integration-guide/guide/app-capability`, `…/guide/deliver-schema`, `…/guide/examples`, `…/guide/action_callback`

## 8. 병합 매니페스트에 SDK 가 넣는 권한

앱이 선언하지 않았지만 SDK 가 넣는 것(빌드의 `merged_manifests` 에서 확인):

| 권한 | 넣는 곳 |
|---|---|
| `android.permission.INTERNET` | TextToSpeech 3.3.1, SpeechToText 3.3.1, universal-access 0.6.0 |
| `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` | Vehicle 2.3.2 |
| `ai.pleos.playground.service.location.fused.FUSED_LOCATION_BINDING` | FusedLocation 1.1.3 |

## 9. 가상 콘솔로 바꿀 수 있는 값과 없는 값

| 값 | 가상 콘솔 | 비고 |
|---|---|---|
| 기어(P/R/N/D), 브레이크 페달 | 있음 | |
| 좌석 점유(운전석·동승석·뒷좌석 좌우) | 있음 | |
| 문 위치, 창문 위치(0~100%) | 있음 | |
| 좌석별 안전띠 | **없음** | 체험 모드로만 시연 |
| 차량 속도 | **없음** | 그래서 브레이크 페달로 정지를 판단 |
| 내비 남은 시간·위치 | **없음** | 에뮬레이터 내비의 모의 주행 또는 체험 모드 |
