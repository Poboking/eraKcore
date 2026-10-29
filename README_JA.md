<div align="center">

# eraKcore

[简体中文](README.md) | [English](README_EN.md) | **日本語**

[![License: MIT](https://img.shields.io/badge/license-MIT-263146?style=flat-square)](LICENSE)
[![CI](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml/badge.svg)](https://github.com/Poboking/eraKcore/actions/workflows/ci.yml)
![Kotlin](https://img.shields.io/badge/Kotlin-2.0.20-7F52FF?style=flat-square)
![JDK](https://img.shields.io/badge/JDK-17-263146?style=flat-square)
![Platform](https://img.shields.io/badge/platform-JVM-6a737d?style=flat-square)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=flat-square)

**Kotlin で書き直した、純 JVM の ERA エンジン。**

Android ネイティブ移植のために作りました。[eratw-pocket](https://github.com/Poboking/eratw-pocket)
は本エンジンを使った最初の Android ネイティブ版 EraTW（上流：画蛇添足版 4.980）で、
一部のローエンド端末でもエミュレータより快適なプレイ体感を保てています。
エンジン自体はゲームデータを含まず、デスクトップ・サーバー・CI でも独立してビルド・実行できます。

</div>

---

## モジュール

| モジュール | 概要 |
|---|---|
| `core-vm` | ERA セマンティック VM：ERB バイトコード実行、変数・キャラクターシステム、セーブ I/O、組み込み関数、SFMT 乱数 |
| `render` | 純 JVM レンダリング：グリフキャッシュ、テキストグリッド、ボタン当たり判定、ディスプレイリスト、グラフィックレイヤー |
| `tools/erbcc` | ERB / ERH → `erbc.v1` バイトコードコンテナコンパイラ |
| `tools/vmdbg` | エージェント向け非対話デバッグ CLI：call / trace / where / var / disasm / sweep / ledger |
| `tools/uipreview` | デスクトップ描画プレビュー：実物の core-vm + render を駆動し、ブラウザで画面を確認 |
| `tools/fontsubset` | フォントサブセット再生成スクリプト（Python）。`render/src/main/resources/fonts` の保守用 |

`specs/` は本プロジェクトの意味論的な権威です：ISA・組み込み関数・変数・ロード・セーブ形式・RNG の
差分検証仕様で、すべての結論に Emuera ソースの `ファイル:行号` を付けています。
仕様と実装が食い違った場合は仕様を優先します——それがこのプロジェクトのルールです。

## ビルドとテスト

JDK 17 以上が必要です。

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

## コーパスと著作権

本リポジトリは era ゲームのデータ（スクリプト・画像・音声・セーブ・設定）を一切配布しません。
`fixtures/golden/` の `GOLDEN_*.ERB` は本プロジェクトが書いたテストフィクスチャで、
ゲームテキストは含みません。正規に入手したゲームデータをご自身でご用意のうえ、
環境変数で指定してください。

Emuera に関する帰属表示と改変の明示は [NOTICE.md](NOTICE.md) をご覧ください。

## 状態

開発は現在も進行中で、Emuera との差分検証は未完了です。既知の問題は issue トラッカーに。
issue と PR を歓迎します——意味論に関する変更は、Emuera の挙動との差分根拠（`ファイル:行号`）を
添えてください。まず仕様、次に実装です。

## ライセンス

[MIT](LICENSE)。同梱フォントはそれぞれオープンソースライセンスに従います。
フォント一覧は [NOTICE.md](NOTICE.md) をご覧ください。
