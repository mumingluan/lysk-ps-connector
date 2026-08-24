plugins {
    id("com.android.application")
}

android {
    namespace = "com.axuan.lyskps"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.axuan.lyskps"
        minSdk = 23
        targetSdk = 28          // 低 target: 模块自身 UI 无存储/权限麻烦
        versionCode = 6
        versionName = "1.5"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // 模块只侧载不分发, 不理会 Play 商店的 targetSdk 政策检查
        checkReleaseBuilds = false
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "TrustAllX509TrustManager")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += "/META-INF/**"
    }

    sourceSets.getByName("main") {
        jniLibs.srcDir("src/main/jniLibs")
    }

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "LYSK-PS-$name.apk"
        }
    }
}

dependencies {
    // Xposed API 仅编译期需要, 运行时由 LSPosed 提供; 本地 jar 免依赖 api.xposed.info 仓库
    compileOnly(files("libs/xposed-api-82.jar"))
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
}
