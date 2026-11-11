# 차량 신호 → 기능

앱이 읽는 신호와 그 신호로 하는 일, 부르는 API, 필요한 권한, 근거 문서를 한곳에 모았습니다.
문서 주소의 앞부분 `https://document.pleos.ai/docs/pleos-only/` 는 `…/` 로 줄였습니다.
'실측'은 2026-11-11 Pleos Connect Emulator v2.1.0(Android 14)에서 직접 본 값이고, 그 밖은 문서와 컴파일로 맞춘 것입니다.

원칙

- **읽기만 합니다.** 차량 제어(`set*`, `CONTROL_CAR_*`), 경고등(`registerTelltales`, `PRIVATE_CAR_SAFETY`), LLM 은 쓰지 않습니다.
- **Vehicle SDK 5종**(좌석 점유·안전띠·문·창문·브레이크)은 `check*Capabilities` → `getCurrent*`(현재 값) → `register*`(변화)
  순서이고, 확인·조회·리스너마다 `onFailed` 를 처리합니다. 등록은 확인 결과의 **지원 영역 목록에 든 자리만** 합니다
  (목록이 있는데 앱이 쓰는 자리와 하나도 맞지 않으면, 영역 표기가 예상과 다른 것으로 보고 전처럼 자리마다 시도하고 연결 기록에 남깁니다).
- 다른 SDK 는 확인 방법이 다릅니다.
  - NaviHelper: `initialize(onConnected, onFailed)` 만 실패를 알려 줍니다. `getRouteStateInfo()`·`getDestinationInfo()` 는 값도 실패도
    돌려주지 않고 결과가 리스너로 옵니다(부를 때의 예외만 잡습니다).
  - FusedLocation: `isSupported()` → `initialize()`. 초기화에는 실패 콜백이 없어 예외만 잡습니다.
  - TextToSpeech·SpeechToText: capability 확인 API 가 없습니다. `initialize` 의 성공·실패 콜백으로 판단합니다.
  - AAOS Car API: `Car.createCar` 가 null 이면 연결 실패로 봅니다.
- **다시 시도**: Vehicle 확인 실패(`onFailed`·예외)와 Vehicle 초기화, Car 서비스 연결, NaviHelper 연결, FusedLocation 초기화는
  2·4·8초 간격으로 세 번까지 다시 합니다(`RetryPolicy`). 끝내 실패한 신호는 값이 비어 있을 뿐이고, 엔진은 그 신호를 쓰는 기능만 끕니다.
- **구독 수명**: 차량용 기기에서 앱 화면이 보이거나 동행 중일 때만 구독합니다. 화면이 뒤로 가고 동행이 없으면 1초 뒤 리스너를 풀고,
  SDK 객체는 기다리던 비동기 콜백이 모두 돌아온 뒤에 놓습니다(Pleos 문서: 콜백이 끝나기 전에 `release()` 금지. 10초 안에 오지 않으면
  그대로 놓고 연결 기록에 남김). 다시 붙일 때마다 새 세대를 만들어 옛 세대의 콜백은 무시합니다(`SignalSession`).
- 붙인 뒤 4초 안에 신호를 하나도 얻지 못하면 '차량 신호 없음'입니다. 차량용 기기에서는 체험 모드로 넘어가지 않습니다.
  그 뒤 다시 시도가 성공하면 연결됨으로 바뀝니다.
- 구현: `app/src/main/java/app/myeotbeonideun/signal/PleosSignalSource.kt`, `CarApiBridge.kt`, `SeatReading.kt`, `SignalSession.kt`, `DrivingLock.kt`

## 0. 에뮬레이터 실측 (2026-11-11)

| 항목 | 실측 | 앱이 하는 일 |
|---|---|---|
| 연결된 신호 | 좌석 점유·문·창문·안전띠·브레이크·변속단·주행 규제·내비·위치 '사용', 속도 '없음' → 속도 권한을 허용하면 10종 모두 '사용' | 환경설정 > 차량 신호, 개발자 패널 > 연결 기록에 보임 |
| 지원 영역 수(연결 기록) | 좌석 점유 5곳, 안전띠 5곳, 문 6곳, 창문 4곳 | 앱이 쓰는 자리만 등록: 좌석 점유·문은 네 자리, 안전띠·창문은 동승 세 자리 |
| 좌석 점유 | 원시 값은 AAOS 표준대로 빈자리 1·점유 2(logcat `SEAT_OCCUPANCY … value = 2`). 그런데 리스너·`getCurrentSeatOccupancy` 가 넘기는 Boolean 은 가상 콘솔과 **반대**(콘솔 운전석 Occupied → false) | '좌석 값 읽기'로 해석한다(아래 1절). 이 보정은 그 뒤 빌드에 넣었고 에뮬레이터 재확인 전이다 |
| 창문 위치 | 가상 콘솔 40% → 원시 값 **4**(0~10 눈금). logcat `WINDOW_POS` areaId 256·1024(뒷좌석 왼쪽·오른쪽) | 확인 결과의 `AreaBounds(min, max)` 로 0~100% 로 바꾼다(4/10 → 40%) → 기준(30%)을 넘어 창문 안내가 났다 |
| 문 위치 | 가상 콘솔 Open → 원시 값 **1**(`DOOR_POS` areaId 64 = 뒷좌석 오른쪽). 닫힘은 최솟값 | 최솟값보다 크면 열림 → 주행 중 문 열림 알림이 났다. 차 자체의 '도어 열림' 경고도 계기판에 따로 뜬다 |
| 브레이크 | `BRAKE_PEDAL_STATUS` 등록·수신 | 속도를 쓸 수 없을 때의 정지 판단 |
| 변속단·주행 규제 | Gear D → `CarUxRestrictions` 수신(activeRestrictions=80). 메뉴의 출발·기록·설정이 흐려지고(잠김) 동행·카드는 그대로 | 출발 판단, 정차 전용 화면 잠금 |
| 속도 | `CAR_SPEED` 허용 창이 "몇 번이든에서 이 기기의 위치 정보에 액세스하도록 허용하시겠습니까?"로 뜬다(위치 권한 묶음). 가상 콘솔로 바꿀 수 있다 | 정지 판단(아래 2절) |
| 내비 남은 시간 | **초.** 내장 내비에서 9분·1.9km 경로 → 개발자 패널 "남은 540초 / 전체 540초"(내비 내부 기록도 remainTime=501) | 구간 계산 |
| 내비 남은 거리 | 단위를 확인하지 못했다(미터로 가정) | 남은 시간 0 표본 거르기, 목적지 근처 확인(300m·150m), 경유지 판단(1km)의 보조 |
| 내비 도착 이벤트 | **만들 수 없었다.** 이 에뮬레이터는 내비 위치가 움직이지 않는다(내보내는 NMEA 문장의 체크섬이 늘 틀려 내장 내비가 버림). 남은 시간은 재계산으로만 조금 준다(501→483초) | 내비 없이 간 길처럼 운전자가 '도착했어요'로 알린다 |
| 위치 | FusedLocation 이 `adb emu geo fix` 위치를 받았다. 환경설정의 '위치 사용'은 SDK 지원 여부이고, `FUSED_LOCATION` 권한 허용 전에도 '사용'으로 보였다 | 근접 알림(`addProximityAlert`)은 확인하지 못했다 |
| 말하기 | Pleos TextToSpeech(ON_DEVICE)가 기기 안 엔진(GAAS OnDevice, 화자 seoyoon, ko_KR, 22,050Hz)으로 소개말을 합성·재생 | 음성 엔진 표시 "Pleos TextToSpeech (ON_DEVICE)" |
| 알아듣기 | '물으면 대답하기'를 켜면 앱 요청으로 기기 안 인식이 마이크 녹음을 시작(16kHz)하고 인식을 돌린다 | 알아들은 문장과 대답은 확인하지 못했다(음성을 넣는 도중 에뮬레이터가 멈춤) |

## 1. Pleos Vehicle SDK (`ai.pleos.playground:Vehicle:2.3.2`)

| 신호 | 기능 | API | 권한(보호 수준) | 문서 |
|---|---|---|---|---|
| 좌석 점유(운전석·앞 옆자리·뒷좌석 좌우) | 앉은 자리 자동 선택, 운전자 이탈·복귀 판단, 동승자가 내렸는지 | `Vehicle.getSeat()` · `checkSeatOccupancyCapabilities` · `getCurrentSeatOccupancy(area, onComplete: (Boolean?) -> Unit, onFailed)` · `registerSeatOccupancy(area, SeatOccupancyListener)` · `unregisterSeatOccupancy` | `pleos.car.permission.CAR_SEATS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-occupancy` |
| 안전띠 체결 | 풀리면 부드러운 권유 2회 뒤 운전자에게 알림(지원 차종만) | `checkSeatBeltBuckledCapabilities` · `getCurrentSeatBeltBuckledState` · `registerSeatBeltBuckledState(area, SeatBeltBuckledStateListener)` | `pleos.car.permission.CAR_SEATS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/seat/register-seat-belt-buckled-state` |
| 문 위치 | 주행 중 동승 좌석 문 열림 → 운전자에게 즉시, 동승자에게 "문은 닫아 주세요". 운전석 문은 운전자 이탈 판단. 운전자가 내린 동안 동승 좌석 문 열림 → 동승자를 안심시키고 돌아온 운전자에게 알림 | `Vehicle.getDoor()` · `checkDoorPositionCapabilities` · `getCurrentDoorPosition(area, onComplete: (Int?) -> Unit, onFailed)` · `registerDoorPosition(area, DoorPositionListener)` | `pleos.car.permission.CAR_DOORS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/door/register-door-position` |
| 창문 위치 | 동승 좌석 창문이 기준(기본 30%) 이상 열림 → "손은 안에 두세요", 운전자 화면 한 줄 | `Vehicle.getWindow()` · `checkWindowPositionCapabilities` · `getCurrentWindowPosition(area, onComplete: (Int?) -> Unit, onFailed)` · `registerWindowPosition(area, WindowPositionListener)` | `pleos.car.permission.CAR_WINDOWS` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/window/register-window-position` |
| 브레이크 페달 | 속도를 쓸 수 없을 때, 주행 단에서 35초 넘게 밟고 있으면 신호 대기·정체 안내(속도로 볼 때는 20초) | `Vehicle.getBrake()` · `checkBrakePedalStatusCapabilities` · `getCurrentBrakePedalStatus(onComplete: (Boolean?) -> Unit, onFailed)` · `registerBrakePedalStatus(BrakePedalStatusListener)` | `pleos.car.permission.CAR_DRIVE` (normal) | `…/connect-sdk-pleos/Vehicle/vehicle/ai.pleos.playground.vehicle.api/brake/register-brake-pedal-status` |

- 좌석 대응: 운전석 `ROW_1_LEFT`, 앞 옆자리 `ROW_1_RIGHT`, 뒷좌석 `ROW_2_LEFT`·`ROW_2_RIGHT` (운전석이 왼쪽인 차 기준).
- **좌석 점유 값 읽기(극성 보정).** 에뮬레이터에서 Boolean 이 반대로 왔고 실차의 극성은 모르므로, 앱은 SDK 값을 그대로 쓰지 않고
  해석합니다(`SeatValueReader`). 환경설정 > 차량 신호의 '좌석 값 읽기'(정차 중에만 바꿈)
  - **자동**(기본): 운전자가 화면을 조작하는 때에는 운전석에 사람이 있다고 봅니다. 출발 전 설정 화면(P단, 앱이 보임, 동행 없음)과
    동행을 시작하는 순간에 운전석 원시 값이 false 면 '반대로', true 면 '그대로'로 정하고, 동행 중에는 바꾸지 않습니다.
    동행 시작 때 운전석 값이 아직 없으면(화면 밖 Gleo 로 시작해 신호를 막 붙인 때) 10초 안에 처음 오는 값으로 정합니다. 운전석 값이 없으면 '그대로'.
  - **그대로 / 반대로**: 직접 고른 대로 바로 씁니다. 자동의 전제가 틀리는 경우(운전자는 밖에 있고 동승자가 화면을 눌러 시작)를 위한 것입니다.
  - 해석한 값은 앉은 자리 자동 선택, 운전자 이탈·복귀, 동승자가 자리를 떴는지 판단에 씁니다. 개발자 패널(차량 모드)은 지금 해석
    ("좌석 값: 반대로(운전석 기준 자동)", 동행 중이면 "· 동행 중 고정")과 SDK 원시 값을 함께 보입니다.
- **안전띠**는 극성을 확인하지 못해(가상 콘솔에 좌석별 안전띠가 없음) 해석하지 않고 그대로 씁니다. 개발자 패널에 원시 값이 보입니다.
- 창문·문 값의 범위는 `check*Capabilities` 가 주는 `AreaBounds(minValue, maxValue)` 로 받습니다. 창문은 (값 − 최솟값) / (최댓값 − 최솟값)으로
  0~100% 로 바꾸고, 문은 최솟값보다 크면 열린 것으로 봅니다(최솟값 = 닫힘, AAOS `WINDOW_POS`·`DOOR_POS` 와 같은 뜻 — 위 0절에서 확인).
- 좌석 점유 값은 엔진이 2.5초 그대로일 때만 받습니다(흔들림 완충, `docs/architecture.md`).
- 사용법 가이드: `…/connect-sdk-pleos/Vehicle/check-capability`, `…/get-property`, `…/callback`, `…/vehicle-permission`, `…/vehicle-error-code`

## 2. AAOS Car API (`android.car`)

Pleos 문서는 속도·변속단을 "AAOS Car API 로 얻는 표준 데이터"로 분류하고, 주행/정차 판정은 `CarUxRestrictions` 로 하도록 정합니다.
Pleos Vehicle SDK 2.3.2 에는 속도·변속단 API 가 없습니다.

| 신호 | 기능 | API | 권한 | 문서 |
|---|---|---|---|---|
| 주행 규제 | 주행 중에는 출발 전 설정·기록·환경설정·개발자 패널을 잠그고 동행 화면과 동승자 카드만 연다 | `CarUxRestrictionsManager.registerListener` · `getCurrentCarUxRestrictions().isRequiresDistractionOptimization` | 없음 | `…/vehicle-app-developer-guide/driving-restriction-development-guide`, `…/vehicle-app-planning-guide/driving-restiction-policy` |
| 변속단 | P단(정차)·주행 단 구분: 출발, 정차, 운전자 이탈, 도착, '도착했어요'를 받을 때 | `CarPropertyManager.registerCallback(GEAR_SELECTION)`, 붙일 때 `getIntProperty(GEAR_SELECTION)` 로 지금 값도 한 번 | `android.car.permission.CAR_POWERTRAIN` | `…/vehicle-app-planning-guide/pleos-connect-sdk-api`, `…/vehicle-app-planning-guide/api-compatibility-availability` |
| 속도 | 브레이크를 떼고 서 있을 때(오토홀드)도 정지를 안다 | `CarPropertyManager.registerCallback(PERF_VEHICLE_SPEED)` | `android.car.permission.CAR_SPEED` (사용자 허용 필요 — 첫 실행 안내(정차 중)에서 한 번 권하고, 환경설정에서도 요청. 허용 창은 '위치 정보'로 뜸) | `…/vehicle-app-developer-guide/vehicle-app-faq` |

- 화면 잠금은 주행 규제 값으로, 그 값이 없으면 변속단이 P 가 아닌지로 정합니다(둘 다 모르면 잠그지 않음). 체험 모드여도
  실차가 주행 중이면 잠급니다(지금 신호 출처의 규제 OR 실차의 규제, `DrivingLock`).
- 속도는 한 번이라도 움직임(5km/h 이상)이 보인 뒤에만 정지 판단에 씁니다(1km/h 미만 = 정지). 속도 권한이 없거나 그 전에는
  '주행 단에서 브레이크를 밟고 있음'을 정지로 봅니다. 그래서 속도 권한이 없으면 서행 중 브레이크를 오래 밟아도 정지로 보고,
  오토홀드로 발을 떼고 서 있으면 정지를 모릅니다. 가상 콘솔에서는 속도를 바꿀 수 있습니다(`docs/demo.md`).
- 변속단과 주행 규제를 둘 다 모르는 차에서는 시간만으로 출발했다고 보지 않고, 움직였다는 증거(속도 5km/h 이상)가 있을 때만 출발로 봅니다.
- 주행 중에도 쓰는 화면이 있어 액티비티에 `distractionOptimized` 메타데이터를 선언했습니다(AAOS 표준 방식.
  Pleos 문서에는 선언 방법이 적혀 있지 않습니다).

## 3. NaviHelper SDK (`ai.pleos.playground:NaviHelper:3.0.5`)

권한: `pleos.car.permission.NAVI_ROUTE`. 경로를 요청하거나 바꾸는 API(`NAVI_ROUTE_SEARCH`)는 쓰지 않습니다.

| 콜백(`NaviHelperEventListener`) | 쓰는 값 | 기능 |
|---|---|---|
| `onRouteStarted(RouteStartInfo)` | `name`, `duration` | 목적지 이름을 출발 전 설정 '가는 곳'의 기본값으로(비어 있고 고치지 않았을 때, 16자까지), 전체 예상 시간을 구간 계산에 |
| `onDestinationInfo(DestinationInfo)` | `name`, `remainTime`(**초** — 실측), `remainDistance`(미터로 가정 — 미확인) | 남은 시간으로 구간(가는 중/절반/거의 다 옴/곧 도착) 결정 |
| `onDrivingInfo(DrivingInfo)` | `destination.duration` | 남은 시간 갱신 |
| `onRouteStateInfo(RouteStateInfo)` | `isRouting`, `trafficStatus.summary.totalCongestionTime` | 경로에 1분 넘는 정체가 있으면, 정지했을 때 "길이 막혀요"로 말함 |
| `onDestinationArrived(DestinationArrivedInfo)` | — | 도착 안내(에뮬레이터에서는 만들 수 없어 확인하지 못함) |
| `onWayPointArrived(WaypointArrivedInfo)` | — | (경유지 도착 — 정차 판단은 P단으로) |
| `onRouteCancelled()`, `onNavigationStatus(NavigationStatus)` | — | 안내가 끝나면 남은 시간 정보를 비움 |

- 초기화: `NaviHelper(context).initialize(onConnected, onFailed)` → `addListener`. 연결에 실패하면 그 객체를 놓고 새 객체로 다시 시도합니다.
- `getDestinationInfo()`·`getRouteStateInfo()` 는 값을 돌려주지 않고 콜백으로 줍니다. 그래서 내비가 연결된 동안, 동행 중에만 30초마다 다시 묻습니다
  (차량 신호 가운데 주기적으로 묻는 것은 이것 하나입니다).
- 엔진은 표본 하나를 그대로 믿지 않습니다: 남은 거리 0 은 도착 이벤트 없이는 받지 않고, 남은 시간 0 은 남은 거리가 1~150m 이거나 도착
  이벤트와 함께일 때만 받습니다. 구간과 목적지 근처는 같은 판단이 표본 2개 연달아 오거나 10초 이어질 때만 믿고, 남은 시간이 작아도 남은
  거리가 300m 넘게 남았으면 근처로 보지 않습니다(`docs/architecture.md` 구간 절).
- 문서: `…/connect-sdk-pleos/NaviHelper/event-listeners`, `…/NaviHelper/destinations`, `…/NaviHelper/initialization-and-release`, `…/NaviHelper/declaring-permissions`,
  `…/connect-sdk-pleos/NaviHelper/navi-helper/ai.pleos.playground.navi.helper.listener/navi-helper-event-listener/entry`

## 4. FusedLocation SDK (`ai.pleos.playground:FusedLocation:1.1.3`)

권한: `pleos.car.permission.FUSED_LOCATION` (dangerous). 사용자가 '도착 지점 기억하기'를 켤 때만 묻습니다.

| API | 기능 |
|---|---|
| `isSupported()` → `initialize()` | 지원하는 차에서만 사용 |
| `getLastKnownLocation(FusedLocationListener)` | 도착했을 때의 위치를 그 길의 도착 지점으로 기억(기기 안). 운전자가 '도착했어요'로 알린 도착은 그때 위치로 새로 저장하고, 차가 스스로 본 도착은 저장된 지점이 없을 때만. 도착을 거두면(다시 출발) 그 도착에서 저장한 지점을 되돌림 |
| `addProximityAlert(ProximityAlertRequest(lat, lon, 300f, setOf(ENTER)), ProximityAlertListener)` | 다음 동행부터 내비 없이도 300m 앞에서 '곧 도착'(그 상태로 P단이면 도착) |
| `removeProximityAlert(alertId)` | 동행이 끝나면 해제 |

- 에뮬레이터: FusedLocation 이 `adb emu geo fix` 위치를 받는 것은 확인했고, 근접 알림이 실제로 오는지는 확인하지 못했습니다.
- 문서: `…/connect-sdk-pleos/FusedLocation/location-data-access`, `…/FusedLocation/declaring-permissions`,
  `…/connect-sdk-pleos/FusedLocation/fused-location/ai.pleos.playground.location.fused/fused-location/add-proximity-alert`

## 5. TextToSpeech SDK (`ai.pleos.playground:TextToSpeech:3.3.1`)

권한: `pleos.car.permission.TTS_SERVICE`(에뮬레이터에서는 설치 때 허용됨).

| API | 기능 |
|---|---|
| `TextToSpeech(context, Mode.ON_DEVICE)` | 기기 안 엔진만 사용(네트워크·앱 등록 불필요). 에뮬레이터에서 소리를 냄(위 0절) |
| `initialize(OnInitializeListener)` | 실패하면 안드로이드 기본 TextToSpeech 로 대체(그 엔진이 기기 안에서만 합성하는지는 앱이 정하지 못함) |
| `setLocale(Locale.KOREA)`, `setAudioAttributes(USAGE_ASSISTANT, CONTENT_TYPE_SPEECH)` | |
| `setAudioZoneId(zoneId)` | 동승 좌석 쪽 오디오 존(환경설정에서 번호 선택. 차종별 번호는 문서에 없음, 확인하지 못함) |
| `speak(text)`, `stop()`, `addEventListener(EventListener)` | 문장 단위로 읽고 `onDone` 을 기다림 |

오디오 정책(`SpeechController`)

- **다른 소리에 양보**: 말하기 전에 `AudioManager.getActivePlaybackConfigurations()` 의 용도를 보고, 내비 길 안내
  (`USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`)나 통화(통화 음성·통화 신호음·전화 벨)가 나는 동안은 0.25초 간격으로 최대 8초 기다립니다.
  끝나지 않으면 그 말은 소리 내지 않고 화면에만 남기며 "내비 안내 중이라 / 통화 중이라 소리 내지 않았어요"를 보입니다.
  급한 안전 알림(주행 중 문 열림, 운전자가 없는 동안의 문·자리)은 기다리지 않습니다. Gleo 목소리는 용도로 가리지 않고 포커스 거절로 기다립니다.
- 그다음 `AudioFocusRequest(AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)` 로 포커스를 요청합니다(음악은 끊지 않고 낮춤).
  얻지 못하면 1.5초 간격으로 세 번 더 묻고, 끝내 얻지 못하면 그 말은 소리 내지 않고 화면에만 남깁니다.
- `AUDIOFOCUS_LOSS`·`LOSS_TRANSIENT` 를 받으면 즉시 멈추고 포커스를 돌려줍니다(Gleo 호출, 통화).
- 말마다 결과(말함, 또는 건너뜀과 그 까닭)를 엔진에 돌려주고, 기록의 횟수는 끝까지 소리 낸 말만 셉니다.
- 선행 묵음 약 200ms(안드로이드 TTS 는 오디오 앞 무음, Pleos TTS 는 재생 지연), 문장 사이 쉼 350ms.
- 급한 운전자 알림의 알림음은 `ToneGenerator(STREAM_NOTIFICATION)` 의 짧고 낮은 소리로, 차의 스피커에서 나 동승자에게도 들립니다.
- 에뮬레이터에서 내비 음성 안내와 겹치는 경우와 Pleos 의 오디오 포커스 매트릭스 값은 확인하지 못했습니다.
- 문서: `…/connect-sdk-pleos/TextToSpeech/intro`, `…/TextToSpeech/start-and-stop`, `…/vehicle-app-developer-guide/audio-focus-development-guide/audio-focus-development`,
  `…/vehicle-app-developer-guide/voice-guidance-preroll-silence-guide`

## 6. SpeechToText SDK (`ai.pleos.playground:SpeechToText:3.3.1`) — 실험 기능

권한: `pleos.car.permission.STT_SERVICE`(에뮬레이터에서는 설치 때 허용됨). 환경설정에서 켰을 때만, 동행 중에만, 차가 말하지 않을 때만 듣습니다
(첫 소개말 동안에도 듣지 않음).

| API | 기능 |
|---|---|
| `SpeechToText(context, Mode.ON_DEVICE)` · `initialize` · `addListener(ResultListener)` · `request()` · `stop()` | "어디 가", "언제 도착", "다 왔어", "왜 안 가", "집에 가자" 다섯 가지를 알아듣고 대답 |

- 낱말 하나('어디', '언제', '얼마나')로는 대답하지 않고 가는 길을 묻는 꼴("어디 가", "여기가 어디", "언제 도착", "얼마나 남" 등)만 받습니다.
  누가 한 말인지는 가리지 못해 통화·라디오의 같은 말에도 대답할 수 있고, 같은 물음에는 18초 안에 다시 대답하지 않습니다.
- `onUpdated(stt, completed)` 의 문장은 물음의 종류로 바꾼 뒤 버립니다. 저장하지 않습니다.
- 오류: 시간 초과는 세지 않고, 정상으로 끝날 때마다 0 으로 돌립니다. 연달아 세 번, 또는 1분 안에 여섯 번 오류가 나면 그 동행에서는 물러납니다
  (권한 거부·지원 안 함 같은 오류는 바로). 앱의 나머지는 그대로 동작합니다.
- 에뮬레이터: 앱 요청으로 녹음과 인식이 시작되는 것까지 확인했고, 알아들은 문장과 대답은 확인하지 못했습니다.
- 문서: `…/connect-sdk-pleos/SpeechToText/start-and-stop`, `…/SpeechToText/result-listeners`, `…/SpeechToText/declaring-permissions`

## 7. Gleo Interface

| 함수(`assets/schema.json`) | component | 동작 |
|---|---|---|
| `REPEAT_TRIP_ANSWER` (`arrived`: boolean, 선택) | service(브로드캐스트) | 다시 말씀드리기. `arrived: true` 면 정차 중 도착을 알리고 동행을 마침('도착했어요'와 같음). 응답 `mode: "silent"`(앱이 직접 말함) |
| `PAUSE_VOICE_GUIDE` (`minutes`: number, 선택) | service(브로드캐스트) | 잠시 조용히(기본 10분, 1~60분) |
| `START_COMPANION_TRIP` (`destinationName`: string, 선택) | activity | 저장된 '자주 가는 길'로 동행 시작. 없으면 `status: "asking"`, 주행 중이거나 변속단·주행 규제를 읽지 못해 정차인지 모르면 받지 않음(`fail`) |

- 수신: Intent action = 함수 이름, 파라미터 = `ACTION_PARAM` 문자열(JSON).
- 응답: `ai.pleos.playground.intent.action.APP_ACTION_RESPONSE` 브로드캐스트(extra `packageName`, `response`). 받는 쪽을 정하지 못해
  가는 곳 이름 같은 값은 싣지 않습니다.
- 리시버와 '동행 시작' 화면은 권한 없이 열려 있습니다(Gleo 의 송신 권한·패키지가 문서에 없음). 연속 호출 제한: 말하기·조용히는 4초에 한 번·1분에 네 번,
  동행 시작은 10초에 한 번·10분에 세 번(`GleoLimits`).
- 에뮬레이터: 같은 꼴의 방송(`REPEAT_TRIP_ANSWER`)을 넣으면 앱이 다시 말하고, 앱의 응답을 Gleo 프로세스(GAA)가 받는 것까지 확인했습니다.
  Gleo 음성("글레오, …")으로 이 함수가 불리는 경로는 확인하지 못했습니다(스키마는 '검토·승인 후 활성화').
- 문서: `…/app-integration-guide/guide/app-capability`, `…/guide/deliver-schema`, `…/guide/examples`, `…/guide/action_callback`

## 8. 병합 매니페스트에 SDK 가 넣는 권한

앱이 선언하지 않았지만 SDK 가 넣는 것(빌드의 `merged_manifests` 에서 확인):

| 권한 | 넣는 곳 |
|---|---|
| `android.permission.INTERNET` | TextToSpeech 3.3.1, SpeechToText 3.3.1, universal-access 0.6.0 |
| `ai.pleos.playground.service.vehicle.VEHICLE_BINDING` | Vehicle 2.3.2 |
| `ai.pleos.playground.service.location.fused.FUSED_LOCATION_BINDING` | FusedLocation 1.1.3 |

앱이 선언한 권한 전체와 언제 묻는지는 `docs/privacy.md` 의 '권한' 절에 있습니다.

## 9. 가상 콘솔로 바꿀 수 있는 값과 없는 값

가상 콘솔은 `adb forward tcp:8088 tcp:8088` 뒤 `http://127.0.0.1:8088/` 입니다. HTTP 로 바꾸는 방법과 시연 도우미는 `docs/demo.md`.

| 값 | 가상 콘솔 | 비고 |
|---|---|---|
| 기어(P/R/N/D), 브레이크 페달(0~100%) | 있음 | |
| 차량 속도(0~240km/h) | 있음 | 페이지에는 가속 페달로, HTTP 로는 `speed=` 로 바꾼다 |
| 좌석 점유(운전석·동승석·뒷좌석 좌우) | 있음 | 사실대로 넣는다(앱이 극성을 맞춘다) |
| 문 위치(네 문 열림·닫힘), 창문 위치(0~100%) | 있음 | |
| 좌석별 안전띠 | **없음** | 체험 모드로만 시연 |
| 내비 목적지·남은 시간 | 콘솔에는 없음 | 에뮬레이터의 내장 내비에서 검색해 안내를 시작하면 들어온다 |
| 내비 위치 이동·도착 이벤트 | **없음** | 이 에뮬레이터에서는 위치가 움직이지 않는다. 구간 변화는 체험 모드로, 도착은 '도착했어요'로 보인다 |
