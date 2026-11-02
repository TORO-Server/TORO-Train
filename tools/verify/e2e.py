"""実機の確認。本物のサーバーに本物のクライアントをつないで、マスコンの操作を自動で確かめる (Windows のみ)。
プラグインなのでクライアントに Mod は入れず、キーをウィンドウに送り、結果はコンソール、クライアントのログ ([CHAT])、
画面の撮影 (TORO_VERIFY_DIR/shots) で判定する。やり方は MarumaSign の tools/verify にならっている。

使い方: ./gradlew build のあとで python tools/verify/e2e.py
必要なもの: Windows、画面、Python 3 と pip install portablemc pillow psutil、Java 25 以上
時間: 5 分くらい。Minecraft のウィンドウが開き、ときどき手前に出る。しゃがみと右クリックは本物の入力で送るが、
      ウィンドウが手前にあることを確かめてから送るので、ほかのウィンドウには入らない。

環境変数:
    TORO_VERIFY_MC_DIR  クライアントのファイルを置くフォルダ (既定: TORO_VERIFY_CACHE/minecraft)。初回は数百 MB を取ってくる
"""
import os
import re
import shutil
import subprocess
import sys
import time
import traceback
import json
from pathlib import Path

import common
from common import check, wait_until

if sys.platform == "win32":
    import ctypes
    from ctypes import wintypes

    user32 = ctypes.windll.user32

MC_DIR = Path(os.environ.get("TORO_VERIFY_MC_DIR", common.CACHE / "minecraft"))
SHOTS = common.WORK / "shots"
OPTIONS = """\
pauseOnLostFocus:false
tutorialStep:none
onboardAccessibility:false
skipMultiplayerWarning:true
joinedFirstServer:true
narrator:0
fullscreen:false
renderDistance:4
maxFps:60
inactivityFpsLimit:minimized
fov:0.0
soundCategory_master:0.0
lang:ja_jp
"""
# Windows の仮想キーコードとスキャンコード
KEYS = {
    "w": (0x57, 0x11), "a": (0x41, 0x1E), "s": (0x53, 0x1F), "d": (0x44, 0x20), "t": (0x54, 0x14),
    "space": (0x20, 0x39), "ctrl": (0x11, 0x1D), "enter": (0x0D, 0x1C),
}


def windows_of(pids):
    """プロセスの Minecraft のウィンドウを (ハンドル, 位置) の一覧で返す"""
    found = []

    @ctypes.WINFUNCTYPE(wintypes.BOOL, wintypes.HWND, wintypes.LPARAM)
    def visit(window, _):
        owner = wintypes.DWORD()
        user32.GetWindowThreadProcessId(window, ctypes.byref(owner))
        if owner.value in pids and user32.IsWindowVisible(window):
            title = ctypes.create_unicode_buffer(256)
            user32.GetWindowTextW(window, title, 256)
            if title.value.startswith("Minecraft"):
                rect = wintypes.RECT()
                user32.GetWindowRect(window, ctypes.byref(rect))
                if rect.right - rect.left > 200:
                    found.append((window, (rect.left, rect.top, rect.right, rect.bottom)))
        return True

    user32.EnumWindows(visit, 0)
    return found


def print_window(window, box, path):
    """ウィンドウの中身を、手前に出さずに撮る (PrintWindow の PW_RENDERFULLCONTENT)"""
    from PIL import Image

    gdi32 = ctypes.windll.gdi32
    user32.GetWindowDC.restype = wintypes.HDC
    user32.GetWindowDC.argtypes = [wintypes.HWND]
    user32.PrintWindow.argtypes = [wintypes.HWND, wintypes.HDC, wintypes.UINT]
    user32.ReleaseDC.argtypes = [wintypes.HWND, wintypes.HDC]
    gdi32.CreateCompatibleDC.restype = wintypes.HDC
    gdi32.CreateCompatibleDC.argtypes = [wintypes.HDC]
    gdi32.CreateCompatibleBitmap.restype = wintypes.HBITMAP
    gdi32.CreateCompatibleBitmap.argtypes = [wintypes.HDC, ctypes.c_int, ctypes.c_int]
    gdi32.SelectObject.restype = wintypes.HGDIOBJ
    gdi32.SelectObject.argtypes = [wintypes.HDC, wintypes.HGDIOBJ]
    gdi32.DeleteObject.argtypes = [wintypes.HGDIOBJ]
    gdi32.DeleteDC.argtypes = [wintypes.HDC]
    gdi32.GetDIBits.argtypes = [wintypes.HDC, wintypes.HBITMAP, wintypes.UINT, wintypes.UINT, ctypes.c_void_p,
                                ctypes.c_void_p, wintypes.UINT]

    class BitmapInfoHeader(ctypes.Structure):
        _fields_ = [("biSize", wintypes.DWORD), ("biWidth", wintypes.LONG), ("biHeight", wintypes.LONG),
                    ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD),
                    ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", wintypes.LONG),
                    ("biYPelsPerMeter", wintypes.LONG), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD)]

    width, height = box[2] - box[0], box[3] - box[1]
    window_dc = user32.GetWindowDC(window)
    memory_dc = gdi32.CreateCompatibleDC(window_dc)
    bitmap = gdi32.CreateCompatibleBitmap(window_dc, width, height)
    previous = gdi32.SelectObject(memory_dc, bitmap)
    try:
        if not user32.PrintWindow(window, memory_dc, 2):
            return False
        header = BitmapInfoHeader(ctypes.sizeof(BitmapInfoHeader), width, -height, 1, 32, 0, 0, 0, 0, 0, 0)
        buffer = ctypes.create_string_buffer(width * height * 4)
        if not gdi32.GetDIBits(memory_dc, bitmap, 0, height, buffer, ctypes.byref(header), 0):
            return False
    finally:
        gdi32.SelectObject(memory_dc, previous)
        gdi32.DeleteObject(bitmap)
        gdi32.DeleteDC(memory_dc)
        user32.ReleaseDC(window, window_dc)
    Image.frombuffer("RGB", (width, height), buffer, "raw", "BGRX", 0, 1).save(path)
    return True


class Client:
    def __init__(self, name, version):
        self.name = name
        self.version = version
        self.dir = common.WORK / f"client-{name}"
        self.proc = None

    def start(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        self.dir.mkdir(parents=True)
        (self.dir / "options.txt").write_text(OPTIONS, encoding="utf-8")
        command = [shutil.which("portablemc"), "--timeout", "60", "--main-dir", str(MC_DIR), "--work-dir", str(self.dir), "--output", "human",
                   "start", self.version, "-u", self.name, "-s", "127.0.0.1", "-p", str(common.PORT), "--resolution", "1280x720", "--jvm-args=-Xmx2G"]
        self.output = open(self.dir / "launcher-output.log", "w", encoding="utf-8", errors="replace")
        self.proc = subprocess.Popen(command, stdout=self.output, stderr=subprocess.STDOUT)

    def window(self):
        import psutil
        try:
            root = psutil.Process(self.proc.pid)
            pids = {root.pid} | {child.pid for child in root.children(recursive=True)}
        except psutil.Error:
            return None
        found = windows_of(pids)
        return found[0] if found else None

    def focus(self):
        """ウィンドウを手前に出す。手前に出たら True。手前にないと描画が遅れて、短いキーの入力を取りこぼす"""
        hwnd = self.window()[0]
        for _ in range(5):
            # Alt を押していると、別のプロセスのウィンドウも手前に出せる
            user32.keybd_event(0x12, 0, 0, 0)
            user32.keybd_event(0x12, 0, 2, 0)
            user32.ShowWindow(hwnd, 9)
            user32.SetForegroundWindow(hwnd)
            time.sleep(0.4)
            if user32.GetForegroundWindow() == hwnd:
                time.sleep(0.5)
                return True
        return False

    def press(self, name, hold=0.25, after=0.2):
        """キーをウィンドウに送る。手前になくても届くので、使っている人の操作の邪魔をしない"""
        hwnd = self.window()[0]
        vk, scan = KEYS[name]
        user32.PostMessageW(hwnd, 0x0100, vk, (scan << 16) | 1)
        time.sleep(hold)
        user32.PostMessageW(hwnd, 0x0101, vk, (scan << 16) | 1 | (1 << 30) | (1 << 31))
        time.sleep(after)

    def tap(self, name, times):
        for _ in range(times):
            self.press(name)

    def shift_down(self):
        """Shift は PostMessage では効かないので、本物のキー入力で送る"""
        if not self.focus():
            return False
        user32.keybd_event(0xA0, 0x2A, 0, 0)
        return True

    def shift_up(self):
        user32.keybd_event(0xA0, 0x2A, 2, 0)

    def shift(self, hold=0.8):
        if not self.shift_down():
            return False
        time.sleep(hold)
        self.shift_up()
        time.sleep(0.5)
        return True

    def right_click(self):
        """右クリックも PostMessage では効かないので、本物のマウス入力で送る"""
        if not self.focus():
            return False
        user32.mouse_event(0x0008, 0, 0, 0, 0)
        time.sleep(0.15)
        user32.mouse_event(0x0010, 0, 0, 0, 0)
        time.sleep(0.4)
        return True

    def chat(self, text):
        self.press("t", after=0.6)
        hwnd = self.window()[0]
        for ch in text:
            user32.PostMessageW(hwnd, 0x0102, ord(ch), 1)
            time.sleep(0.02)
        time.sleep(0.2)
        self.press("enter", after=0.6)

    def log_lines(self):
        path = self.dir / "logs" / "latest.log"
        return path.read_text(encoding="utf-8", errors="replace").splitlines() if path.is_file() else []

    def mark(self):
        return len(self.log_lines())

    def wait_chat(self, text, since, timeout=4.0):
        return wait_until(lambda: any("[CHAT]" in line and text in line for line in self.log_lines()[since:]), timeout, 0.2)

    def shot(self, name):
        SHOTS.mkdir(parents=True, exist_ok=True)
        window, box = self.window()
        ok = print_window(window, box, SHOTS / f"{name}.png")
        print(f"  shot {name}: {ok}", flush=True)
        return ok

    def stop(self):
        if self.proc is None or self.proc.poll() is not None:
            return
        found = self.window()
        if found:
            user32.PostMessageW(found[0], 0x0010, 0, 0)  # WM_CLOSE
            try:
                self.proc.wait(20)
            except subprocess.TimeoutExpired:
                pass
        subprocess.run(["taskkill", "/T", "/F", "/PID", str(self.proc.pid)], capture_output=True)


def sign(server, z, *lines):
    text = ",".join(json.dumps(line) for line in (list(lines) + [""] * 4)[:4])
    server.cmd(f"setblock 0 -62 {z} minecraft:oak_sign[rotation=0]{{front_text:{{messages:[{text}]}}}}", 0.2)


def stop_with_eb(server, c):
    c.press("a")
    c.tap("s", 8)
    time.sleep(0.4)
    return server.info()


def driving(server, c):
    """マスコンの基本の操作"""
    server.cmd("tt create 1 iron_block at 0 -60 8 world south")
    server.cmd("tp Tester 0.5 -59.5 5.5 0 0", 1.0)
    c.chat("/tt enter")
    time.sleep(1.0)
    i = server.info()
    check("/tt enter で座る", "Tester" in i["riders"], i["text"])
    c.shot("01-riding")

    c.press("w", after=0.6)
    i = server.info()
    check("マスコン OFF のときは W でノッチが入らない", i["driver"] is None, i["text"])
    time.sleep(0.5)
    server.cmd("tt stop")

    run_mark = c.mark()
    c.press("d", after=0.5)
    check("D で ON の案内が出る", c.wait_chat("モード: ON", run_mark))
    i = server.info()
    check("D で ON になる (N)", i["driver"] == "Tester" and i["notch"] == "N", i["text"])
    c.shot("02-mascon-on")

    c.tap("w", 3)
    i = server.info()
    check("W 3 回で P3", i["notch"] == "P3", i["notch"])
    time.sleep(1.0)
    i = server.info()
    check("P3 で速くなる", i["speed"] and i["speed"] > 0.1, i["speed"])
    c.shot("03-p3-hud")
    c.press("a")
    check("A で N", server.info()["notch"] == "N")
    c.tap("w", 9)
    i = server.info()
    check("W を続けても P7 まで", i["notch"] == "P7", i["notch"])

    # 看板とパワードレールを無視して走る
    start = len(server.lines)
    top = 0.0

    def past():
        nonlocal top
        info = server.info()
        top = max(top, info["speed"] or 0.0)
        return info if info.get("z", 0) > 190 else None

    i = wait_until(past, 30)
    check("マスコン ON で駅と信号とパワードレールを止まらず通過する", i is not None, top)
    check("P7 で速度が出る (0.8 以上、1.5 以下)", 0.8 <= top <= 1.5001, top)
    check("announce の看板は働く", c.wait_chat("passtest", run_mark, 1.0))
    check("detector の看板は働く", server.log_has("detector", start))
    check("通過後に detector のレバーが切れている", server.passed("execute if block 1 -62 180 minecraft:lever[powered=false]"))
    c.shot("04-p7-fast")

    # ブレーキ
    c.press("a")
    c.tap("s", 7)
    i = server.info()
    check("S で B7", i["notch"] == "B7", i["notch"])
    before = i["speed"] or 0
    time.sleep(0.8)
    after = server.info()["speed"] or 0
    check("B7 で減速する", after < before, (before, after))
    c.press("s")
    i = server.info()
    check("もう 1 回 S で EB、その場で止まる", i["notch"] == "EB" and i["speed"] == 0.0, i["text"])
    c.press("s")
    check("EB から S を押しても EB のまま", server.info()["notch"] == "EB")
    c.shot("05-eb")

    # 進行方向
    z0 = server.info()["z"]
    m = c.mark()
    c.press("space", after=0.5)
    check("停車中にスペースで進行方向を切り替える", c.wait_chat("進行方向を切り替えました", m))
    time.sleep(0.6)
    c.press("a")
    c.tap("w", 3)
    time.sleep(1.2)
    i = server.info()
    check("切り替えると反対へ走る", i["z"] < z0 + 0.5, (z0, i["z"]))
    m = c.mark()
    c.press("space", after=0.5)
    check("走行中のスペースは断られる", c.wait_chat("走行中は進行方向を切り替えられません", m))
    stop_with_eb(server, c)
    time.sleep(0.6)
    c.press("space", after=0.6)
    time.sleep(0.6)

    # 素手の右クリック
    m = c.mark()
    server.cmd("rotate Tester 0 60", 0.5)
    c.right_click()
    ok = c.wait_chat("進行方向を切り替えました", m, 1.0)
    if not ok:
        # マウスを取り込む前のクリックは効かないことがあるので、もう 1 回
        time.sleep(0.7)
        c.right_click()
        ok = c.wait_chat("進行方向を切り替えました", m, 2.0)
    c.shot("05b-right-click")
    check("停車中に素手の右クリックで進行方向を切り替える", ok)
    if ok:
        time.sleep(0.6)
        c.press("space", after=0.6)
    server.cmd("rotate Tester 0 0", 0.3)

    # 警笛
    start = len(server.lines)
    c.press("ctrl", after=0.3)
    check("Ctrl で警笛", wait_until(lambda: server.log_has("の警笛", start), 2.0, 0.1))

    # OFF にする。切り替えは 1 秒に 1 回
    time.sleep(1.2)
    m = c.mark()
    c.press("d", after=0.3)
    check("D で OFF の案内が出る", c.wait_chat("モード: OFF", m))
    check("D で OFF になる", server.info()["driver"] is None)
    c.press("d", after=0.1)
    check("切り替えてから 1 秒はもう一度切り替わらない", server.info()["driver"] is None)


def off_mode(server, c):
    """OFF のときは看板が効き、W で発進する"""
    time.sleep(1.2)
    i = server.info()
    z = int(i["z"])
    station = z + 12
    blocker = z + 30
    sign(server, station, "[+train]", "station", "2")
    sign(server, blocker, "[+train]", "blocker")
    server.cmd("rotate Tester 0 0", 0.3)
    c.press("w", after=0.6)
    i = server.info()
    check("OFF のときは W で見ている向きに発進する", (i["kmh"] or 0) > 0 and i["z"] > z, i["text"])

    def at_station():
        info = server.info()
        return info if "停車中" in info["action"] else None

    i = wait_until(at_station, 25, 0.25)
    check("OFF のときは駅の看板で止まる", i is not None and (i["kmh"] or 0) == 0, i and i["text"])

    def at_blocker():
        info = server.info()
        return info if "信号" in info["action"] and (info["kmh"] or 0) == 0 else None

    i = wait_until(at_blocker, 30, 0.4)
    check("OFF のときは信号の看板の手前で止まる", i is not None and i["z"] <= blocker + 0.1, i and i["text"])
    c.shot("06-off-blocked")
    # 信号で止まっている列車も、ON にすれば進める
    time.sleep(1.1)
    c.press("d", after=0.3)
    c.tap("w", 3)
    i = wait_until(lambda: server.info().get("z", 0) > blocker + 1, 8)
    check("ON にすると信号を越えて進める", i)
    i = stop_with_eb(server, c)
    check("信号を越えて EB で止まる", i["speed"] == 0.0)


def player_toggle(server, c):
    """/mascon on|off と、降りること"""
    time.sleep(1.2)
    c.press("d", after=0.3)
    check("(準備) OFF", server.info()["driver"] is None)
    m = c.mark()
    c.chat("/mascon off")
    check("/mascon off の案内", c.wait_chat("無効化しました", m))
    time.sleep(1.2)
    m = c.mark()
    c.press("d", after=0.5)
    check("/mascon off のあいだは D で ON にならない", server.info()["driver"] is None and not c.wait_chat("モード", m, 0.5))
    stored = (common.SERVER_DIR / "plugins" / "TORO-Train" / "mascon.yml").read_text(encoding="utf-8")
    check("/mascon off が mascon.yml に残る", len(stored.splitlines()) >= 3, stored)
    m = c.mark()
    c.chat("/mascon on")
    check("/mascon on の案内", c.wait_chat("有効化しました", m))
    time.sleep(1.2)
    c.press("d", after=0.3)
    check("/mascon on で D が戻る", server.info()["driver"] == "Tester")

    c.shift()
    time.sleep(0.5)
    i = server.info()
    check("しゃがんで降りると OFF になる", i["driver"] is None and "Tester" not in i["riders"], i["text"])
    c.shot("07-dismounted")
    server.cmd("tt destroy", 0.4)


def minecart(server, c):
    """ふつうのトロッコで D を押す"""
    server.cmd("tp Tester 0.5 -59.5 520.5 0 0", 1.0)
    server.cmd("summon minecart 0.5 -60 522", 0.6)
    server.cmd("ride Tester mount @e[type=minecart,limit=1,sort=nearest]", 0.8)
    time.sleep(1.2)
    m = c.mark()
    c.press("d", after=1.0)
    check("ふつうのトロッコで D を押すと ON になる", c.wait_chat("モード: ON", m))
    check("トロッコは列車に置き換わる", not server.passed("execute if entity @e[type=minecart]"))
    listed = server.until("tt list", lambda ls: any("1 両" in l for l in ls))
    name = next((re.search(r"(train\d+) 1 両", l).group(1) for l in listed if re.search(r"(train\d+) 1 両", l)), None)
    check("置き換えた列車がある", name is not None, listed)
    if name:
        server.cmd(f"tt select {name}", 0.3)
        i = server.info()
        check("置き換えた列車に座って運転している", i["driver"] == "Tester" and "Tester" in i["riders"], i["text"])
    c.shot("08-converted")
    c.shift()
    if name:
        server.cmd("tt destroy", 0.4)


def track_rules(server, c):
    """分岐器の看板、前の列車の手前で止まること、連結"""
    # 南へ続く本線と、z = 606 から東へ分かれる線路。分岐器の看板は東へ向ける
    server.cmd("fill 1 -60 606 12 -60 606 minecraft:rail[shape=east_west]", 0.5)
    sign(server, 606, "[+train]", "switcher", "e")

    def straight():
        server.cmd("setblock 0 -60 606 minecraft:rail[shape=north_south]", 0.3)

    def ride(z):
        server.cmd(f"tt create 1 iron_block at 0 -60 {z} world south", 0.5)
        server.cmd(f"tp Tester 0.5 -59.5 {z - 3}.5 0 0", 1.0)
        c.chat("/tt enter")
        time.sleep(1.0)
        check(f"(準備) z={z} の列車に座る", "Tester" in server.info()["riders"])
        time.sleep(1.1)
        c.press("d", after=0.4)
        check(f"(準備) z={z} の列車で ON", server.info()["driver"] == "Tester")

    def beyond_junction():
        info = server.info()
        return info if info.get("z", 0) > 609 or info.get("x", 0) > 1.5 else None

    straight()
    ride(592)
    c.tap("w", 3)
    i = wait_until(beyond_junction, 15, 0.2)
    check("マスコン ON は分岐器の看板を無視して線路の形のまま進む", i is not None and i["x"] < 1 and i["z"] > 609, i and i["text"])
    check("マスコン ON では分岐器の形が変わらない", server.passed("execute if block 0 -60 606 minecraft:rail[shape=north_south]"))
    stop_with_eb(server, c)
    c.shift()
    server.cmd("tt destroy", 0.4)

    straight()
    server.cmd("tt create 1 iron_block at 0 -60 592 world south", 0.4)
    server.cmd("tt launch 0.3", 0.2)
    i = wait_until(beyond_junction, 15, 0.2)
    check("マスコン OFF なら分岐器の看板のとおりに曲がる", i is not None and i["x"] > 1.5, i and i["text"])
    server.cmd("tt destroy", 0.4)
    straight()
    server.cmd("setblock 0 -62 606 minecraft:dirt", 0.3)

    # 前の列車の手前で止まる
    server.cmd("tt create 1 iron_block at 0 -60 615 world south", 0.5)
    ride(575)
    c.tap("w", 7)

    def stopped():
        info = server.info()
        return info if info["speed"] == 0.0 and info.get("z", 0) > 590 else None

    i = wait_until(stopped, 20, 0.2)
    check("マスコン ON でも前の列車の手前で止まる", i is not None and i["z"] < 614.3, i and i["text"])
    c.shot("09-stopped-before-train")

    # 連結
    server.cmd("tt property coupling true", 0.3)
    me = re.search(r"\] (train\d+) \(", server.info()["text"])
    names = re.findall(r"(train\d+) 1 両", "\n".join(server.until("tt list", lambda ls: any("1 両" in l for l in ls))))
    others = [n for n in names if not me or n != me.group(1)]
    if others and me:
        server.cmd(f"tt select {others[0]}", 0.2)
        server.cmd("tt property coupling true", 0.3)
        server.cmd(f"tt select {me.group(1)}", 0.2)
    check("ぶつかると連結する", wait_until(lambda: any("2 両" in l for l in server.cmd("tt list", 0.3)), 15, 0.3))
    joined = re.search(r"(train\d+) 2 両", "\n".join(server.until("tt list", lambda ls: any("2 両" in l for l in ls))))
    if joined:
        server.cmd(f"tt select {joined.group(1)}", 0.2)
        i = server.info()
        check("連結するとマスコンは OFF になる", i["driver"] is None, i["text"])
    c.shift()
    server.cmd("tt destroy", 0.5)


def visuals(server, c):
    """TrainCarts から引き継いだ車体の見た目 (撮影した画面を目で確かめる) と、それに乗ること"""
    server.cmd("tt spawn dotted.train at 0 -60 530 world south", 1.0)
    server.cmd("tp Tester 7.5 -58 530.5 90 8", 2.0)
    time.sleep(1.5)
    c.shot("10-dotted-side")
    server.cmd("tp Tester 0.5 -55 524.5 0 30", 2.0)
    time.sleep(1.0)
    c.shot("11-dotted-front")
    server.cmd("tt destroy", 0.4)
    server.cmd("tt spawn schemcart at 0 -60 530 world south", 1.0)
    server.cmd("tp Tester 5.5 -58.5 530.5 90 8", 2.0)
    time.sleep(1.0)
    c.shot("12-schemcart")
    server.cmd("tt destroy", 0.4)
    server.cmd("tt spawn chestcart at 0 -60 530 world south", 1.0)
    server.cmd("tp Tester 3.5 -59 530.5 90 12", 2.0)
    time.sleep(1.0)
    c.shot("13-chestcart")
    server.cmd("tt destroy", 0.4)

    server.cmd("tt spawn dotted.train at 0 -60 530 world south", 1.0)
    server.cmd("tp Tester 0.5 -59.5 523.5 0 0", 1.0)
    c.chat("/tt enter")
    time.sleep(1.0)
    i = server.info()
    check("引き継いだ車体の座席に座る", "Tester" in i["riders"], i["text"])
    time.sleep(1.1)
    m = c.mark()
    c.press("d", after=0.4)
    check("引き継いだ列車でも D で ON (allowManualMovement に関係なく)", c.wait_chat("モード: ON", m))
    c.tap("w", 2)
    time.sleep(1.0)
    i = server.info()
    check("引き継いだ列車を運転できる", i["notch"] == "P2" and (i["speed"] or 0) > 0.05, i["text"])
    c.shot("14-dotted-riding")
    stop_with_eb(server, c)
    start = len(server.lines)
    # 押しているあいだに撮る (「降りられません」が HUD に消されていないことを画面で確かめる)
    if c.shift_down():
        time.sleep(0.5)
        c.shot("15-cannot-exit")
        c.shift_up()
    time.sleep(0.5)
    check("(しゃがみがサーバーに届く)", server.log_has("shift=true", start))
    check("allowPlayerExit: false の列車は降りられない", "Tester" in server.info()["riders"], server.info()["text"])
    server.cmd("tt destroy", 0.6)


def two_players(server, c):
    """1 本の列車を運転できるのは 1 人だけ"""
    second = Client("Second", c.version)
    second.start()
    try:
        if not server.wait_for("Second joined the game", 120):
            # たまにクライアントの起動が止まるので、1 度だけ起動し直す
            second.stop()
            second.start()
            if not server.wait_for("Second joined the game", 150):
                check("2 人目のクライアントが入る", False)
                return
        check("2 人目のクライアントが入る", True)
        time.sleep(8)
        server.cmd("tt create 2 iron_block at 0 -60 560 world south", 1.0)
        server.cmd("tp Tester 0.5 -59.5 557.5 0 0", 1.0)
        c.focus()
        c.chat("/tt enter")
        time.sleep(0.8)
        server.cmd("tp Second 0.5 -59.5 556 0 0", 1.0)
        second.focus()
        second.chat("/tt enter")
        time.sleep(1.0)
        i = server.info()
        check("2 人とも同じ列車に座る", "Tester" in i["riders"] and "Second" in i["riders"], i["text"])
        time.sleep(1.1)
        c.focus()
        c.press("d", after=0.4)
        check("1 人目が ON", server.info()["driver"] == "Tester")
        second.focus()
        m = second.mark()
        second.press("d", after=0.4)
        check("2 人目には操作中と出る", second.wait_chat("他のプレイヤーが操作中です", m))
        check("運転は 1 人目のまま", server.info()["driver"] == "Tester")
        second.shot("16-second-denied")
        second.press("w")
        check("2 人目の W は効かない", server.info()["notch"] == "N", server.info()["notch"])
        c.shift()
        check("1 人目が降りると OFF", server.info()["driver"] is None, server.info()["text"])
        time.sleep(1.1)
        second.focus()
        m = second.mark()
        second.press("d", after=0.4)
        check("その後は 2 人目が ON にできる", second.wait_chat("モード: ON", m))
        second.shift()
        server.cmd("tt destroy", 0.4)
    finally:
        second.stop()


def detector_slow(server):
    """検知の看板のレバーは、列車がいるあいだ入り、出ると切れる。列車を消しても切れる"""
    powered = "execute if block 1 -62 180 minecraft:lever[powered=true]"
    off = "execute if block 1 -62 180 minecraft:lever[powered=false]"
    check("(準備) レバーは切れている", server.passed(off))
    server.cmd("tt create 1 iron_block at 0 -60 170 world south", 0.4)
    server.cmd("tt launch 0.1", 0.2)
    check("列車がいるあいだレバーが入る", wait_until(lambda: server.passed(powered), 15, 0.2))
    check("列車が出るとレバーが切れる", wait_until(lambda: server.passed(off), 15, 0.2))
    server.cmd("tt destroy", 0.4)
    server.cmd("tt create 1 iron_block at 0 -60 170 world south", 0.4)
    server.cmd("tt launch 0.1", 0.2)
    check("(準備) もう 1 度入る", wait_until(lambda: server.passed(powered), 15, 0.2))
    server.cmd("tt destroy", 0.4)
    check("列車を消すとレバーが切れる", server.passed(off))


def spawn_sign_restart(server, java, paper):
    """再起動のあとに、信号が入ったままの spawn の看板が列車を出さないこと。新しいサーバーを返す"""
    sign(server, 590, "[+train]", "spawn", "2m")
    server.cmd("setblock 1 -62 590 minecraft:redstone_block", 1.0)
    lines = server.until("tt list", lambda ls: any("2 両" in l for l in ls))
    check("信号が入ると spawn の看板が 2m を出す", any("2 両" in l for l in lines), lines)
    server.cmd("tt destroyall", 0.5)
    server.stop()
    server = common.Server(java, paper)
    if not server.wait_for("Done (", 240):
        check("再起動", False)
        return server
    time.sleep(2)
    server.cmd("setblock 0 -62 591 minecraft:stone", 1.0)
    server.cmd("setblock 0 -62 589 minecraft:stone", 1.0)
    lines = server.until("tt list", lambda ls: any("本:" in l for l in ls))
    check("再起動後に、信号が入ったままの看板は列車を出さない", any("列車 0 本" in l for l in lines), lines)
    return server


def build_track(server):
    for command in ("difficulty peaceful", "time set noon",
                    "forceload add -16 -16 16 640", "fill 0 -60 0 0 -60 620 minecraft:rail",
                    "setblock 0 -60 30 minecraft:powered_rail[shape=north_south,powered=false]",
                    # 看板の隣にレバー。西を向けるので、東のブロック (土) に付く
                    "setblock 1 -62 180 minecraft:lever[face=wall,facing=west,powered=false]"):
        server.cmd(command, 1.5 if command.startswith("forceload") else 0.6)
    sign(server, 60, "[+train]", "announce", "passtest")
    sign(server, 100, "[+train]", "station", "5")
    sign(server, 140, "[+train]", "blocker")
    sign(server, 180, "[+train]", "detector")


def main():
    common.utf8_output()
    if sys.platform != "win32":
        print("確認を始められません: e2e.py は Windows だけで動きます。サーバーだけの確認は smoke.py で行えます", flush=True)
        return common.EXIT_NOT_READY
    try:
        import PIL  # noqa: F401
        import psutil  # noqa: F401
    except ImportError as error:
        print(f"確認を始められません: {error}。pip install portablemc pillow psutil を実行してください", flush=True)
        return common.EXIT_NOT_READY
    if not shutil.which("portablemc"):
        print("確認を始められません: portablemc がありません。pip install portablemc pillow psutil を実行してください", flush=True)
        return common.EXIT_NOT_READY
    try:
        version = common.minecraft_version()
        java = common.find_java()
        paper = common.paper_jar(version)
        plugin = common.prepare_server(debug=True)
    except common.NotReady as error:
        print(f"確認を始められません: {error}", flush=True)
        return common.EXIT_NOT_READY
    print(f"Paper: {paper.name} / プラグイン: {plugin.name} / クライアント: {MC_DIR}", flush=True)
    shutil.rmtree(SHOTS, ignore_errors=True)
    MC_DIR.mkdir(parents=True, exist_ok=True)

    server = common.Server(java, paper)
    c = Client("Tester", version)
    try:
        if not server.wait_for("Done (", 400):
            print("\n".join(server.lines[-40:]), flush=True)
            check("サーバーが起動する", False)
            return common.finish("e2e")
        time.sleep(3)
        build_track(server)
        c.start()
        # 初回はクライアントのファイルを取ってくるので長く待つ
        if not server.wait_for("Tester joined the game", 900):
            check("クライアントがサーバーに入る", False, (c.dir / "launcher-output.log").read_text(encoding="utf-8", errors="replace")[-600:])
            return common.finish("e2e")
        check("本物のクライアントがサーバーに入る", True)
        if not wait_until(lambda: c.window() is not None, 60):
            check("ウィンドウが見つかる", False)
            return common.finish("e2e")
        time.sleep(12)
        c.shot("00-joined")

        for scenario in (driving, off_mode, player_toggle, minecart, track_rules, visuals, two_players):
            print(f"\n== {scenario.__name__}", flush=True)
            print(f"  手前に出せた: {c.focus()}", flush=True)
            try:
                scenario(server, c)
            except Exception as error:
                traceback.print_exc()
                check(f"{scenario.__name__} が最後まで動く", False, error)
        c.stop()
        print("\n== detector_slow", flush=True)
        detector_slow(server)
        print("\n== spawn_sign_restart", flush=True)
        server = spawn_sign_restart(server, java, paper)
    finally:
        c.stop()
        server.stop()
        server.kill()
    problems = server.problems()
    check("TORO-Train の警告とエラーがない", not problems, "\n".join(problems[:10]))
    print(f"\n撮影した画面: {SHOTS}", flush=True)
    return common.finish("e2e")


if __name__ == "__main__":
    sys.exit(main())
