plugins {
    kotlin("jvm")
}

// M4 渲染层：纯 JVM（ADR-001 的重设计区 + AGENTS.md 铁律），全部回归在桌面跑，不碰模拟器。
// Android 绑定模块（:render-canvas）后续单独加入并依赖本模块；本模块一律不许出现 Android API。
// 依赖方向单向 :render -> :core-vm（渲染层消费引擎产物，`:core-vm` 永不反向依赖本模块）。
kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}
