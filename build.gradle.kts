// eraKcore 根构建脚本：仅做插件版本声明与全模块公共配置
plugins {
    kotlin("jvm") version "2.0.20" apply false
}

allprojects {
    group = "com.eratw.port"
    version = "0.1.0-SNAPSHOT"
}
