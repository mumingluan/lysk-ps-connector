plugins {
    id("com.android.application")
}

android {
    namespace = "com.axuan.lyskps"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.axuan.lyskps"
        minSdk = 24
        targetSdk = 28          // 低 target: 客户端 UI 无额外存储权限要求
        versionCode = 11
        versionName = "2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    lint {
        // 客户端只侧载不分发, 不理会 Play 商店的 targetSdk 政策检查
        checkReleaseBuilds = false
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "TrustAllX509TrustManager")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
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
                "LYSK-PS-Connector-$name.apk"
        }
    }
}

dependencies {
    implementation("org.bouncycastle:bcpkix-jdk18on:1.85")
    implementation("org.tukaani:xz:1.10")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
