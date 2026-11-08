plugins {
    alias(libs.plugins.kotlin.jvm)
}

// M4 渲染层：纯 JVM（ADR-001 的重设计区 + AGENTS.md 铁律），全部回归在桌面跑，不碰模拟器。
// Android 绑定模块（:render-canvas）后续单独加入并依赖本模块；本模块一律不许出现 Android API。
// 依赖方向单向 :render -> :core-vm（渲染层消费引擎产物，`:core-vm` 永不反向依赖本模块）。
kotlin {
    // A-5：17 走 catalog 的 versions.jvmTarget（全仓 JVM 面同源）。
    jvmToolchain(libs.versions.jvmTarget.get().toInt())
}

dependencies {
    implementation(project(":core-vm"))
    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    maxHeapSize = "1g"
    System.getenv().filterKeys { it.startsWith("ERATW_") }.forEach { (k, v) -> environment(k, v) }
}
