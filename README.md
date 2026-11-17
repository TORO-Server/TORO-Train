# TORO-Train

Display エンティティ (Minecraft のブロックや文字を、位置や大きさを自由に変えて見せる仕組み) で描く列車の Paper 用プラグインです。TrainCarts と同じように、線路と看板で駅・分岐・発車などを制御でき、ワールドのブロックをそのまま列車にして走らせることもできます。

TrainCarts のコードは使わず、Kotlin で一から作っています。バニラのトロッコは使わず、列車の位置はプラグインの中で持ちます。

## 動作環境

Paper と Java 25 です。Paper の対象バージョンは `build.gradle.kts` が正本です。

## 始め方

1. `./gradlew build` で `build/libs/` に JAR ができます
2. JAR を `plugins/` に入れて起動します
3. トロッコのアイテムを線路に置くと列車になります。`/tt create` でも出せます

## 関連文書

- 遊び方、看板、設定、TrainCarts から移る方法: [docs/manual.md](docs/manual.md)
- 開発者向けの手引き: [AGENTS.md](AGENTS.md)
- 文章の書き方: [WRITING-STYLE.md](https://github.com/TORO-Server/TORO-Website/blob/main/docs/WRITING-STYLE.md)
