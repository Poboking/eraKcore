# erbcc-rs

erbcc 的 Rust 侧实现，用于和 Kotlin 版 `tools/erbcc` 做逐字节对拍。它不是运行时
依赖；运行时和 Android 壳层都不需要它即可构建。

## 构建

需要 Rust stable（edition 2021）与 Cargo：

```bash
cargo build --release --manifest-path tools/erbcc-rs/Cargo.toml
# 产物：tools/erbcc-rs/target/release/erbcc（Windows 为 erbcc.exe）
cargo test --manifest-path tools/erbcc-rs/Cargo.toml
```

## CLI

```bash
erbcc scan    --corpus <ERB目录>
erbcc symbols --corpus <ERB目录> [--json <输出文件>]
erbcc compile --corpus <ERB目录> --out <产物目录> [--limit <N>]
erbcc check   --corpus <ERB目录> [--out <产物目录>]
erbcc audit   --corpus <ERB目录> [--game-root <游戏根>] [--strict]
```

`erbcc --help` 的输出与 Kotlin 版逐字节对齐，契约见
`tests/cli_contract.rs` 与 `tests/data/rust-scan/kotlin-usage.txt`。

## 与 Gradle 的接线

`tools/erbcc-rs/gradle/erbcc-rs-parity.gradle.kts` 由 `tools/erbcc/build.gradle.kts`
末尾 `apply(...)` 接入，注册四个任务：

```bash
./gradlew :tools:erbcc:erbccClasspathFile   # 产出 ERBCC_CLASSPATH（installDist 的 jar 清单）
./gradlew :tools:erbcc:compileCorpusRs -Pcorpus=<ERB目录> [-Pout=<目录>] [-Plimit=<N>]
./gradlew :tools:erbcc:rsParity -Pcorpus=<ERB目录> [-Pout=<目录>]
./gradlew :tools:erbcc:regenRsBaselines -Plane=<rust-manifest|rust-kernel|all>
```

对拍驱动在 `devtools/erbcc_rs_parity.py`；Python 解释器可用 `-PparityPython=` 或
`ERATW_PYTHON` 覆盖。

## 测试数据

`tests/data/` 下的容器与侧车是**合成对拍夹具**（体积很小，不是游戏产物），
由 `.gitattributes` 标记为二进制，禁止手改。新增基准请用
`regenRsBaselines` 重新生成，并把差异逐项核对后再提交。

## 与 Kotlin 版的关系

两套实现共享 `specs/erbc-format.md`、`specs/isa.yaml`、`specs/loader.md` 等规格。
对拍的目标不是「Rust 替代 Kotlin」，而是用第二条独立实现验证容器的字节级契约：
同一语料经两条腿编译后，`program.erbc` / `program.meta` / 清单 / 侧车应逐字节一致；
不一致必须能归因到明确的 `KNOWN_RUST_GAP` 或规格缺口。
