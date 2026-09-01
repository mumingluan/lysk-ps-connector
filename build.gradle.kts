// 改编自 GenshinProxy (Xuoos) 的工程骨架, 升级到 AGP 8 以支持 JDK21
plugins {
    id("com.android.application") version "8.4.2" apply false
}

// The Windows wrapper supplies an external build root. This avoids OneDrive/
// AppContainer ACLs that allow files to be written but prevent Gradle from
// deleting stale output directories on the next incremental build.
System.getProperty("lysk.gradle.buildRoot")?.takeIf { it.isNotBlank() }?.let { buildRoot ->
    layout.buildDirectory.set(file("$buildRoot/root"))
    subprojects {
        layout.buildDirectory.set(file("$buildRoot/${project.name}"))
    }
}
