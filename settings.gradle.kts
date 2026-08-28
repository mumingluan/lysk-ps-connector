pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    // 不设 FAIL_ON_PROJECT_REPOS: 本机有全局阿里云镜像 init 脚本会往项目里加仓库
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LYSK-PS-Connector"
include(":app")
