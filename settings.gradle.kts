// eraKcore 根工程 —— Kotlin 重写的 ERA 运行时（纯 JVM，零 Android 依赖）
//
// 模块：
//   :core-vm        ERA 语义虚拟机（ERB 字节码执行、变量/存档/内建函数）
//   :render         纯 JVM 渲染层（字形缓存 / 网格 / 命中区 / 显示列表）
//   :tools:erbcc    ERB → erbc 字节码容器编译器
//   :tools:vmdbg    面向 agent 的无交互 VM 调试 CLI
//   :tools:uipreview 桌面渲染预览（浏览器贴帧）
//   :tools:fontsubset 字体内子集生成（维护 resources/fonts 的再生成工具）

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "eraKcore"

include(":core-vm")
include(":render")
include(":tools:erbcc")
include(":tools:vmdbg")
include(":tools:uipreview")
// :tools:fontsubset 是纯 Python 工具目录（字体内子集再生成），不是 Gradle 模块。
