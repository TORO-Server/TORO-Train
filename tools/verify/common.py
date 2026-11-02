"""smoke.py と e2e.py で共通の部品。試験用のサーバーを作って動かし、結果を記録する。

環境変数:
    TORO_VERIFY_DIR   作業フォルダ (既定: build/verify)。サーバー、クライアント、画面の撮影、結果を置く
    TORO_VERIFY_CACHE ダウンロードしたものを置くフォルダ (既定: ~/.toro-train-verify)
    TORO_VERIFY_JAVA  サーバーを動かす Java 25 以上の java (既定: JAVA_HOME、Gradle の JDK、PATH の順に探す)
    TORO_VERIFY_PORT  サーバーのポート (既定: 25599)
"""
import gzip
import json
import os
import re
import shutil
import struct
import subprocess
import sys
import threading
import time
import urllib.request
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
WORK = Path(os.environ.get("TORO_VERIFY_DIR", REPO / "build" / "verify"))
CACHE = Path(os.environ.get("TORO_VERIFY_CACHE", Path.home() / ".toro-train-verify"))
SERVER_DIR = WORK / "server"
PORT = int(os.environ.get("TORO_VERIFY_PORT", "25599"))
FILL_BUILDS = "https://fill.papermc.io/v3/projects/paper/versions/{version}/builds"

# 終了コード: 0 はすべて合格、1 は不合格、2 は確認を始められなかった (環境が足りないなど)
EXIT_OK, EXIT_FAILED, EXIT_NOT_READY = 0, 1, 2


class NotReady(Exception):
    """確認を始められない。理由をそのまま表示する"""


# ---------- 結果 ----------

results = []


def check(name, ok, detail=""):
    results.append({"name": name, "ok": bool(ok), "detail": str(detail)[:600]})
    print(f"[{'OK' if ok else 'NG'}] {name}" + (f"  ({detail})" if detail and not ok else ""), flush=True)


def finish(name):
    """結果を WORK/<name>-results.json に書き、終了コードを返す"""
    failed = [r for r in results if not r["ok"]]
    WORK.mkdir(parents=True, exist_ok=True)
    path = WORK / f"{name}-results.json"
    path.write_text(json.dumps(results, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"\n結果: OK {len(results) - len(failed)} / NG {len(failed)} ({path})", flush=True)
    for r in failed:
        print(f"NG: {r['name']}: {r['detail']}", flush=True)
    return EXIT_FAILED if failed or not results else EXIT_OK


def utf8_output():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    sys.stderr.reconfigure(encoding="utf-8", errors="replace")


def wait_until(fn, timeout, step=0.3):
    end = time.time() + timeout
    while time.time() < end:
        value = fn()
        if value:
            return value
        time.sleep(step)
    return None


# ---------- 必要なものを探す ----------

def minecraft_version():
    """build.gradle.kts の paper-api の版から、Minecraft のバージョンを読む"""
    text = (REPO / "build.gradle.kts").read_text(encoding="utf-8")
    match = re.search(r'paper-api:([\d.]+)\.build', text)
    if not match:
        raise NotReady("build.gradle.kts から Minecraft のバージョンを読めません")
    return match.group(1)


def java_major(java):
    try:
        output = subprocess.run([str(java), "-version"], capture_output=True, text=True, errors="replace", timeout=30).stderr
    except OSError:
        return 0
    match = re.search(r'version "(\d+)', output)
    return int(match.group(1)) if match else 0


def find_java():
    names = ["java.exe", "java"]
    candidates = []
    if os.environ.get("TORO_VERIFY_JAVA"):
        candidates.append(Path(os.environ["TORO_VERIFY_JAVA"]))
    if os.environ.get("JAVA_HOME"):
        candidates += [Path(os.environ["JAVA_HOME"]) / "bin" / n for n in names]
    for jdk in sorted((Path.home() / ".gradle" / "jdks").glob("*"), reverse=True):
        candidates += [jdk / "bin" / n for n in names]
    if shutil.which("java"):
        candidates.append(Path(shutil.which("java")))
    for java in candidates:
        if java.exists() and java_major(java) >= 25:
            return java
    raise NotReady("Java 25 以上が見つかりません。TORO_VERIFY_JAVA に java の場所を指定してください")


def plugin_jar():
    jars = [j for j in (REPO / "build" / "libs").glob("TORO-Train-*.jar") if not j.name.endswith("-thin.jar")]
    if not jars:
        raise NotReady("プラグインの jar がありません。先に ./gradlew build を実行してください")
    return max(jars, key=lambda j: j.stat().st_mtime)


def paper_jar(version):
    """その版のいちばん新しい安定版の Paper を取ってくる。取れなければ、前に取ったものを使う"""
    folder = CACHE / "paper"
    folder.mkdir(parents=True, exist_ok=True)
    cached = sorted(folder.glob(f"paper-{version}-*.jar"), key=lambda j: j.stat().st_mtime)
    try:
        request = urllib.request.Request(FILL_BUILDS.format(version=version), headers={"User-Agent": "TORO-Train-verify"})
        with urllib.request.urlopen(request, timeout=30) as response:
            builds = json.load(response)
        stable = [b for b in builds if b.get("channel") == "STABLE"]
        latest = max(stable, key=lambda b: b["id"])
        target = folder / f"paper-{version}-{latest['id']}.jar"
        if not target.exists():
            print(f"Paper {version} build {latest['id']} を取ってきます", flush=True)
            url = latest["downloads"]["server:default"]["url"]
            request = urllib.request.Request(url, headers={"User-Agent": "TORO-Train-verify"})
            part = target.with_suffix(".part")
            with urllib.request.urlopen(request, timeout=300) as response, open(part, "wb") as out:
                shutil.copyfileobj(response, out)
            part.replace(target)
        return target
    except Exception as error:
        if cached:
            print(f"Paper の情報を取れなかったので、前に取ったものを使います ({error})", flush=True)
            return cached[-1]
        raise NotReady(f"Paper を取ってこられません: {error}")


# ---------- TrainCarts の見本データ ----------

def _name(text):
    data = text.encode("utf-8")
    return struct.pack(">H", len(data)) + data


def _tag(kind, name, payload):
    return bytes([kind]) + _name(name) + payload


def _short(name, value):
    return _tag(2, name, struct.pack(">h", value))


def _int(name, value):
    return _tag(3, name, struct.pack(">i", value))


def _bytes(name, value):
    return _tag(7, name, struct.pack(">i", len(value)) + bytes(value))


def _string(name, value):
    return _tag(8, name, _name(value))


def _ints(name, value):
    return _tag(11, name, struct.pack(">i", len(value)) + b"".join(struct.pack(">i", v) for v in value))


def _compound(name, *items):
    return _tag(10, name, b"".join(items) + b"\0")


def _compounds(name, items):
    body = b"".join(b"".join(item) + b"\0" for item in items)
    return _tag(9, name, bytes([10 if items else 0]) + struct.pack(">i", len(items)) + body)


def _write_nbt(path, root):
    path.parent.mkdir(parents=True, exist_ok=True)
    with gzip.open(path, "wb") as out:
        out.write(root)


DEFAULT_PROPERTIES = """default:
  speedLimit: 0.6
  slowDown:
    friction: false
    gravity: true
  allowManualMovement: false
  collision:
    train: LINK
  wait:
    distance: 1.5
  keepChunksLoaded: false
"""

SAVED_TRAINS = """chestcart:
  speedLimit: 0.8
  claims:
  - someone 00000000-0000-0000-0000-000000000001
  carts:
    0:
      entityType: MINECART_CHEST
      model:
        type: ENTITY
        entityType: MINECART_CHEST
        position: {}
        attachments:
          0:
            type: SEAT
            position: {}
dotted.train:
  invincible: true
  carts:
    0:
      flipped: true
      destination: '東京'
      route:
      - '東京'
      - '大阪'
      tags:
      - express
      allowPlayerExit: false
      model:
        type: EMPTY
        position: {}
        physical:
          cartLength: 6.0
          wheelDistance: 4.0
        attachments:
          0:
            type: BLOCK_DISPLAY
            blockData: 'minecraft:stone'
            position: {posY: 0.5, sizeX: 2.0, sizeY: 1.0, sizeZ: 4.0, rotY: 90.0}
          1:
            type: ITEM
            item:
              ==: org.bukkit.inventory.ItemStack
              v: 3337
              type: DIAMOND
            position: {transform: HEAD, posZ: 1.0}
          2:
            type: ITEM
            item:
              ==: org.bukkit.inventory.ItemStack
              v: 3337
              type: EMERALD
            position: {transform: DISPLAY_FIXED, posZ: -1.0, sizeX: 2.0, sizeY: 2.0, sizeZ: 2.0}
          3:
            type: TEXT
            text: '&aHello'
            position: {posY: 2.0}
          4:
            type: SEAT
            position: {posY: 1.0, posZ: 2.0}
          5:
            type: SEAT
            position: {posZ: -2.0}
          6:
            type: MODEL
            modelName: roof
            position: {posY: 2.0}
          7:
            type: WE_SCHEMATIC
            schematic: legacybox
            position: {posZ: 3.0}
          8:
            type: SEQUENCER
            position: {}
            attachments:
              0:
                type: BLOCK_DISPLAY
                blockData: 'minecraft:gold_block'
                position: {posY: 3.0}
          9:
            type: SOUND
            position: {}
          10:
            type: ENTITY
            entityType: PIG
            position: {}
schemcart:
  carts:
    0:
      model:
        type: WE_SCHEMATIC
        schematic: spongebox
        position: {posY: 0.5}
        attachments:
          0:
            type: SEAT
            position: {posY: 1.0}
      entityType: MINECART
    1:
      entityType: MINECART
aliastrain:
  spawnPattern: 2chestcart
missingschem:
  carts:
    0:
      model:
        type: WE_SCHEMATIC
        schematic: doesnotexist
        position: {}
"""

MODULE_TRAIN = """moduletrain:
  carts:
    0:
      entityType: MINECART_TNT
"""

# roof と moduleroof は互いを含むので、たどるのが止まることも確かめられる
SAVED_MODEL = """roof:
  type: BLOCK_DISPLAY
  blockData: 'minecraft:oak_planks'
  position: {sizeX: 3.0, sizeY: 0.2, sizeZ: 3.0}
  attachments:
    0:
      type: MODEL
      modelName: moduleroof
      position: {posY: 1.0}
"""

MODULE_MODEL = """moduleroof:
  type: BLOCK_DISPLAY
  blockData: 'minecraft:red_wool'
  position: {}
  attachments:
    0:
      type: MODEL
      modelName: roof
      position: {}
"""

LIVE_TRAINS = """train9:
  speedLimit: 0.5
  carts:
    61dfd820-3086-4169-a457-07fc1d4d420a:
      owners:
      - nobodyknown
      model:
        type: ENTITY
        entityType: MINECART
        position: {}
"""


def write_fixtures():
    plugins = SERVER_DIR / "plugins"
    tc = plugins / "Train_Carts"
    (tc / "savedTrainModules").mkdir(parents=True, exist_ok=True)
    (tc / "savedModelModules").mkdir(parents=True, exist_ok=True)
    schematics = plugins / "WorldEdit" / "schematics"

    # 古い MCEdit 形式。石、木材、空気、文字つきの看板
    _write_nbt(schematics / "legacybox.schematic", _compound(
        "Schematic",
        _short("Width", 2), _short("Height", 1), _short("Length", 2), _string("Materials", "Alpha"),
        _bytes("Blocks", [1, 5, 0, 63]), _bytes("Data", [0, 0, 0, 0]),
        _compounds("Entities", []),
        _compounds("TileEntities", [[
            _string("id", "Sign"), _int("x", 1), _int("y", 0), _int("z", 1),
            _string("Text1", '{"text":"legacy"}'), _string("Text2", '""'), _string("Text3", '""'), _string("Text4", '""'),
        ]]),
    ))
    # Sponge 形式の版 2
    _write_nbt(schematics / "spongebox.schem", _compound(
        "Schematic",
        _int("Version", 2), _int("DataVersion", 3465),
        _short("Width", 1), _short("Height", 2), _short("Length", 3),
        _int("PaletteMax", 3),
        _compound("Palette", _int("minecraft:air", 0), _int("minecraft:stone", 1), _int("minecraft:oak_sign[rotation=0,waterlogged=false]", 2)),
        _bytes("BlockData", [1, 1, 1, 0, 2, 0]),
        _compounds("BlockEntities", [[_string("Id", "minecraft:sign"), _ints("Pos", [0, 1, 1])]]),
    ))

    for path, text in (
        (tc / "DefaultTrainProperties.yml", DEFAULT_PROPERTIES),
        (tc / "SavedTrainProperties.yml", SAVED_TRAINS),
        (tc / "savedTrainModules" / "mod1.yml", MODULE_TRAIN),
        (tc / "SavedModels.yml", SAVED_MODEL),
        (tc / "savedModelModules" / "m.yml", MODULE_MODEL),
        (tc / "routes.yml", "line.1:\n- a\n- b\n"),
        (tc / "TrainProperties.yml", LIVE_TRAINS),
    ):
        path.write_text(text, encoding="utf-8")

    # destinations.dat: 行き先「東京」(分岐器の印つき) と、名前のない分岐器
    data = bytearray(struct.pack(">i", 2))
    for names, x in (("東京\n::traincarts::switchable::", 1), ("", 2)):
        for text in (names, "world"):
            data += _name(text)
        data += struct.pack(">iii", x, -60, 5)
    data += struct.pack(">i", 0) * 2
    (tc / "destinations.dat").write_bytes(zlib.compress(bytes(data)))

    mascon = plugins / "MasconCart"
    mascon.mkdir(parents=True, exist_ok=True)
    (mascon / "config.yml").write_text("disabledPlayers:\n- 00000000-0000-0000-0000-000000000002\n", encoding="utf-8")


def prepare_server(debug=False):
    """試験用のサーバーを作り直す。Paper が展開したゲームのファイルは残し、ワールドとプラグインのデータだけ消す"""
    plugin = plugin_jar()
    for folder in ("world", "world_nether", "world_the_end", "plugins", "logs"):
        shutil.rmtree(SERVER_DIR / folder, ignore_errors=True)
    (SERVER_DIR / "plugins").mkdir(parents=True, exist_ok=True)
    (SERVER_DIR / "eula.txt").write_text("eula=true\n", encoding="ascii")
    # 26.3 の既定はホワイトリストが有効で、そのままだと試験のクライアントが入れない
    (SERVER_DIR / "server.properties").write_text("\n".join([
        "online-mode=false", "enforce-secure-profile=false", "white-list=false", "enforce-whitelist=false",
        f"server-port={PORT}", "level-type=minecraft\\:flat", "spawn-protection=0", "max-players=4",
        "generate-structures=false", "view-distance=4", "simulation-distance=4",
    ]) + "\n", encoding="ascii")
    shutil.copy(plugin, SERVER_DIR / "plugins" / "TORO-Train.jar")
    write_fixtures()
    if debug:
        # config.yml の値は同梱の既定値のまま、看板と入力のログだけ出す
        data = SERVER_DIR / "plugins" / "TORO-Train"
        data.mkdir(parents=True, exist_ok=True)
        (data / "config.yml").write_text("debug: true\n", encoding="utf-8")
    return plugin


def strip(line):
    return re.sub(r"\x1b\[[0-9;]*m", "", line)


class Server:
    """サーバーのプロセスとコンソール"""

    def __init__(self, java, paper):
        self.lines = []
        self.p = subprocess.Popen(
            [str(java), "-Xmx2G", "-Dfile.encoding=UTF-8", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8", "-jar", str(paper), "--nogui"],
            cwd=SERVER_DIR, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            encoding="utf-8", errors="replace", bufsize=1,
        )
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for line in self.p.stdout:
            self.lines.append(strip(line.rstrip("\n")))

    def wait_for(self, text, timeout):
        end = time.time() + timeout
        while time.time() < end:
            if any(text in line for line in self.lines):
                return True
            if self.p.poll() is not None:
                return False
            time.sleep(0.3)
        return False

    def _send(self, text):
        self.p.stdin.write(text + "\n")
        self.p.stdin.flush()

    def cmd(self, text, wait=0.5):
        """コマンドを送り、wait 秒のあいだに出た行を返す"""
        start = len(self.lines)
        self._send(text)
        time.sleep(wait)
        return self.lines[start:]

    def until(self, text, done, timeout=4.0):
        """コマンドを送り、done(出た行) が true になるまで待つ。コンソールの応答は遅れることがあるので、結果を読むときはこちらを使う"""
        start = len(self.lines)
        self._send(text)
        end = time.time() + timeout
        while time.time() < end:
            if done(self.lines[start:]):
                break
            time.sleep(0.05)
        time.sleep(0.1)
        return self.lines[start:]

    def passed(self, text):
        """execute if ... の結果"""
        lines = self.until(text, lambda ls: any("Test passed" in l or "Test failed" in l for l in ls))
        return any("Test passed" in l for l in lines)

    def count(self, selector):
        """セレクターに当たるエンティティの数"""
        lines = self.until(f"execute if entity {selector}", lambda ls: any("Test passed" in l or "Test failed" in l for l in ls))
        for line in lines:
            match = re.search(r"Count: (\d+)", line)
            if match:
                return int(match.group(1))
        return 0

    def info(self):
        """/tt info の結果を読む"""
        lines = self.until("tt info", lambda ls: any("乗客:" in l or "列車が" in l or "選んでください" in l for l in ls))
        text = "\n".join(lines)
        out = {"text": text}
        match = re.search(r"先頭: ([-\d.]+), ([-\d.]+), ([-\d.]+)", text)
        if match:
            out["x"] = float(match.group(1))
            out["z"] = float(match.group(3))
        match = re.search(r"マスコン: (\S+) / (\S+) / ([\d.]+)", text)
        out["driver"], out["notch"], out["speed"] = (match.group(1), match.group(2), float(match.group(3))) if match else (None, None, None)
        match = re.search(r"速さ: (\d+) km/h", text)
        out["kmh"] = int(match.group(1)) if match else None
        match = re.search(r"動作: (.*)", text)
        out["action"] = match.group(1) if match else ""
        match = re.search(r"乗客: (.*)", text)
        out["riders"] = match.group(1).strip() if match else ""
        return out

    def log_has(self, text, since=0):
        return any(text in line for line in self.lines[since:])

    def problems(self):
        """TORO-Train の警告とエラー"""
        return [line for line in self.lines
                if ("torosaba" in line or "[TORO-Train]" in line) and ("WARN" in line or "ERROR" in line or "Exception" in line)]

    def stop(self):
        if self.p.poll() is not None:
            return
        try:
            self._send("stop")
            self.p.wait(120)
        except (subprocess.TimeoutExpired, OSError, ValueError):
            self.p.kill()

    def kill(self):
        if self.p.poll() is None:
            self.p.kill()
