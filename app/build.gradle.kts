plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.longpresstool"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.longpresstool"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // 生成 BuildConfig，用来区分 debug / release。
        // 本项目的 debug 构建里有一个"用 adb 直接触发长按"的排查入口，
        // 必须保证它不会进入正式包，所以需要 BuildConfig.DEBUG。
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    // 悬浮窗里的传统 View 界面用到 MaterialCardView 等 Material Components 控件
    implementation(libs.android.material)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    // 让 Compose 能感知生命周期地收集 StateFlow（collectAsStateWithLifecycle）
    implementation(libs.androidx.lifecycle.runtime.compose)
    // Compose 里的 viewModel() 需要它
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}