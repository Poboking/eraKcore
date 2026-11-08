use std::path::{Path, PathBuf};

/// 一条内容寻址条目（源文件或产物）：相对路径一律 `/` 分隔。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HashEntry {
    pub rel_path: String,
    pub bytes: u64,
    pub sha256: String,
}

#[derive(Debug)]
pub enum ErbccError {
    /// 本路未移植/未实现（骨架态；闸把它当作红，不许静默跳过）
    NotPorted(&'static str),
    /// 参数错误（退出码 2）
    Usage(String),
    /// 读写/定位失败（退出码 1）
    Io(String),
    /// 与 Kotlin 侧对拍不等（闸用）
    Parity(String),
}

pub type Outcome<T> = Result<T, ErbccError>;

pub const EXIT_OK: i32 = 0;
pub const EXIT_FAIL: i32 = 1;
pub const EXIT_USAGE: i32 = 2;

/// 产物目录名：冻结的跨模块路径常量（app 打包与设备侧装载链都以字面量取它）。
pub const ERBC_DIR_NAME: &str = "erbc.v1";

/// 仓库根定位标记（与 `ErbcCompilerDigest.MARKER_REL` 同源）。
pub const MARKER_REL: &str = "core-vm/src/main/kotlin/com/eratw/port/core/compile/ErbProgramCompiler.kt";

/// 编译器指纹的源码根（与 `ErbcCompilerDigest.SOURCE_ROOT_REL` 同源）。
pub const COMPILER_SOURCE_ROOT_REL: &str = "core-vm/src/main/kotlin";

/// 引擎版本的唯一来源：`core-vm/.../Version.kt` 里的 `const val VERSION`。
/// 禁止在任何侧硬编码版本串——读不到就报错，不返回默认值冒充。
pub fn erbcc_version(repo_root: &Path) -> Outcome<String> {
    let file = repo_root.join("core-vm/src/main/kotlin/com/eratw/port/core/Version.kt");
    let text = std::fs::read_to_string(&file)
        .map_err(|e| ErbccError::Io(format!("读不到引擎版本文件 {file:?}: {e}")))?;
    for line in text.lines() {
        let t = line.trim();
        if t.starts_with("const val VERSION") {
            if let Some(rest) = t.split_once('=') {
                let v = rest.1.trim().trim_start_matches(':').trim();
                let v = v.trim_matches(|c| c == '"');
                if !v.is_empty() {
                    return Ok(v.to_string());
                }
            }
        }
    }
    Err(ErbccError::Io(format!(
        "{file:?} 里找不到 `const val VERSION`：版本串无源可读，拒绝硬编码"
    )))
}

/// 绝对化但不引入 Windows `\\?\` 动词前缀（`canonicalize` 会加，导致与 Kotlin
/// `toAbsolutePath().normalize()` 的路径口径不可比）。
pub fn absolutize(path: &Path) -> PathBuf {
    let abs = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let s = abs.to_string_lossy();
    match s.strip_prefix(r"\\?\") {
        // 去掉动词前缀后盘符本就是大写，无需再改
        Some(stripped) => PathBuf::from(stripped),
        None => abs,
    }
}

/// 从 `start` 逐级向上找含 [MARKER_REL] 的目录；找不到返回 None。
pub fn find_repo_root(start: &Path) -> Option<PathBuf> {
    let abs = absolutize(start);
    let mut dir: Option<&Path> = if abs.is_dir() {
        Some(abs.as_path())
    } else {
        abs.parent()
    };
    while let Some(d) = dir {
        if d.join(MARKER_REL).is_file() {
            return Some(d.to_path_buf());
        }
        dir = d.parent();
    }
    None
}

/// 当前工作目录（找不到时退回 `.`）。
pub fn current_dir() -> PathBuf {
    std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
}

// ---------------------------------------------------------------------------
// Kotlin 路径上屏口径（F1 单源）
//
// Java 的 `WindowsPath.toString()` 把入参里的 `/` 归一成 `\\`，而 Rust 的 [PathBuf]
// 原样保留 —— 不归一就导致「同一格报红只差分隔符」。`audit` 腿先做了归一、其余四臂没做，
// 于是 Rust 自己内部都不一致（`cli_surface_diff` 的 F1 格钉的就是这个）。两个口径都要留：
// 报红行**没绝对化**（Kotlin 打在 `Path(原文)` 上），清单/产物行**已绝对化**，故分两枚。
// ---------------------------------------------------------------------------

/// Kotlin 的 `Path(原文).toString()` 口径：Windows 上分隔符归一成 `\\`，**不**绝对化。
/// 用在「`corpus 不是目录: <原样入参>`」这类打在未绝对化 Path 上的报红行。
/// TODO(verify)：Java 还会折叠连续分隔符（`a//b` → `a\\b`）；本波没对拍过这种入参。
pub fn kotlin_path_text(raw: &str) -> String {
    if cfg!(windows) {
        raw.replace('/', "\\")
    } else {
        raw.to_string()
    }
}

/// Kotlin 的 `Path.toString()` 口径（**已绝对化**的路径）：Windows 上分隔符恒 `\\`
/// （Java 的 `WindowsPath` 会把 `/` 归一成 `\\`，而 [absolutize] 保留原样）。
/// 不归一 ⇒ 清单 `corpus-root` 行与上屏路径行与基准不可比。
/// TODO(verify)：Java 的 `normalize()` 还会折 `.`/`..` 段；带这两段的入参未对拍
/// （卡点：需要一个「同目录不同写法」的基准跑，闸语料用不到）。
pub fn kotlin_path_string(path: &Path) -> String {
    let s = absolutize(path).to_string_lossy().into_owned();
    if cfg!(windows) {
        s.replace('/', "\\")
    } else {
        s
    }
}

pub fn sha256_hex(bytes: &[u8]) -> String {
    use sha2::{Digest, Sha256};
    let digest = Sha256::digest(bytes);
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(64);
    for b in digest {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 公开向量自检：十六进制小写、无偏移（我第一版把 nibble 映射写错过）。
    #[test]
    fn sha256_hex_matches_public_vectors() {
        assert_eq!(
            sha256_hex(b""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        );
        assert_eq!(
            sha256_hex(b"abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        );
    }

    #[test]
    fn erbcc_version_reads_from_core_vm_source() {
        let repo = find_repo_root(&current_dir()).expect("仓库根应含 core-vm 标记文件");
        let v = erbcc_version(&repo).expect("版本串应从 Version.kt 读到");
        assert!(v.starts_with("0."), "读到的不是版本串：{v}");
    }
}
