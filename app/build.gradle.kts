import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 读取签名用的密钥库信息（keystore.properties，已被 .gitignore 忽略）。
 *
 * 为什么要单独放一个文件而不是把密码写在 build.gradle.kts 里：
 * build.gradle.kts 是要提交进 Git 的，密钥库密码显然不能跟着提交。
 * 用文件读进来的另一个好处是——没有这个文件时构建也能正常进行
 * （见下面的 hasReleaseSigning），别人 clone 下来照样能编译 debug 包。
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

/** 是否具备 release 签名条件。不具备时 release 构建会退回调试签名，仅供本机测试。 */
val hasReleaseSigning = keystoreProperties.getProperty("storeFile") != null

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

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // 打开代码压缩与资源压缩（R8）。
            // 这样打出来的正式包体积明显更小，也是发布版应有的形态。
            // 本项目没有使用反射，所以不需要额外的 keep 规则；
            // 如果以后引入反射/序列化框架，再补 proguard-rules.pro。
            optimization {
                enable = true
            }
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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