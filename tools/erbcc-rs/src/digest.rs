use std::io::Read;
use std::path::{Path, PathBuf};

use sha2::{Digest, Sha256};

use crate::types::{ErbccError, Outcome, COMPILER_SOURCE_ROOT_REL};

/// 编译器指纹 `compiler-digest` 的一次计算结果。
///
/// 口径权威 = `compile/ErbcCompilerDigest.kt:40-103`（逐条照抄，不得自创）：
/// 1. 文件集 = `core-vm/src/main/kotlin` 递归全部 `.kt` 普通文件（不含测试源集，`:84-90`）；
/// 2. 相对路径 = 相对**仓库根**，`\` 统一成 `/`（`:87`）；
/// 3. 按相对路径字典序（`String` 自然序）排序（`:89`）；
/// 4. 逐文件喂 SHA-256：`u32be(路径字节数) ‖ 路径 UTF-8 ‖ u32be(文件字节数) ‖ 文件字节`（`:93-101`）；
/// 5. 输出小写十六进制（`:102` + `:136-145`）；无时间戳/绝对路径/主机名。
///
/// **文件集为空或源码树缺失必须报错**，不许返回空哈希冒充「一致」
/// （Kotlin 侧 `ErbcCompilerDigest.kt:81-83` 与 `:91` 的两处 `check(...)`）。
pub struct CompilerDigest {
    /// SHA-256 小写十六进制（64 字符，`ErbcCompilerDigest.kt:66`）。
    pub digest: String,
    /// 参与计算的文件数（清单 `compiler-sources` 行，`ErbcCompilerDigest.kt:69`）。
    pub file_count: usize,
}

/// 递归收集 `[src_root]` 下的 `.kt` 普通文件，返回 `(相对仓库根的 `/` 分隔路径, 绝对路径)`，
/// 已按相对路径字典序排序（= `ErbcCompilerDigest.kt:84-90`）。
///
/// 判据逐条：
/// * 只收普通文件，且**跟随符号链接**（Kotlin `Files.isRegularFile` 默认 NOFOLLOW_LINKS=false）
///   —— 故用 `Path::is_file()` 而不是 `DirEntry::file_type()`（后者不跟随链接）；
/// * 文件名以 `.kt` 结尾，**大小写敏感**（`:86` 的 `endsWith(".kt")` 没有 ignoreCase）；
/// * 相对路径 = `root.relativize(it)` 后 `\` → `/`（`:87`）；
/// * 排序键 = 该相对路径（`:89` 的 `sortedBy { it.first }`）。
///
/// 排序口径的已知差异（本仓不触发）：Java 的 `String` 自然序按 **UTF-16 码元** 比较，Rust 按
/// **UTF-8 字节** 比较；BMP 内两者同序，只有增补平面字符（码点 ≥ U+10000）才会分叉，而
/// `core-vm/src/main/kotlin` 的目录与文件全 ASCII。TODO(verify)：源码树若出现星平面字符的
/// 文件名需重新对齐（改比较键为 UTF-16 码元序列）。
///
/// 目录项打不开（权限等）时本函数跳过该目录；Kotlin 的 `Files.walk` 会把 `IOException`
/// 抛成流终止。两侧都在「构建期读不动源码树」这一罕见面上分叉，见交付报告 TODO(verify)。
fn collect_kt_files(repo_root: &Path, src_root: &Path) -> Vec<(String, PathBuf)> {
    let mut out: Vec<(String, PathBuf)> = Vec::new();
    let mut stack: Vec<PathBuf> = vec![src_root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            if path.is_dir() {
                stack.push(path);
                continue;
            }
            if !path.is_file() {
                continue;
            }
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().to_string())
                .unwrap_or_default();
            if !name.ends_with(".kt") {
                continue;
            }
            // src_root 恒在 root 之下，strip_prefix 必成功；兜底分支只为不依赖这个前提。
            let rel = path
                .strip_prefix(repo_root)
                .map(|r| r.to_string_lossy().replace('\\', "/"))
                .unwrap_or_else(|_| path.to_string_lossy().replace('\\', "/"));
            out.push((rel, path));
        }
    }
    out.sort_by(|a, b| a.0.cmp(&b.0));
    out
}

/// 计算 `[repo_root]` 对应的编译器指纹（= Kotlin `ErbcCompilerDigest.compute`，`:78-103`）。
///
/// 失败面与 Kotlin 一致：源码树不存在、树里一个 `.kt` 都没有、单个文件读不出来，一律 `Err`
/// （Kotlin 侧对应 `check(...)` 抛 `IllegalStateException`）。
pub fn compiler_digest(repo_root: &Path) -> Outcome<CompilerDigest> {
    // Kotlin `repoRoot.toAbsolutePath().normalize()`（`:79`）；Rust 走 `types::absolutize`，
    // 不引 `canonicalize` 的 `\\?\` 动词前缀（口径见 types.rs 的 `absolutize` KDoc）。
    let root = crate::types::absolutize(repo_root);
    let src_root = root.join(COMPILER_SOURCE_ROOT_REL);
    if !src_root.is_dir() {
        return Err(ErbccError::Io(format!(
            "编译器源码树不存在：{}（compiler-digest 没有判据；repoRoot={}）",
            src_root.display(),
            root.display()
        )));
    }
    let files = collect_kt_files(&root, &src_root);
    if files.is_empty() {
        return Err(ErbccError::Io(format!(
            "编译器源码树里没有 .kt 文件：{}（compiler-digest 没有判据）",
            src_root.display()
        )));
    }

    let mut sha = Sha256::new();
    for (rel, path) in &files {
        let rel_bytes = rel.as_bytes();
        let bytes = std::fs::read(path)
            .map_err(|e| ErbccError::Io(format!("读编译器源码失败 {}: {e}", path.display())))?;
        // `u32be(路径字节数) ‖ 路径 ‖ u32be(内容字节数) ‖ 内容`：Kotlin 用
        // `ByteBuffer.allocate(4).putInt(n)`（默认大端 i32，`:97-100`）。
        sha.update((rel_bytes.len() as u32).to_be_bytes());
        sha.update(rel_bytes);
        sha.update((bytes.len() as u32).to_be_bytes());
        sha.update(bytes);
    }
    Ok(CompilerDigest {
        digest: hex_lower(&sha.finalize()),
        file_count: files.len(),
    })
}

/// 单文件 SHA-256（清单 `source`/`artifact` 行用）。
///
/// 流式读（64 KiB 一块），与 `ArtifactFreshness.kt:443-455` 的 `sha256Hex(path)` 同口径 ——
/// 全量容器可达数百 MB，不能整读进内存；两侧最终摘要逐位相同（同为 SHA-256）。
pub fn sha256_file(path: &Path) -> Outcome<String> {
    let mut file = std::fs::File::open(path)
        .map_err(|e| ErbccError::Io(format!("打开文件失败 {}: {e}", path.display())))?;
    let mut sha = Sha256::new();
    let mut buf = vec![0u8; 1 << 16];
    loop {
        let n = file
            .read(&mut buf)
            .map_err(|e| ErbccError::Io(format!("读文件失败 {}: {e}", path.display())))?;
        if n == 0 {
            break;
        }
        sha.update(&buf[..n]);
    }
    Ok(hex_lower(&sha.finalize()))
}

/// 字节 → 小写十六进制（`ErbcCompilerDigest.kt:136-145`、`ErbcArtifacts.kt:105-116`）。
pub(crate) fn hex_lower(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(bytes.len() * 2);
    for b in bytes {
        out.push(HEX[(b >> 4) as usize] as char);
        out.push(HEX[(b & 0x0f) as usize] as char);
    }
    out
}
