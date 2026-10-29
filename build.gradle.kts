// eraKcore 根构建脚本：仅做插件版本声明与全模块公共配置
plugins {
    kotlin("jvm") version "2.0.20" apply false
}

allprojects {
    group = "com.eratw.port"
    version = "0.1.0-SNAPSHOT"

    // CI 上失败摘要只给类名不给消息；完整栈/消息直接进 stdout，排障不用翻 XML 报告。
    tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = false
        }
    }
}
