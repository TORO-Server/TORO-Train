"""サーバーだけの確認。本物の Paper を起動し、TrainCarts の見本データの引き継ぎと、列車の出現・保存・再起動を確かめる。
クライアントがいらないので、どの OS でも、画面がなくても動く。プレイヤーの操作 (マスコンのキー) は e2e.py で確かめる。

使い方: ./gradlew build のあとで python tools/verify/smoke.py
必要なもの: Python 3、Java 25 以上、初回はインターネット (Paper を取ってくる)
"""
import re
import sys
import time

import common
from common import check


def spawned(server, name, expect):
    """保存した列車を出して、/tt info に expect が全部あるか確かめて消す"""
    server.cmd(f"tt spawn {name} at 0 -60 10 world south", 0.8)
    info = server.info()
    check(f"{name} を出せる", all(text in info["text"] for text in expect), info["text"])
    server.cmd("tt destroy", 0.5)


def first_run(server):
    log = "\n".join(line for line in server.lines if "TrainCarts" in line)
    check("引き継ぎの数", "保存した列車 6 本、走っていた列車 1 本、モデル 2 個、経路 1 個、行き先 1 個" in log, log)
    check("見つからないスケマティックを報告する", "doesnotexist" in log, log)
    check("描けない取り付けを報告する", "ENTITY:PIG" in log, log)

    server.cmd("forceload add -16 -16 32 96", 1.5)
    server.cmd("fill 0 -60 0 0 -60 90 minecraft:rail", 0.8)

    saved = "\n".join(server.cmd("tt saved", 0.6))
    for name in ("aliastrain", "chestcart", "dotted.train", "missingschem", "moduletrain", "schemcart"):
        check(f"保存した列車 {name} がある", name in saved, saved)
    check("経路を引き継ぐ (名前の . を保つ)", "line.1" in "\n".join(server.cmd("tt routes", 0.6)))
    check("行き先を引き継ぐ (分岐器の印は除く)", "行き先: 東京" in "\n".join(server.cmd("tt dests", 0.6)))
    models = "\n".join(server.cmd("tt models", 0.6))
    check("スケマティックをモデルにする", "tc-legacybox" in models and "tc-spongebox" in models, models)

    data = common.SERVER_DIR / "plugins" / "TORO-Train"
    for name, blocks in (("tc-legacybox", 3), ("tc-spongebox", 4)):
        text = (data / "models" / f"{name}.yml").read_text(encoding="utf-8")
        check(f"{name} のブロックの数", f"blocks: {blocks}" in text, text)

    spawned(server, "dotted.train", ["取り付けの組み合わせ (8 個)", "行き先: 東京", "東京 → 大阪"])
    spawned(server, "chestcart", ["トロッコ (chest)", "最高 58 km/h"])
    spawned(server, "schemcart", ["2 両", "ブロックのモデル tc-spongebox", "トロッコ (iron_block)"])
    spawned(server, "aliastrain", ["2 両", "1. トロッコ (chest)", "2. トロッコ (chest)"])
    spawned(server, "moduletrain", ["トロッコ (tnt)"])
    spawned(server, "missingschem", ["トロッコ (iron_block)"])
    spawned(server, "3m", ["3 両"])
    check("読めない名前は断る", "ありません" in "\n".join(server.cmd("tt spawn nonsense at 0 -60 10 world south", 0.6)))
    check("コンソールから /mascon は使えない", "プレイヤーのみ" in "\n".join(server.cmd("mascon off", 0.6)))

    # 設定と見た目
    server.cmd("tt spawn dotted.train at 0 -60 20 world south", 2.0)
    properties = "\n".join(server.cmd("tt property", 0.8))
    for expect in ("speedlimit = 0.6", "friction = 0.0", "waitdistance = 1.5", "coupling = true", "invincible = true", "manualcontrol = true"):
        check(f"設定を引き継ぐ: {expect}", expect in properties, properties)
    # 根 1 つ、石、木材、赤い羊毛、金、モデルの 3 ブロックと看板の板
    check("ブロックのディスプレイの数", server.count("@e[type=block_display]") == 9)
    check("アイテムのディスプレイの数 (アイテム 2 つと座席 2 つ)", server.count("@e[type=item_display]") == 4)
    check("文字のディスプレイの数 (TEXT と古い看板の文字)", server.count("@e[type=text_display]") == 2)

    # 走って、保存する
    server.cmd("tt launch 0.3", 3.0)
    info = server.info()
    check("列車が走る", info.get("z", 0) > 21 and info["kmh"], info["text"])
    check("保存した経路を読み込める", "a → b" in "\n".join(server.cmd("tt route load line.1", 0.6)))
    check("負の速さは断る", "速さを書いて" in "\n".join(server.cmd("tt speed -1", 0.6)))
    server.cmd("tt saveall", 1.0)

    text = (data / "mascon.yml").read_text(encoding="utf-8")
    check("MasconCart の /mascon off を引き継ぐ", "00000000-0000-0000-0000-000000000002" in text, text)
    text = (data / "traincarts-import.yml").read_text(encoding="utf-8")
    check("走っていた列車はトロッコが読み込まれるまで待つ", "train9:" in text and "imported: true" in text, text)


def second_run(server):
    time.sleep(1)
    log = "\n".join(server.lines)
    check("2 回目は引き継がない", "TrainCarts から引き継ぎました" not in log)
    listed = "\n".join(server.cmd("tt list", 0.6))
    check("走っていた列車を読み込む", "列車 1 本" in listed, listed)
    time.sleep(1)
    check("読み込んだ列車の見た目を作り直す", server.count("@e[type=block_display]") == 9)
    check("読み込んだ列車は経路を保つ", "→ a" in listed, listed)
    saved = "\n".join(server.cmd("tt saved", 0.6))
    check("保存した列車は残る", "dotted.train" in saved, saved)
    server.cmd("tt destroyall", 0.8)


def run_server(java, paper, scenario):
    server = common.Server(java, paper)
    try:
        if not server.wait_for("Done (", 400):
            print("\n".join(server.lines[-40:]), flush=True)
            check("サーバーが起動する", False)
            return
        time.sleep(3)
        scenario(server)
    finally:
        server.stop()
        server.kill()
        problems = server.problems()
        check(f"{scenario.__name__} で TORO-Train の警告とエラーがない", not problems, "\n".join(problems[:10]))


def main():
    common.utf8_output()
    try:
        version = common.minecraft_version()
        java = common.find_java()
        paper = common.paper_jar(version)
        plugin = common.prepare_server()
    except common.NotReady as error:
        print(f"確認を始められません: {error}", flush=True)
        return common.EXIT_NOT_READY
    print(f"Paper: {paper.name} / プラグイン: {plugin.name} / Java: {java}", flush=True)
    run_server(java, paper, first_run)
    run_server(java, paper, second_run)
    return common.finish("smoke")


if __name__ == "__main__":
    sys.exit(main())
