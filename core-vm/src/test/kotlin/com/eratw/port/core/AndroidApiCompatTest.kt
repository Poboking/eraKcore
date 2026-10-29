package com.eratw.port.core

import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 平台 API 兼容闸（2026-10-22 死循环事件复盘方案 #1，见 `docs/eratw-死循环事件复盘-2026-10-22.md` §5）。
 *
 * ## 抓什么
 *
 * core-vm 是纯 JVM 模块，编译目标是桌面 JDK17 —— 「JDK 编译面有、Android 编译面没有」
 * 的 API 引用（Java 9+ 的 `Field.trySetAccessible`、`StackWalker`、`ProcessHandle`…）
 * 在桌面**编译期与全量测试双双失明**，只有真机 ART 抛 `NoSuchMethodError` 才暴露。
 * 2026-10-22 真机死循环的根因正是这一类：编译面缺失 + `runCatching` 静默吞掉 ⇒
 * 判等字段全跳过 ⇒ 语句池跨形态互折。本闸把暴露点从「真机炸三轮」左移到「桌面闸红一行」。
 *
 * ## 口径（为什么是 compileSdk 的 android.jar，不是 minSdk）
 *
 * 对照物 = **compileSdk（35）的 android.jar 编译面**：它没有的方法，任何 API 级别都没有
 * （trySetAccessible 属于这类）——这是与本次事故同型、且桌面完全无法自检的雷。
 * minSdk 运行面的缺失（如 API 26+ 才有的 `String.isBlank`）由 AGP lint 的 NewApi 在
 * app 构建时按 minSdk 21 + core library desugaring 口径检查，本闸不重复、也不伪造
 * desugaring 白名单。两道闸分工见复盘文档 §5。
 *
 * ## 实现
 *
 * - 用 ASM 流式读 android.jar（`SKIP_CODE`：只要类层次与签名，stub 方法体不读）建索引；
 * - 扫 core-vm **主源集**产物（`build/classes/kotlin/main`，java/main 存在也扫；测试源集
 *   不扫——测试只跑桌面 JVM）的每条方法/字段引用；
 * - owner 以 `java/`、`javax/`、`android/`、`androidx/` 开头的引用必须沿继承链在
 *   android.jar 里解析到（方法沿 super+interfaces BFS，覆盖 default 方法与父类声明；
 *   其他 owner——kotlin-stdlib、本仓类、测试依赖——随 APK 打包自保证，跳过）；
 * - `invokedynamic`（StringConcatFactory/LambdaMetafactory）跳过：D8 desugar 兜底；
 * - 数组 owner（以 `[` 开头）跳过（clone 等由运行时保证）。
 *
 * ## android.jar 定位（找不到必须 fail，闸不许静默跳过）
 *
 * ① 环境变量 `ERATW_ANDROID_JAR` 显式指定 → ② 从测试工作目录向上逐级找
 * `.tooling/android-sdk/platforms/android-<N>/android.jar`（本仓布局，取最高 API）
 * → ③ `ANDROID_HOME` / `ANDROID_SDK_ROOT` 的 platforms。全部落空即 fail 并给指引。
 */
class AndroidApiCompatTest {

    @Test
    fun `core-vm 字节码只引用 android 编译面存在的 java 与 android API`() {
        val jar = locateAndroidJar()
        val index = AndroidJarIndex.read(jar)
        val violations = scanMainClasses(index)
        assertTrue(
            violations.isEmpty(),
            "core-vm 引用了 android 编译面不存在的 API（ART 必抛 NoSuchMethodError/NoSuchFieldError，\n" +
                "android.jar = $jar）：\n  " + violations.joinToString("\n  "),
        )
    }

    @Test
    fun `android 索引对已知 API 的判定正确`() {
        // 自检：对照物本身的行为钉死 —— trySetAccessible（Java 9+）必须不在编译面，
        // setAccessible/isAccessible（Java 1.1/1.2）必须在。这是 2026-10-22 事故的判定基准。
        val jar = locateAndroidJar()
        val index = AndroidJarIndex.read(jar)
        val field = "java/lang/reflect/Field"
        assertTrue(index.hasMethod(field, "setAccessible", "(Z)V"), "setAccessible(Z)V 应在编译面")
        assertTrue(index.hasMethod(field, "isAccessible", "()Z"), "isAccessible()Z 应在编译面")
        assertEquals(
            false,
            index.hasMethod(field, "trySetAccessible", "()Z"),
            "trySetAccessible()Z 是 Java 9+ API，不应出现在任何 android.jar —— 有则本闸口径失效",
        )
    }

    // ------------------------------------------------------------------
    // android.jar 定位与索引
    // ------------------------------------------------------------------

    private fun locateAndroidJar(): Path {
        System.getenv("ERATW_ANDROID_JAR")?.let { p ->
            val path = Path.of(p)
            check(Files.isRegularFile(path)) { "ERATW_ANDROID_JAR 指向的文件不存在: $path" }
            return path
        }
        // ② 本仓布局：从 cwd（core-vm 模块目录）向上逐级找 .tooling/android-sdk
        var dir: Path? = Path.of("").toAbsolutePath()
        while (dir != null) {
            val tooling = dir.resolve(".tooling/android-sdk/platforms")
            if (Files.isDirectory(tooling)) {
                newestPlatform(tooling)?.let { return it }
            }
            dir = dir.parent
        }
        // ③ 标准环境变量
        for (env in listOf("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val sdk = System.getenv(env)?.takeIf { it.isNotBlank() } ?: continue
            newestPlatform(Path.of(sdk, "platforms"))?.let { return it }
        }
        error(
            "平台 API 兼容闸找不到 android.jar。按优先级任配其一：\n" +
                "  1) 环境变量 ERATW_ANDROID_JAR=<android.jar 绝对路径>\n" +
                "  2) 仓库上游目录放 .tooling/android-sdk/platforms/android-<N>/android.jar（本仓布局）\n" +
                "  3) 环境变量 ANDROID_HOME / ANDROID_SDK_ROOT 指向 SDK 根\n" +
                "闸不许静默跳过：找不到对照物 = 兼容性检查失效。",
        )
    }

    private fun newestPlatform(platformsDir: Path): Path? =
        runCatching {
            Files.list(platformsDir).use { stream ->
                stream
                    .filter { Files.isRegularFile(it.resolve("android.jar")) }
                    .map { it.resolve("android.jar") }
                    .sorted(
                        compareByDescending<Path> {
                            it.parent.fileName.toString().removePrefix("android-").toIntOrNull() ?: -1
                        },
                    )
                    .findFirst()
                    .orElse(null)
            }
        }.getOrNull()

    /** android.jar 索引：类 → (super, interfaces, 方法键集, 字段键集)；键 = name+desc。 */
    private class AndroidJarIndex {
        private val classes = HashMap<String, Entry>()

        private class Entry(
            val superName: String?,
            val interfaces: List<String>,
            val methods: HashSet<String>,
            val fields: HashSet<String>,
        )

        fun hasClass(name: String): Boolean = classes.containsKey(name)

        // 类层次是无环 DAG：沿 super 链上溯、每层把该层的接口递归查完，所有可达类型必被覆盖。
        fun hasMethod(owner: String, name: String, desc: String): Boolean {
            var cur: String? = owner
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val e = classes[cur] ?: return false // 链上类缺失 = 编译面没有，一律不通过
                if (name + desc in e.methods) return true
                cur = e.superName
                for (i in e.interfaces) if (hasMethod(i, name, desc)) return true
            }
            return false
        }

        fun hasField(owner: String, name: String, desc: String): Boolean {
            var cur: String? = owner
            val seen = HashSet<String>()
            while (cur != null && seen.add(cur)) {
                val e = classes[cur] ?: return false
                if (name + desc in e.fields) return true
                cur = e.superName
                for (i in e.interfaces) if (hasField(i, name, desc)) return true
            }
            return false
        }

        companion object {
            fun read(jar: Path): AndroidJarIndex {
                val index = AndroidJarIndex()
                ZipInputStream(Files.newInputStream(jar).buffered()).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        if (!entry.name.endsWith(".class")) continue
                        if (entry.name.endsWith("module-info.class")) continue
                        // visitor 只负责填充下方外层可变量；accept 返回后落进索引。
                        // （覆写 Java 方法时参数改名以避开与外层变量同名遮蔽；返回类型不显式标注，
                        //  按 Kotlin 平台类型推断才能 return null。）
                        var clsName = ""
                        var superName: String? = null
                        var ifaces: List<String> = emptyList()
                        val methods = HashSet<String>()
                        val fields = HashSet<String>()
                        ClassReader(zip.readBytes()).accept(
                            object : ClassVisitor(Opcodes.ASM9) {
                                override fun visit(
                                    version: Int,
                                    access: Int,
                                    name: String,
                                    signature: String?,
                                    superClass: String?,
                                    ifaces2: Array<String>,
                                ) {
                                    clsName = name
                                    superName = superClass
                                    ifaces = ifaces2.toList()
                                }

                                override fun visitMethod(
                                    access: Int,
                                    name: String,
                                    desc: String,
                                    signature: String?,
                                    exceptions: Array<String>?,
                                ): org.objectweb.asm.MethodVisitor? {
                                    methods += name + desc
                                    return null
                                }

                                override fun visitField(
                                    access: Int,
                                    name: String,
                                    desc: String,
                                    signature: String?,
                                    value: Any?,
                                ): org.objectweb.asm.FieldVisitor? {
                                    fields += name + desc
                                    return null
                                }
                            },
                            ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES,
                        )
                        index.classes[clsName] = Entry(superName, ifaces, methods, fields)
                    }
                }
                check(index.classes.isNotEmpty()) { "android.jar 索引为空（$jar）——文件损坏？" }
                return index
            }
        }
    }

    // ------------------------------------------------------------------
    // core-vm 主源集扫描
    // ------------------------------------------------------------------

    private fun scanMainClasses(index: AndroidJarIndex): List<String> {
        val roots = listOf("build/classes/kotlin/main", "build/classes/java/main")
            .map { Path.of(it) }
            .filter { Files.isDirectory(it) }
        check(roots.isNotEmpty()) {
            "找不到 core-vm 主源集编译产物（build/classes/kotlin/main）。" +
                "本测试由 :core-vm:test 驱动时 classes 任务已先行，目录必然存在。"
        }
        val violations = ArrayList<String>()
        for (root in roots) {
            Files.walk(root).use { stream ->
                val classFiles = stream
                    .filter { it.toString().endsWith(".class") }
                    .filter { it.fileName.toString() != "module-info.class" }
                    .toList()
                for (file in classFiles) collectViolations(index, file, violations)
            }
        }
        return violations
    }

    private fun collectViolations(index: AndroidJarIndex, classFile: Path, out: MutableList<String>) {
        val bytes = Files.readAllBytes(classFile)
        val siteClass = classFile.fileName.toString().removeSuffix(".class")
        var siteMethod = "<clinit>"
        ClassReader(bytes).accept(
            object : ClassVisitor(Opcodes.ASM9) {
                override fun visitMethod(
                    access: Int,
                    name: String,
                    desc: String,
                    signature: String?,
                    exceptions: Array<String>?,
                ): MethodVisitor {
                    siteMethod = name
                    return object : MethodVisitor(Opcodes.ASM9) {
                        override fun visitMethodInsn(
                            opcode: Int,
                            owner: String,
                            name: String,
                            desc: String,
                            itf: Boolean,
                        ) {
                            checkRef(index, owner, name, desc, "方法", "$siteClass.$siteMethod", out)
                        }

                        override fun visitFieldInsn(opcode: Int, owner: String, name: String, desc: String) {
                            checkRef(index, owner, name, desc, "字段", "$siteClass.$siteMethod", out)
                        }
                    }
                }
            },
            ClassReader.SKIP_FRAMES,
        )
    }

    private fun checkRef(
        index: AndroidJarIndex,
        owner: String,
        name: String,
        desc: String,
        kind: String,
        site: String,
        out: MutableList<String>,
    ) {
        // 数组类型的方法（clone 等）由运行时保证；invokedynamic 的 bootstrap（Lambda/
        // StringConcat）由 D8 desugar 兜底 —— 都不查。
        if (owner.startsWith("[")) return
        val checked = owner.startsWith("java/") ||
            owner.startsWith("javax/") ||
            owner.startsWith("android/") ||
            owner.startsWith("androidx/")
        if (!checked) return // kotlin/*、com.eratw/*、其他依赖随 APK 打包自保证
        val found = if (kind == "字段") index.hasField(owner, name, desc) else index.hasMethod(owner, name, desc)
        if (!found) {
            val missingClass = !index.hasClass(owner)
            out += "$site 引用 $kind $owner.$name:$desc —— ${if (missingClass) "类不在" else "签名不在"}" +
                "android.jar 编译面（compileSdk 35）"
        }
    }
}
