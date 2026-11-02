# TORO-Train の作業の手引き

AI エージェントと開発者向けです。遊び方と設定は [README.md](README.md) にあります。

TORO-Train は Display エンティティで描く列車の Paper プラグイン (Kotlin) です。TrainCarts の代わりで、TrainCarts のデータと看板、MasconCart の操作を引き継ぎます。バニラのトロッコは使いません。

- Paper 26.3 / Java 25 / Kotlin
- リモート: `TORO-Server/TORO-Train` (非公開)

## コマンド

```bash
./gradlew build                  # ビルドとユニットテスト。build/libs/TORO-Train-<版>.jar ができる
./gradlew test                   # ユニットテストだけ
python tools/verify/smoke.py     # 本物の Paper で確認 (どの OS でも、画面なしでも動く。1 分くらい)
python tools/verify/e2e.py       # 本物のクライアントをつないで確認 (Windows のみ。5 分くらい)
```

`tools/verify` の 2 つは `./gradlew build` のあとで実行します。`smoke.py` は OS に依存しない作りですが、実際に動かしたのは今のところ Windows だけです。

| 終了コード | 意味 |
| --- | --- |
| 0 | すべて合格 |
| 1 | 不合格 (`build/verify/<名前>-results.json` と標準出力の `NG:` の行を見る) |
| 2 | 確認を始められなかった (Java、jar、portablemc がないなど) |

## 変えたら確かめること

| 変えたもの | 確かめ方 |
| --- | --- |
| 計算だけの部分 (`MasconProfile`、`SpawnPattern`、`TrainCartsFormat`、`TrackPath`) | `./gradlew test` |
| TrainCarts の引き継ぎ、スケマティック、車体のモデル、保存と読み込み | `smoke.py` |
| 運転の操作、看板の働き方、乗り降り、画面の下の表示 | `e2e.py` |
| 見た目 (車体、部品の位置) | `e2e.py` のあとで `build/verify/shots/` の画面を目で確かめる |

コンパイルとユニットテストだけでは、Paper の API の実際の動きは分かりません。プラグインの動きを変えたら、合う確認を実行してから「動く」と報告してください。実行できなかった確認は、そう書いてください。

`e2e.py` は Minecraft のウィンドウを開いて、ときどき手前に出します。使っている人の画面を取るので、頼まれていないのに何度も走らせないでください。

### 環境変数

| 名前 | 内容 | 既定 |
| --- | --- | --- |
| `TORO_VERIFY_DIR` | サーバー、クライアント、画面、結果を置く | `build/verify` |
| `TORO_VERIFY_CACHE` | ダウンロードした Paper などを置く | `~/.toro-train-verify` |
| `TORO_VERIFY_MC_DIR` | クライアントのファイル (初回は数百 MB を取ってくる) | `<CACHE>/minecraft` |
| `TORO_VERIFY_JAVA` | サーバーを動かす Java 25 以上 | `JAVA_HOME`、Gradle の JDK、PATH の順 |
| `TORO_VERIFY_PORT` | サーバーのポート | `25599` |

`e2e.py` には `pip install portablemc pillow psutil` が必要です。

## 構成

`src/main/kotlin/net/torosaba/train/` の中です。

| 場所 | 内容 |
| --- | --- |
| `TOROTrain.kt` / `Settings.kt` | プラグイン本体と `config.yml` の値 |
| `train/TrainManager.kt` | 毎 tick の処理の中心。動かす、看板を働かせる、作る・消す・連結、保存 |
| `train/Train.kt` / `Cart.kt` / `Properties.kt` | 列車、車両、設定 (`PropertyKeys` が `/tt property` と看板の名前) |
| `train/Mascon.kt` | マスコン。`MasconProfile` が速度の計算、`MasconControl` が入力と運転者の管理 |
| `train/TrainAction.kt` | 駅に止まる、発車するなどの動作 |
| `train/SpawnPattern.kt` / `SavedTrains.kt` / `BlockTrains.kt` | `3m` などの書き方、保存した列車、ブロックの列車 |
| `track/` | 線路 (レール、ハシゴ、感圧板、自由な線路、線路の外)。`TrackPath` が列車の通ってきた道 |
| `signs/SignTypes.kt` / `ActionSign.kt` / `SignIndex.kt` | 看板の種類、読み取り、線路からの探し方。`Registries.kt` は行き先、出入口、経路、排他区間 |
| `route/Router.kt` | 行き先までの経路探索 |
| `model/` / `render/` | 車体のモデルと、Display エンティティでの描き方 |
| `compat/TrainCartsImport.kt` / `TrainCartsFormat.kt` | TrainCarts の引き継ぎ。`Format` はサーバーなしで読める部分 |
| `command/` / `listener/` | `/tt`、`/mascon`、イベント |

## 決まり

- **言葉**: コメント、メッセージ、README、コミットは日本語です。専門用語を避けた、やさしい言葉で書きます (「分岐器」「車両」「線路の外」など、すでにある言葉に合わせます)。周りのコードと同じくらいの量のコメントにします。
- **TrainCarts のコードは写しません**。保存形式と数値を確かめるために読むだけです。MasconCart の数値は `MasconProfile` と `MasconProfileTest` に合わせてあります。
- **ファイルの改行は LF** です (`.bat` を除く。`.gitattributes` のとおり)。
- **一緒に更新するもの**:
  - コマンド: `TrainCommand` の処理、`help`、タブ補完、README
  - 設定: `Settings.kt`、`config.yml`、README
  - 列車の設定: `TrainProperties` の `save` / `load`、`PropertyKeys`、README
  - 看板: `SignTypes.all`、README の表、`plugin.yml` の権限。マスコン中も働くなら `ignoredInMascon`
  - 車体のモデル: `CartModel` は sealed なので、`save`、`CartModel.load`、`TrainCommand` の `when`、モデルを使っているかの `usesBlockModel`
- **保存したデータは読めるままにします**。`trains.yml`、`saved-trains.yml`、`names.yml`、`traincarts-import.yml` の古い形式を読めなくしないでください。名前を YAML のキーにするときは `Text.toYamlKey` を通します (Bukkit は `.` を階層の区切りとして読むため)。TrainCarts の読み方を増やしたら `TrainCartsImport.FORMAT` を上げます。
- **テストしやすく**: Bukkit なしで動く計算は `object` に分けて `src/test` でテストします。
- **コミット**: 変更のまとまりごとに、何をしたかを 1 行で書きます (例: `車両を叩くと一両だけ壊すようにした`)。
- **サーバーへの配布**は、この文書の範囲外です。頼まれていないのに本番のサーバーに触らないでください。

## 動きの要点

- 列車はいつも先頭の方へ進みます。後ろへ動くときは `Train.reverse()` で先頭と最後尾を入れ替えます。速さはいつも 0 以上です。
- エンティティはワールドに保存しません (`isPersistent = false`)。列車の状態は `trains.yml` にあり、チャンクが読み込まれたときに見た目を作り直します。乗客がいるエンティティを消すと降りるので、`CartVisual` と `internalDismount` を見てください。
- マスコンが ON (`Train.masconDriver` がある) のあいだ、`TrainManager.step` は速さを `MasconProfile` だけで決め、摩擦、重力、パワードレール、動作、最高速度の設定を飛ばします。看板は `SignType.ignoredInMascon` で決まります。運転者が降りたかは毎 tick `checkDriver` で調べます。
- 画面の下の案内は、マスコン中は HUD に上書きされます。運転者に見せるときは `MasconControl.notice` を使います。

## 実機で分かったこと

コードからは分からず、実機で初めて分かったことです。

- Paper 26.3 の既定はホワイトリストが有効です。試験のサーバーでは切ってあります。
- Paper は `Material.entries` から古いマテリアルを隠し、`Material.getMaterial("LEGACY_X", true)` は今のマテリアルに直して返します。古いものは `Material` の定数をリフレクションで読みます (`SchematicConverter.readLegacy`)。
- `PlayerInputEvent` は、乗り物が Display エンティティでも届きます。
- 乗っている人が右クリックしたときは、見ている先がたいてい自分の車両の `Interaction` なので、`PlayerInteractEntityEvent` で受けます。素手で空を右クリックしてもサーバーには何も届きません。
- `ride` コマンドでトロッコに乗せたプレイヤーでも `PlayerInputEvent` は届きます。

試験の作り方で気をつけること:

- キーは `PostMessage` でウィンドウに送れるので、手前になくても届きます。ただし Shift と右クリックは効かないので、ウィンドウが手前にあることを確かめてから本物の入力で送ります (`Client.shift`、`Client.right_click`)。
- 手前にないウィンドウは描画が遅れ、短いキーの入力を取りこぼします。場面の初めに `focus()` を呼びます。
- コンソールの応答は遅れます。結果を読むときは決まった秒数を待たず、`Server.until` などで待ちます。
- サーバーの出力の文字コードは、指定しないと Windows では cp932 になります (`common.Server` で UTF-8 にしています)。

`config.yml` の `debug: true` で、看板、入力、右クリック、降車がログに出ます。`/tt info` で運転者、ノッチ、正確な速さ、止まっている理由が分かります。

## 残っていること

- **遠くの行き先**: 読み込まれていないチャンクの先は、経路探索で行き止まりになります (`TrackGraph.routeCandidates`)。チャンクを読み込むと重くなり、行き先のつながりを別に保存するのは大きな変更になるので、まだ直していません。
- **本番の TrainCarts データでは未確認です**。`smoke.py` の見本はソースに合わせて作ったものです。読み方を変えるときは [TrainCarts のソース](https://github.com/bergerhealer/TrainCarts) を確かめてください。すでに引き継いだサーバーは `/tt import traincarts` で読み直しますが、同じ名前の保存した列車を上書きします。
- **`e2e.py` で確かめていないもの**: マスコン中の線路外走行と `teleport` の看板、カーブや坂の高速走行、`driver-seat-only: true`、`convert-minecarts: false`。
- TrainCarts の機能でまだないものは README の「まだないもの」にあります。

## 参考

- [MasconCart](https://github.com/TORO-Server/MasconCart): 移植元のマスコン
- [TrainCarts](https://github.com/bergerhealer/TrainCarts): 保存形式の確認に使う
- [MarumaSign](https://github.com/TORO-Server/MarumaSign) の `tools/verify`: `e2e.py` の手本
