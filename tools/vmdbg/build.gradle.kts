// vmdbg —— JVM 调试器 CLI（CONTRACT-DBG1 路2）。
// 工具链/依赖风格照抄 tools/erbcc（纯 JVM 工具模块口径：禁 Android API），
// 但入口用 application 插件：installDist 后 tools/vmdbg/build/install/vmdbg/bin/vmdbg <子命令>。
plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.eratw.port.vmdbg.MainKt")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 合成语料装置（MiniCorpus）要用 erbcc 的 CorpusCompile + ErbcArtifacts 把临时 mini ERB
    // 编成 .erbc 产物目录。方向安全（与 core-vm 对 erbcc 的测试依赖同一口径）：
    // erbcc(main) → core-vm(main)；vmdbg(main) → core-vm(main)；vmdbg(test) → erbcc(main) 无环。
    testImplementation(project(":tools:erbcc"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 单测全部用合成 mini 语料（临时目录），不依赖 ERATW_* 环境变量、不依赖真语料；
    // 环境透传只为了与本仓其他工具模块同款（变量缺失时用例照常全跑，不做 Assumptions 跳过）。
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}
