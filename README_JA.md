<div align="center">

# eraKcore

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**Kotlin で実装した、純 JVM の ERA エンジン。**

eraKcore は、ごくありふれた願いから始まりました：エミュレータを介さず、Android で
EraTW をネイティブに遊びたい。[eratw-pocket](https://github.com/Poboking/eratw-pocket)
はその答えで、本エンジンを使って作った最初の Android ネイティブ版 EraTW
（上流：画蛇添足版 4.980）です。一部のローエンド端末ではエミュレータより快適に
動きます。エンジン自体はゲームデータを一切含まず、デスクトップ・サーバー・CI の
いずれでも独立してビルド・実行できます。

</div>

---

## これは何か

- **セマンティクス層であり、フロントエンドではありません。** eraKcore が持つのは
  ゲームのルールそのもの：ERB バイトコード実行、変数・キャラクターシステム、
  セーブ、組み込み関数です。描画はディスプレイリストとして出力し、見た目は各
  フロントエンドに任せます。
- **互換性の基準は Emuera。** エンジンの挙動の基準は Emuera の実際の動作で、
  すべての結論は `specs/` の差分検証仕様として文章化し、Emuera ソースの
  `ファイル:行号` を添える必要があります。
- **Android に依存しない。** 純 JVM 実装で、グラフィックのない環境でもテストが
  できます。これが CI での継続的な差分検証を可能にしています。
- **自己完結。** ゲームデータがなくても、ビルド・テスト・再現がすべて可能です。

`specs/` は本プロジェクトの意味論的な権威で、ISA・組み込み関数・変数・ロード・
セーブ形式・RNG を扱います。


## モジュール

| モジュール | 概要 |
|---|---|
| `core-vm` | ERA セマンティック VM：ERB バイトコード実行、変数・キャラクターシステム、セーブ I/O、組み込み関数、SFMT 乱数 |
| `render` | 純 JVM レンダリング：グリフキャッシュ、テキストグリッド、ボタン当たり判定、ディスプレイリスト、グラフィックレイヤー |
| `tools/erbcc` | ERB / ERH → `erbc.v1` バイトコードコンテナコンパイラ |
| `tools/vmdbg` | エージェント向け非対話デバッグ CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | デスクトップ描画プレビュー：実物の core-vm + render を駆動し、ブラウザで画面を確認 |
| `tools/fontsubset` | フォントサブセット再生成スクリプト（Python）。`render/src/main/resources/fonts` の保守用 |

## ビルドとテスト

JDK 17 以上が必要です。コマンドはすべてリポジトリのルートで実行します。

```bash
./gradlew :core-vm:test             # モジュール単位のテスト
./gradlew :tools:erbcc:installDist  # コンパイラ CLI
```

一部のテストは実ゲームのコーパスや計測産物に依存し、`ERATW_CORPUS` /
`ERATW_GAMEDIR` / `ERATW_ERBC_DIR` 環境変数でゲートされています：変数が未設定なら
そのケースは生成されず、それ以外は自己完結しています。CI はこの範囲のテストを
実行しています。

## リポジトリ構成

```
eraKcore（本リポジトリ、MIT）     eratw-pocket（GPL-3.0）
├── core-vm   ←─────────────     ├── render-canvas
├── render    ←─────────────     ├── platform
└── erbcc                        └── app（Compose シェル）
```

eratw-pocket がゲームの意味論を一切知らないのは、意図的な設計です。スクリプトの
挙動・組み込み関数・セーブ形式の権威ある定義はすべて本リポジトリの `core-vm` と
`specs/` にあり、シェルの変更がゲームの挙動を変えることも許されていません。そのため、
「挙動がおかしい」系の問題は、現象がスマホ上で起きたものでも、
[eratw-pocket](https://github.com/Poboking/eratw-pocket) ではなく本リポジトリへ
お願いします。

## アーキテクチャと設計判断

**仕様先行の差分検証開発。** `specs/` の 6 本の仕様はいずれも実装に先立って
執筆され、全結論に Emuera 逆コンパイルソースの `ファイル:行号` を根拠として
付けます。ドキュメントだけでは見落とす細部を差分検証で拾えます。たとえば
デフォルトの RNG は `System.Random` ではなく自前実装の SFMT19937
（周期 2^19937−1）であり、これをそのまま再現しています。プロジェクト内の
ルールは単純です：仕様と実装が食い違ったら仕様を優先する。実装によって仕様の
誤りが判明した場合は、先に仕様を直し、それから実装を直す——この順序だけは
変えません。

**AOT バイトコード + mmap ゼロ解析ロード。** ERB/ERH はビルド時に erbcc が
`.erbc` コンテナ（リトルエンディアン、i64 値、文字列インターニング、命令固定長
4×u32、セクションテーブル）へコンパイルし、実行時は mmap で直接参照して解析なしで
実行します。Android での高速ロードの直接の源です。

**コンパイルの決定性はテスト対象であり、前提ではない。** 産物はバイト単位で
再現可能（`ErbcDeterminismTest`）に加え、ロード順序の監査もあります。同名定義
ファイルの相対順序がファイルシステムの列挙順に左右される場合、監査カウンタが
非ゼロで失敗とします。マシンやプラットフォームをまたいで産物は一致します。

**純 JVM レイヤリング。** `core-vm` は Android API と `kotlin.random` を禁止し、
静的ミュータブルグローバルも持ちません（状態は store の aux スロットへ）。
そのためデスクトップと CI で全量テストができます。`render` も純 JVM の
ディスプレイリストモデルで、フロントエンド（Compose、デスクトッププレビュー）は
すべてその消費者にすぎません。

**バージョン突合はビルド時に。** erbcc のバージョンの単一ソースは `core-vm` の
`Version.kt` です。下流ビルドはバイトコード産物のコンパイラバージョンとソース
フィンガープリントを検証し、逸脱があればビルド失敗。古い産物と新しいエンジンの
取り違えは出荷されません。

**三段階の状態ハッシュ回帰。** テスト側の L3 ハーネスは `VariableStore` の公開
読み書き面だけを通じて VM 状態全体（変数・XML・スプライトプール・インターン
テーブル）を観察し、クラスタ単位でハッシュチェーンに要約して、PC 基準の全量実行と
バイト単位で比較します。エンジンコードには一切手を入れません：内部レイアウトの
リファクタは指標に影響せず、意味論の変化はチェーン値にそのまま反映されます。

## コーパスと著作権

本リポジトリは era ゲームのデータ（スクリプト・画像・音声・セーブ・設定）を一切
配布しません。`fixtures/golden/` の `GOLDEN_*.ERB` は本プロジェクトが書いたテスト
フィクスチャで、ゲームテキストは含みません。正規に入手したゲームデータをご自身で
ご用意のうえ、環境変数で指定してください。

Emuera に関する帰属表示と改変の明示は [NOTICE.md](NOTICE.md) をご覧ください。

## 貢献について

開発は進行中で、Emuera との差分検証は未完了です。既知の問題は issue トラッカーに
あります。issue と PR はどちらも歓迎します。意味論に関する変更には一つだけ前提が
あります：先に `specs/` に根拠（`ファイル:行号` 付き）を書き、それから実装に手を
入れること。挙動の差分を疑っているだけで確信が持てないなら、仕様を書く前に
issue を立てて相談するだけで十分です。

## 謝辞

このプロジェクトは、era コミュニティが長年積み重ねてきた成果の上にあります。

- **MinorShift** と **妊）|дﾟ)の中の人**——Emuera の著作者。本エンジンの互換性に
  関する判断は、すべてその作品を基準にしています。
- **まだ名前は無い人**——eraThe World（eraTW）の `GameBase.csv` に署名されている
  改変・制作者。そして eraTW の口上・イベント・データ・ドキュメントを書かれた
  すべての方々。
- era コミュニティのスクリプト作者、コンテンツ作者、ツール保守者の皆さま。

上記の謝辞は、いかなる第三者材料の著作権・ライセンス条件も変更するものでは
ありません。使用・再配布の際は添付ファイルの記載に従ってください。

## ライセンス

[MIT](LICENSE)。同梱フォントはそれぞれオープンソースライセンスに従います。
フォント一覧は [NOTICE.md](NOTICE.md) をご覧ください。

