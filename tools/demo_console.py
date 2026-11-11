#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""「몇 번이든」 가상 콘솔 시연 도우미.

Pleos Connect Emulator 의 가상 콘솔(Virtual Console)을 HTTP 로만 조작해 docs/demo.md 의 A 순서를 한 단계씩 진행한다.
adb 는 부르지 않는다. 사람이 Enter 로 단계를 넘기고, 앱 화면의 버튼은 사람이 직접 누른다.

준비(사람이 직접)
    에뮬레이터를 Cold boot 로 띄우고 앱을 설치·실행한 뒤
    adb forward tcp:8088 tcp:8088

쓰는 법
    python tools/demo_console.py               단계마다 Enter(s = 건너뛰기, q = 끝내기)
    python tools/demo_console.py --list        단계 목록만 보기
    python tools/demo_console.py --from 10     10단계부터
    python tools/demo_console.py --dry-run     보내지 않고 보낼 요청만 보기
    python tools/demo_console.py --selftest    이 PC 안의 가짜 콘솔(빈 포트)로 전 과정을 시험한다. 에뮬레이터가 필요 없다
    --base http://127.0.0.1:8088 (기본), --client myeotbeon-demo (기본)

가상 콘솔 HTTP API (2026-11-11 Pleos Connect Emulator v2.1.0 에서 확인)
    POST /browser/session?client=<이름>   3초 안에 한 번씩 보내야 쓰기가 된다. 끊기면 쓰기가 {"error":"emulator_inactive"}.
                                          이 도우미는 1초마다 보내고, 끝날 때 &closing=1 을 보낸다
    PUT  /vehicle/gear?gear=Park|Reverse|Neutral|Drive
    PUT  /vehicle/controls?speed=0~240&accelerator=0~100&brake=0~100
    PUT  /vehicle/body?door_fl=…&door_fr=…&door_rl=…&door_rr=true|false, window_fl…window_rr=0~100
    PUT  /vehicle/seat?kind=occupied&area=driver|passenger|rear_left|rear_right&value=true|false
    GET  /vehicle/state                     지금 값(JSON). 단계마다 요약해 보인다
    GET  /vehicle/catalog                   바꿀 수 있는 값 목록

Python 3.8 이상, 표준 라이브러리만 쓴다.
"""
import argparse
import json
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Callable, List, NamedTuple, Optional

DEFAULT_BASE = "http://127.0.0.1:8088"
DEFAULT_CLIENT = "myeotbeon-demo"
HEARTBEAT_SEC = 1.0


class Step(NamedTuple):
    title: str
    puts: List[str]          # "/vehicle/" 뒤의 경로와 질의. 차례로 보낸다
    app: str = ""            # 사람이 앱에서 할 일
    expect: str = ""         # 앱이 하는 일(대본 첫째 변형 기준)
    hold: int = 0            # 보낸 뒤 기다리는 초(카운트다운)
    optional: bool = False


RESET = [
    "controls?speed=0&accelerator=0&brake=0",
    "gear?gear=Park",
    "body?door_fl=false&door_fr=false&door_rl=false&door_rr=false",
    "body?window_fl=0&window_fr=0&window_rl=0&window_rr=0",
    "seat?kind=occupied&area=driver&value=true",
    "seat?kind=occupied&area=passenger&value=false",
    "seat?kind=occupied&area=rear_left&value=false",
    "seat?kind=occupied&area=rear_right&value=true",
]

STEPS: List[Step] = [
    Step("처음 상태: P단, 문·창문 닫힘, 운전석과 뒷좌석 오른쪽에 사람(좌석 값은 사실대로 넣는다)", RESET,
         app="앱의 '출발'(출발 전 10초 설정) 화면을 연다. 처음 실행이면 첫 실행 안내에서 '속도 신호 허용하기'와 "
             "'알림 허용하기'를 눌러 허용한 뒤 '확인했어요'(동행을 시작할 때는 권한 창이 뜨지 않는다)",
         expect="'앉으신 자리'에 '뒷좌석 오른쪽 (앉아 계심)'이 자동으로 골라진다. "
                "개발자 패널(차량 모드)에는 '좌석 값: 반대로(운전석 기준 자동)'(에뮬레이터는 좌석 값이 반대로 온다)"),
    Step("(선택) 내비로 실제 목적지 넣기", [], optional=True,
         app="에뮬레이터의 내장 지도(내비)에서 목적지를 검색해 안내를 시작하고 앱으로 돌아온다. "
             "'가는 곳'이 비어 있으면 내비 목적지 이름이 들어온다 — 어르신께 들려줄 이름이므로 부르기 쉬운 이름으로 고친다",
         expect="개발자 패널: '목적지 … · 남은 N초 / 전체 N초'(남은 시간은 초). 이 에뮬레이터는 내비 위치가 움직이지 않아 구간이 바뀌지 않고 "
                "도착 안내도 나지 않는다. 내비가 넉넉히 남았다고 알리는 동안 P단에 서면 '경유지 정차'가 된다 — "
                "'도착했어요' 장면을 보이려면 그 전에 내비 안내를 끈다"),
    Step("동행 시작", [],
         app="호칭 '어머니', 가는 곳 '큰딸 집', 운전하는 사람 '따님'을 정하고 '동행 시작'(또는 자주 가는 길 '큰딸 집'을 한 번 누름)",
         expect="소개말 “어머니, 저는 길을 알려 주는 목소리예요. 따님이 운전할 때 제가 알려 드릴게요.”"),
    Step("출발: D단, 속도 40", ["gear?gear=Drive", "controls?speed=40&accelerator=20&brake=0"], hold=5,
         expect="“어머니, 이제 출발해요. 큰딸 집으로 가요.” 메뉴의 출발·기록·설정이 잠기고 '도착했어요'·'동행 끝내기'가 사라진다"),
    Step("'다시 말씀드리기' 두 번", [],
         app="동행 화면의 '다시 말씀드리기'를 누르고, 말이 끝나면 한 번 더",
         expect="같은 사실을 다른 표현으로: “어머니, 큰딸 집에 가요. 따님이 운전하고 있어요.” / "
                "“어머니, 큰딸 집에 가는 길이에요. 따님이 운전해서 가요.”"),
    Step("신호 대기: 속도 0, 브레이크 100% 로 20초(속도 신호를 허용하지 않았으면 35초)",
         ["controls?speed=0&accelerator=0&brake=100"], hold=37,
         expect="20초쯤 뒤(속도 신호를 허용하지 않았으면 35초쯤 뒤) “어머니, 잠깐 신호를 기다려요. 아직 가는 중이에요.”"),
    Step("다시 출발: 속도 30", ["controls?speed=30&accelerator=15&brake=0"], hold=3),
    Step("뒷좌석 오른쪽 창문 40%", ["body?window_rr=40"], hold=6,
         expect="동승자: “어머니, 창문이 열렸어요. 손은 안에 두세요.” 운전자 화면: “같이 탄 분 쪽 창문이 열렸어요.”"),
    Step("창문 닫기", ["body?window_rr=0"], hold=2),
    Step("주행 중 뒷좌석 오른쪽 문 열림", ["body?door_rr=true"], hold=5,
         expect="붉은 한 줄 “같이 탄 분 쪽 문이 열렸어요.”와 알림음, 동승자: “어머니, 문은 닫아 주세요. 아직 가는 중이에요.” "
                "(차 자체의 '도어 열림' 경고도 계기판에 뜬다)"),
    Step("문 닫기", ["body?door_rr=false"], hold=2, expect="붉은 한 줄이 사라진다"),
    Step("가는 길에 잠깐 들름: P단으로 서기(내비 안내 없이 간 길)",
         ["controls?speed=0&accelerator=0&brake=100", "gear?gear=Park"], hold=8,
         expect="5초쯤 뒤 “어머니, 차를 세웠어요. 따님이 알려 드릴 때까지 앉아 계세요.”(도착인지 모르므로 도착을 부정하지 않는 말). "
                "아래 줄에 '도착했어요'·'동행 끝내기'가 보인다 — 여기서는 누르지 않는다"),
    Step("운전자가 내림: 운전석 문 열림 → 운전석 비움 → 문 닫힘",
         ["body?door_fl=true", "seat?kind=occupied&area=driver&value=false", "body?door_fl=false"], hold=6,
         expect="2.5초쯤 뒤 “어머니, 따님이 잠깐 내렸어요. 금방 돌아와요.”(75초마다 짧게 다시)"),
    Step("(선택) 운전자가 없는 동안 뒷좌석 오른쪽 문 열림", ["body?door_rr=true"], hold=4, optional=True,
         expect="바로 “어머니, 앉아서 따님을 기다려요.” 알림음·붉은 줄은 운전자가 돌아올 때 낸다"),
    Step("(선택) 그 문 닫기", ["body?door_rr=false"], hold=1, optional=True),
    Step("운전자가 돌아옴: 운전석 문 열림 → 운전석에 앉음 → 문 닫힘",
         ["body?door_fl=true", "seat?kind=occupied&area=driver&value=true", "body?door_fl=false"], hold=6,
         expect="“어머니, 따님이 돌아왔어요. 편히 앉아 계세요.”(도착일 수도 있는 정차라 출발을 말하지 않는다). "
                "앞의 선택 단계를 했으면 붉은 한 줄 “차에서 내린 사이 같이 탄 분 쪽 문이 열렸어요.”와 알림음(1분)"),
    Step("다시 출발: D단, 속도 30", ["gear?gear=Drive", "controls?speed=30&accelerator=15&brake=0"], hold=10,
         expect="따로 출발 안내는 하지 않는다. '도착했어요'·'동행 끝내기'가 사라지고 메뉴의 출발·기록·설정이 잠긴다"),
    Step("목적지에서 P단으로 서기", ["controls?speed=0&accelerator=0&brake=100", "gear?gear=Park"], hold=8,
         expect="5초쯤 뒤 정차 문장의 다른 표현 “어머니, 차가 잠깐 섰어요. 따님이 도와 드릴 때까지 앉아 계세요.” "
                "아래 줄에 '도착했어요'"),
    Step("'도착했어요'", [],
         app="정차 중 아래 줄의 '도착했어요'를 누른다",
         expect="“어머니, 큰딸 집에 다 왔어요. 따님이 올 때까지 앉아 계세요.” 말이 끝나면 동행이 끝나고 '오늘의 기록'으로 넘어간다"),
    Step("기록과 자주 가는 길", [],
         app="'오늘의 기록'을 본 뒤 '출발'을 누른다",
         expect="'N번 차가 대신 말했어요'와 그 세부(먼저 말함·다시 말씀드리기·물음에 대답·멈춤 안내), 문·창문·안전띠 안내는 따로. "
                "출발 화면의 '자주 가는 길'에 '큰딸 집 · 어머니 · 눌러서 시작'"),
    Step("정리: 처음 상태로", RESET),
]


class ConsoleError(Exception):
    pass


class Console:
    """가상 콘솔 HTTP. 쓰기 전에 브라우저 세션(heartbeat)을 살려 둔다."""

    def __init__(self, base: str, client: str, dry_run: bool = False, timeout: float = 8.0):
        self.base = base.rstrip("/")
        self.client = client
        self.dry_run = dry_run
        self.timeout = timeout
        self._stop = threading.Event()
        self._thread: Optional[threading.Thread] = None

    def _request(self, method: str, path: str) -> str:
        req = urllib.request.Request(self.base + path, method=method, data=b"" if method != "GET" else None)
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read().decode("utf-8", "replace")
        except urllib.error.HTTPError as e:
            return e.read().decode("utf-8", "replace")
        except (urllib.error.URLError, OSError) as e:
            raise ConsoleError(
                f"가상 콘솔({self.base})에 닿지 않습니다: {e}. 에뮬레이터가 Cold boot 로 떴는지, "
                f"'adb forward tcp:8088 tcp:8088' 을 했는지 확인하세요."
            ) from e

    def heartbeat(self, closing: bool = False) -> None:
        if self.dry_run:
            return
        path = "/browser/session?client=" + urllib.parse.quote(self.client) + ("&closing=1" if closing else "")
        self._request("POST", path)

    def start(self) -> None:
        if self.dry_run:
            return
        self.heartbeat()

        def loop() -> None:
            while not self._stop.wait(HEARTBEAT_SEC):
                try:
                    self.heartbeat()
                except ConsoleError:
                    pass

        self._thread = threading.Thread(target=loop, daemon=True)
        self._thread.start()

    def close(self) -> None:
        self._stop.set()
        if self._thread:
            self._thread.join(timeout=3)
        try:
            self.heartbeat(closing=True)
        except ConsoleError:
            pass

    def put(self, query: str) -> None:
        """PUT /vehicle/<query>. 세션이 끊겼다고 하면 세션을 한 번 살리고 다시 보낸다."""
        if self.dry_run:
            return
        body = self._request("PUT", "/vehicle/" + query)
        if "emulator_inactive" in body:
            self.heartbeat()
            body = self._request("PUT", "/vehicle/" + query)
        if '"error"' in body:
            raise ConsoleError(f"PUT /vehicle/{query} → {body.strip()[:200]}")

    def state_summary(self) -> str:
        if self.dry_run:
            return ""
        try:
            s = json.loads(self._request("GET", "/vehicle/state"))
        except (ConsoleError, ValueError):
            return ""
        return summarize(s)


def summarize(s: dict) -> str:
    """GET /vehicle/state 의 JSON 을 한 줄로. 모르는 꼴이면 아는 것만."""
    def get(d, *keys):
        for k in keys:
            if not isinstance(d, dict):
                return None
            d = d.get(k)
        return d

    parts = []
    gear = get(s, "gear", "actual", "value")
    if gear is not None:
        parts.append(f"기어 {gear}")
    speed, brake = get(s, "controls", "speed_kph"), get(s, "controls", "brake_pct")
    if speed is not None:
        parts.append(f"속도 {speed}")
    if brake is not None:
        parts.append(f"브레이크 {brake}%")
    names = {"front_left": "운전석", "front_right": "앞 옆자리", "rear_left": "뒤 왼쪽", "rear_right": "뒤 오른쪽"}
    doors = get(s, "body", "doors")
    if isinstance(doors, dict):
        opened = [names.get(k, k) for k, v in doors.items() if v is True]
        parts.append("열린 문 " + (", ".join(opened) if opened else "없음"))
    windows = get(s, "body", "windows")
    if isinstance(windows, dict):
        opened = [f"{names.get(k, k)} {v}%" for k, v in windows.items() if isinstance(v, (int, float)) and v > 0]
        parts.append("열린 창문 " + (", ".join(opened) if opened else "없음"))
    seats = get(s, "seat", "occupied")
    if isinstance(seats, dict):
        seat_names = {"driver": "운전석", "passenger": "동승석", "rear_left": "뒤 왼쪽", "rear_right": "뒤 오른쪽"}
        taken = [seat_names.get(k, k) for k, v in seats.items() if v is True]
        parts.append("사람 있음 " + (", ".join(taken) if taken else "없음"))
    return " · ".join(parts)


def print_step(n: int, total: int, step: Step, out=print) -> None:
    out("")
    out(f"[{n}/{total}] {step.title}")
    for q in step.puts:
        out(f"    콘솔  PUT /vehicle/{q}")
    if step.app:
        out(f"    앱에서  {step.app}")
    if step.expect:
        out(f"    기대  {step.expect}")


def countdown(left: int) -> None:
    """기다리는 초를 한 줄에 덮어 쓴다. 0 이면 그 줄을 지운다."""
    sys.stdout.write(f"\r    기다림 {left:2d}초 " if left else "\r" + " " * 20 + "\r")
    sys.stdout.flush()


def run(steps: List[Step], console: Console, start_at: int = 1, ask: Callable[[str], str] = input,
        sleep: Callable[[float], None] = time.sleep, gap: float = 0.6, out=print,
        tick: Callable[[int], None] = countdown) -> int:
    """단계를 차례로 진행한다. 돌려주는 값: 끝까지 간 단계 수."""
    done = 0
    total = len(steps)
    for n, step in enumerate(steps, start=1):
        if n < start_at:
            continue
        print_step(n, total, step, out)
        prompt = "    Enter = " + ("보내기" if step.puts else "다음") + ", s = 건너뛰기, q = 끝내기 > "
        answer = ask(prompt).strip().lower()
        if answer == "q":
            break
        if answer == "s":
            continue
        for i, q in enumerate(step.puts):
            if i:
                sleep(gap)  # 사람이 콘솔을 누르는 정도의 간격(운전석 문 → 좌석 → 문)
            console.put(q)
        if step.puts and not console.dry_run:
            summary = console.state_summary()
            if summary:
                out(f"    지금  {summary}")
        if step.hold and not console.dry_run:
            for left in range(step.hold, 0, -1):
                tick(left)
                sleep(1)
            tick(0)
        done += 1
    return done


# ───────────────────────── 자체 시험(가짜 콘솔) ─────────────────────────

def selftest() -> int:
    """이 PC 안의 빈 포트에 가짜 가상 콘솔을 띄우고 전 과정을 돌려 본다. 에뮬레이터·adb 를 쓰지 않는다."""
    from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
    from urllib.parse import parse_qs, urlparse

    log: List[tuple] = []
    lock = threading.Lock()
    state = {"gear": {"actual": {"value": "Park"}}, "controls": {"speed_kph": 0, "brake_pct": 0, "accelerator_pct": 0},
             "body": {"doors": {}, "windows": {}}, "seat": {"occupied": {}}}
    last_beat = [0.0]
    reject_next = [1]  # 첫 쓰기 하나는 세션이 끊긴 것처럼 거절해 다시 보내는지 본다

    door_area = {"door_fl": "front_left", "door_fr": "front_right", "door_rl": "rear_left", "door_rr": "rear_right"}
    window_area = {"window_fl": "front_left", "window_fr": "front_right", "window_rl": "rear_left", "window_rr": "rear_right"}

    class Fake(BaseHTTPRequestHandler):
        def log_message(self, *args):  # 조용히
            pass

        def reply(self, code: int, obj: dict) -> None:
            data = json.dumps(obj).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_POST(self):
            with lock:
                log.append(("POST", self.path))
                last_beat[0] = time.time()
            self.reply(200, {"ok": True})

        def do_GET(self):
            with lock:
                log.append(("GET", self.path))
                snapshot = json.loads(json.dumps(state))
            self.reply(200, snapshot)

        def do_PUT(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            with lock:
                log.append(("PUT", self.path))
                if time.time() - last_beat[0] > 3 or reject_next[0] > 0:
                    reject_next[0] -= 1
                    self.reply(409, {"error": "emulator_inactive"})
                    return
                if u.path == "/vehicle/gear":
                    state["gear"]["actual"]["value"] = q["gear"]
                elif u.path == "/vehicle/controls":
                    for k, field in (("speed", "speed_kph"), ("brake", "brake_pct"), ("accelerator", "accelerator_pct")):
                        if k in q:
                            state["controls"][field] = float(q[k])
                elif u.path == "/vehicle/body":
                    for k, v in q.items():
                        if k in door_area:
                            state["body"]["doors"][door_area[k]] = v == "true"
                        elif k in window_area:
                            state["body"]["windows"][window_area[k]] = int(v)
                elif u.path == "/vehicle/seat" and q.get("kind") == "occupied":
                    state["seat"]["occupied"][q["area"]] = q["value"] == "true"
                else:
                    self.reply(404, {"error": "unknown"})
                    return
            self.reply(200, {"ok": True})

    server = ThreadingHTTPServer(("127.0.0.1", 0), Fake)
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()
    base = f"http://127.0.0.1:{port}"
    failures = []

    def check(cond: bool, what: str) -> None:
        if not cond:
            failures.append(what)

    quiet = lambda *a, **k: None  # noqa: E731
    try:
        # 1) 마른 실행은 아무것도 보내지 않는다
        dry = Console(base, "selftest", dry_run=True)
        dry.start()
        run(STEPS, dry, ask=lambda _: "", sleep=lambda _: None, out=quiet, tick=quiet)
        dry.close()
        check(not log, f"--dry-run 이 요청을 보냈다: {log[:3]}")

        # 2) 전 과정: 선택 단계까지 모두 보내고, 세션이 끊겼다는 거절에 한 번 살려 다시 보낸다
        c = Console(base, "selftest")
        c.start()
        done = run(STEPS, c, ask=lambda _: "", sleep=lambda _: None, gap=0, out=quiet, tick=quiet)
        c.close()
        check(done == len(STEPS), f"끝까지 간 단계 {done}/{len(STEPS)}")
        puts = [p for m, p in log if m == "PUT"]
        expected = ["/vehicle/" + q for st in STEPS for q in st.puts]
        check(puts[0] == expected[0] and puts[1] == expected[0], "거절된 첫 쓰기를 세션을 살린 뒤 다시 보내지 않았다")
        check(puts[1:] == expected, f"보낸 쓰기가 단계와 다르다: {len(puts) - 1}개 / 기대 {len(expected)}개")
        first_put = next(i for i, (m, _) in enumerate(log) if m == "PUT")
        check(any(m == "POST" for m, _ in log[:first_put]), "첫 쓰기 전에 브라우저 세션을 열지 않았다")
        check(log[-1][0] == "POST" and "closing=1" in log[-1][1], "끝날 때 세션 닫기(closing=1)를 보내지 않았다")
        check(state["gear"]["actual"]["value"] == "Park", "정리 뒤 기어가 P 가 아니다")
        check(not any(state["body"]["doors"].values()), "정리 뒤 열린 문이 있다")
        check(not any(state["body"]["windows"].values()), "정리 뒤 열린 창문이 있다")
        check(state["seat"]["occupied"] == {"driver": True, "passenger": False, "rear_left": False, "rear_right": True},
              f"정리 뒤 좌석 값이 다르다: {state['seat']['occupied']}")
        check(summarize(state).startswith("기어 Park"), f"상태 요약: {summarize(state)}")

        # 3) --from 과 건너뛰기·끝내기
        log.clear()
        c2 = Console(base, "selftest")
        c2.start()
        answers = iter(["s", "", "q"])
        n_steps = run(STEPS, c2, start_at=len(STEPS) - 2, ask=lambda _: next(answers), sleep=lambda _: None, gap=0,
                      out=quiet, tick=quiet)
        c2.close()
        check(n_steps == 1, f"--from·건너뛰기·끝내기: 진행 {n_steps}")

        # 4) 닿지 않는 주소는 알아듣기 쉬운 오류
        try:
            Console("http://127.0.0.1:9", "selftest", timeout=1).put("gear?gear=Park")
            check(False, "닿지 않는 주소에서 오류가 나지 않았다")
        except ConsoleError as e:
            check("adb forward" in str(e), "닿지 않을 때 안내가 없다")
    finally:
        server.shutdown()
        server.server_close()

    if failures:
        print("selftest 실패:")
        for f in failures:
            print("  -", f)
        return 1
    print(f"selftest 통과: 단계 {len(STEPS)}개, 쓰기 {sum(len(s.puts) for s in STEPS)}개, 세션 유지·다시 보내기·정리 확인")
    return 0


def main(argv: Optional[List[str]] = None) -> int:
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    p = argparse.ArgumentParser(description="가상 콘솔 시연 도우미(HTTP 만, adb 없음)")
    p.add_argument("--base", default=DEFAULT_BASE, help="가상 콘솔 주소(기본 %(default)s)")
    p.add_argument("--client", default=DEFAULT_CLIENT, help="브라우저 세션 이름(기본 %(default)s)")
    p.add_argument("--from", dest="start_at", type=int, default=1, help="이 단계부터")
    p.add_argument("--list", action="store_true", help="단계 목록만 보기")
    p.add_argument("--dry-run", action="store_true", help="보내지 않고 보낼 요청만 보기")
    p.add_argument("--selftest", action="store_true", help="가짜 콘솔로 자체 시험")
    a = p.parse_args(argv)

    if a.selftest:
        return selftest()
    if a.list:
        for n, step in enumerate(STEPS, start=1):
            print_step(n, len(STEPS), step)
        return 0
    console = Console(a.base, a.client, dry_run=a.dry_run)
    try:
        console.start()
        print(f"가상 콘솔 {a.base} — 브라우저 세션 '{a.client}' 을 1초마다 살려 둡니다." if not a.dry_run else "마른 실행: 보내지 않습니다.")
        run(STEPS, console, start_at=a.start_at)
    except ConsoleError as e:
        print(f"\n오류: {e}")
        return 2
    except (KeyboardInterrupt, EOFError):
        print("\n멈춤")
    finally:
        console.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
