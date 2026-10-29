// uipreview —— 桌面服务基座 + 浏览器贴帧 MVP（CONTRACT-UP1）。
// 工具链/依赖风格照抄 tools/vmdbg（纯 JVM 工具模块口径：禁 Android API、零第三方运行时依赖；
// HTTP 用 JDK 自带 com.sun.net.httpserver，PNG 编码用 javax.imageio）。
// 入口用 application 插件：installDist 后 tools/uipreview/build/install/uipreview/bin/uipreview <子命令>。
plugins {
    kotlin("jvm")
    application
}

application {
    mainClass.set("com.eratw.port.uipreview.MainKt")
}

kotlin {
    jvmToolchain(17)
}

// 设备档案随 jar 分发（组长接线）：档案数据在模块根 profiles/（不在 src/main/resources，
// 保留「仓库里可直接编辑」的布局），这里把它并入主资源集的 profiles/ 前缀——
// installDist 产物在任意 cwd 跑 `serve --device` 都能从 classpath 兜底拿到内置档案
// （DeviceProfiles.loadDefault 的搜索顺序：环境变量 → 文件系 → classpath）。
tasks.named<Copy>("processResources") {
    from("profiles") {
        into("profiles")
    }
}

dependencies {
    implementation(project(":core-vm"))
    implementation(project(":render"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // 合成语料装置（MiniTitle）要用 erbcc 的 CorpusCompile + ErbcArtifacts 把临时 mini ERB
    // 编成 .erbc 产物目录。方向安全（与 tools/vmdbg 的测试依赖同一先例）：
    // erbcc(main) → core-vm(main)；uipreview(main) → core-vm + render(main)；uipreview(test) → erbcc(main) 无环。
    testImplementation(project(":tools:erbcc"))
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    // 单测全部用合成 mini 语料（临时目录），不依赖 ERATW_* 环境变量、不依赖真语料；
    // 环境透传只为了与本仓其他工具模块同款（变量缺失时用例照常全跑，不做 Assumptions 跳过）。
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
    // 手动旋钮透传（GalleryPngTest 的属性门控）：`-Deratw.up2.gallery=1` 要进测试 JVM
    // 才生效 —— Gradle 命令行 -D 只落在 daemon，必须在这里转交给 worker。
    System.getProperties().filterKeys { it is String && it.startsWith("eratw.") }
        .forEach { (k, v) -> systemProperty(k as String, v as String) }
}
