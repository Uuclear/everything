plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.everything.eve"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.everything.eve"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        vectorDrawables { useSupportLibrary = true }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }

    // JVM 单测：Android SDK stub API 默认抛 RuntimeException("Method ... not mocked")，
    // 这里开启 returnDefaultValues=true 让 JSONObject.put / Context.getString / Application
    // 等返回零值（null / 0 / false / empty List）以确保单元测试可运行（org.json.put 抛错
    // 是财务附件仓库测试最常见的根因——records 通道元数据 JSON 序列化调用 JSONObject）。
    //
    // includeAndroidResources：让 Robolectric 把 androidx / material3 library 内部字符串
    // 资源（如 ModalBottomSheet Scrim 用的 close_sheet 等）合并进沙箱 resources.arsc。
    // 否则 manifest=NONE 的 Compose Sheet 测试会因 library string NotFoundException 全军覆没。
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.moshi)
    implementation(libs.moshi.kotlin)
    implementation(libs.okhttp.logging)

    // libsodium：与 Go/Web 完全相同的 Argon2id + XChaCha20-Poly1305 原语
    implementation(libs.lazysodium)
    implementation(libs.jna)

    // B8：小票 OCR —— CameraX 预览/取帧 + ML Kit 端侧中文文字识别（自包含 AAR，无云依赖）
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)
    implementation(libs.mlkit.text.recognition)
    // 中文识别模型构件：提供 ChineseTextRecognizerOptions（text-recognition 本体仅含 Latin 壳）
    implementation(libs.mlkit.text.recognition.chinese)

    // JVM 单测：Crockford 向量等不依赖 native 的纯算法
    testImplementation(libs.junit)
    // 真实 org.json 实现：覆盖 android.jar stub 让 JSONObject.put / .toString 工作
    testImplementation(libs.json)
    // 协程测试：Dispatchers.setMain（B3 附件 VM 的 viewModelScope.launch 单测）
    testImplementation(libs.kotlinx.coroutines.test)
    // JVM 单测锚点向量：lazysodium-java 自带桌面 libsodium（win64/linux64/mac），
    // 与 lazysodium-android 同版本同原语，仅测试类路径可见（阶段 4a Task 5）
    testImplementation(libs.lazysodium.java)
    // B8：Robolectric 在 JVM 内模拟 Android 运行环境，支撑 Compose Sheet 本地单测
    testImplementation(libs.robolectric)
    // B8：createComposeRule 驱动 Sheet 组合与节点断言（构件复用 androidTest
    // 同款 ui-test-junit4，版本随 compose BOM；不开启 includeAndroidResources）
    testImplementation(libs.androidx.ui.test.junit4)

    // Instrumented：Room v1→v2 迁移（需连接设备/模拟器）
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.room.testing)

    // Compose UI Test（阶段 4b Task 6 / TR-6.4）
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.test.manifest)
}
