plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.hpu.selfcammonitor"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.hpu.selfcammonitor"
        minSdk = 25
        targetSdk = 36
        versionCode = 8
        versionName = "2.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 内网穿透二进制（cloudflared / frpc）以 lib*.so 形式打包进 jniLibs，
    // 安装时由系统解压到 nativeLibraryDir（只读可执行），绕开 Android 10+ 的 W^X 限制。
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 避免 build 时对 cloudflared/frpc 这俩 Go 二进制做 strip 导致损坏
            keepDebugSymbols += "**/*.so"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.camera.view)
    implementation("androidx.cardview:cardview:1.0.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    val camerax_version = "1.6.1"
    implementation("androidx.camera:camera-core:${camerax_version}")
    implementation("androidx.camera:camera-camera2:${camerax_version}")
    implementation("androidx.camera:camera-lifecycle:${camerax_version}")
    implementation("androidx.camera:camera-view:${camerax_version}")

    // Lifecycle Service (用于让 Service 拥有 Lifecycle)
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")

    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // 网络请求（用于发送报警）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("androidx.camera:camera-video:${camerax_version}")
}