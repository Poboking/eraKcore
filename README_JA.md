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

Android ネイティブ移植のために書いています。[eratw-pocket](https://github.com/Poboking/eratw-pocket)
は本エンジンを使って作った最初の Android ネイティブ版 EraTW（上流：画蛇添足版 4.980）で、
一部のローエンド端末ではエミュレータより快適に遊べます。エンジン自体はゲームデータを
含まず、デスクトップ・サーバー・CI のいずれでも独立してビルド・実行できます。

</div>

---

## プロジェクトの目標

目標が衝突したときは、次の優先順位で判断します。

1. Emuera の観察可能な挙動との一致。結論は `specs/` の差分検証仕様に従います。
2. プラットフォーム非依存：エンジンは Android に依存せず純 JVM で動作し、
   フロントエンドはディスプレイリストのみを消費します。
3. 自己完結性：ゲームデータがなくてもビルド・テスト・再現ができます。

この順序は衝突の解決のためだけに使います。私たちのルールは、仕様と実装が食い違ったら
仕様を優先すること。実装の過程で仕様そのものの誤りが見つかった場合は、先に仕様を直し、
それから実装を直します。意味論に関する変更は、必ず先に `specs/` に文章として落とし
（Emuera ソースの `ファイル:行号` を添えて）、それからコードに手を入れます。

## モジュール

| モジュール | 概要 |
|---|---|
| `core-vm` | ERA セマンティック VM：ERB バイトコード実行、変数・キャラクターシステム、セーブ I/O、組み込み関数、SFMT 乱数 |
| `render` | 純 JVM レンダリング：グリフキャッシュ、テキストグリッド、ボタン当たり判定、ディスプレイリスト、グラフィックレイヤー |
| `tools/erbcc` | ERB / ERH → `erbc.v1` バイトコードコンテナコンパイラ |
| `tools/vmdbg` | エージェント向け非対話デバッグ CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | デスクトップ描画プレビュー：実物の core-vm + render を駆動し、ブラウザで画面を確認 |
| `tools/fontsubset` | フォントサブセット再生成スクリプト（Python）。`render/src/main/resources/fonts` の保守用 |

`specs/` は本プロジェクトの意味論的な権威です：ISA・組み込み関数・変数・ロード・
セーブ形式・RNG の差分検証仕様で、すべての結論に Emuera ソースの `ファイル:行号`
を付けています。

## アーキテクチャと設計判断

**仕様先行の差分検証開発。** `specs/` の 6 本の仕様は実装に先立って執筆され、
全結論に Emuera 逆コンパイルソースの `ファイル:行号` を根拠として付けます。
ドキュメントだけでは見落とす細部を差分検証で拾えます。たとえばデフォルトの RNG は
`System.Random` ではなく自前実装の SFMT19937（周期 2^19937−1）であり、これをそのまま再現しています。

**AOT バイトコード + mmap ゼロ解析ロード。** ERB/ERH はビルド時に erbcc が
`.erbc` コンテナ（リトルエンディアン、i64 値、文字列インターニング、命令固定長
4×u32、セクションテーブル）へコンパイルし、実行時は mmap で直接参照して解析なしで
実行します。Android での高速ロードの直接の源です。

**コンパイルの決定性はテスト対象。** 産物はバイト単位で再現可能
（`ErbcDeterminismTest`）に加え、ロード順序の監査もあります。同名定義ファイルの
相対順序がファイルシステムの列挙順に左右される場合、監査カウンタが非ゼロで失敗と
します。マシンやプラットフォームをまたいで産物は一致します。

**純 JVM レイヤリング。** `core-vm` は Android API・`kotlin.random`・静的ミュータブル
グローバルを禁止し（状態は store の aux スロットへ）、デスクトップと CI で全量テスト
可能です。`render` も純 JVM のディスプレイリストモデルで、フロントエンド（Compose、
デスクトッププレビュー）はすべてその消費者にすぎません。

**バージョン突合はビルド時に。** erbcc のバージョン単一ソースは `core-vm` の
`Version.kt` で、下流ビルドはバイトコード産物のコンパイラバージョンとソース
フィンガープリントを検証し、逸脱があればビルド失敗。古い産物と新しいエンジンの
取り違えは出荷されません。

**三段階の状態ハッシュ回帰。** テスト側の L3 ハーネスは `VariableStore` の公開読み
書き面だけ経由で VM 状態全体（変数・XML・スプライトプール・インターニング表）を
観察し、クラスタ単位のダイジェストをハッシュチェーン化して PC 基準の全量実行と
バイト単位で突き合わせます。エンジンコードには一行も触れないため、内部レイアウトの
リファクタは基準に影響せず、意味論の変化はハッシュ値にそのまま反映されます。

## ビルドとテスト

JDK 17 以上が必要です。コマンドはすべてリポジトリのルートで実行する前提です。

```bash
./gradlew :core-vm:test             # モジュール単位のテスト
./gradlew :tools:erbcc:installDist  # コンパイラ CLI
```

一部のテストは実ゲームのコーパスや計測産物に依存し、`ERATW_CORPUS` / `ERATW_GAMEDIR` /
`ERATW_ERBC_DIR` 環境変数でゲートされています：変数が未設定ならそのケースは生成されず、
それ以外は自己完結しています。CI はこの範囲のテストを実行しています。

## リポジトリ構成

```
eraKcore（本リポジトリ、MIT）     eratw-pocket（GPL-3.0）
├── core-vm   ←─────────────     ├── render-canvas
├── render    ←─────────────     ├── platform
└── erbcc                        └── app（Compose シェル）
```

eratw-pocket はゲームの意味論を一切知りません：スクリプトの挙動・組み込み関数・
セーブ形式の権威ある定義は、すべて本リポジトリの `core-vm` と `specs/` にあります。
ゲームの挙動に関する問題は [eratw-pocket](https://github.com/Poboking/eratw-pocket)
ではなく本リポジトリへ issue をお願いします。シェルの変更がゲームの挙動を変える
ことはありません。

## コーパスと著作権

本リポジトリは era ゲームのデータ（スクリプト・画像・音声・セーブ・設定）を一切
配布しません。`fixtures/golden/` の `GOLDEN_*.ERB` は本プロジェクトが書いたテスト
フィクスチャで、ゲームテキストは含みません。正規に入手したゲームデータをご自身で
ご用意のうえ、環境変数で指定してください。

Emuera に関する帰属表示と改変の明示は [NOTICE.md](NOTICE.md) をご覧ください。

## 状態

開発は現在も進行中で、Emuera との差分検証は未完了です。既知の問題は issue
トラッカーにあります。issue と PR を歓迎します。意味論に関する変更は、Emuera の
挙動との差分根拠（`ファイル:行号`）を添え、先に `specs/` を更新してから実装に手を
入れてください。挙動の差分について確信が持てない場合は、まず issue で相談いただく
のが確実です。

## 謝辞

本プロジェクトは、era コミュニティが長年積み重ねてきた仕事の上に成り立っています。
以下の作者と貢献者の皆さまに感謝します。

- **MinorShift** と **妊）|дﾟ)の中の人**：Emuera の著作者。本エンジンは Emuera の
  挙動を互換性の参考にしています。
- **まだ名前は無い人**：eraThe World（eraTW）の `GameBase.csv` に署名されている
  改変・制作者。
- **eraTW の口上およびコンテンツの作者・改変者の皆さま**：キャラクター口上、イベント、
  データ、ドキュメントに貢献されたすべての創作者。
- **era コミュニティのスクリプト・コンテンツ作者とツール保守者の皆さま**。

上記の謝辞は、外部の第三者材料の著作権・帰属・ライセンス・利用条件を変更するものでは
ありません。それらを使用・再配布する際は、添付のファイルの記載に従ってください。

## ライセンス

[MIT](LICENSE)。同梱フォントはそれぞれオープンソースライセンスに従います。
フォント一覧は [NOTICE.md](NOTICE.md) をご覧ください。
