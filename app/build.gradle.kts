import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Python 可执行文件路径：优先用 gradle.properties/local.properties 或环境变量，避免硬编码到某台机器
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val defaultPython = if (System.getProperty("os.name").lowercase().contains("win")) "python.exe" else "python3"
val pythonExecutable: String = (project.findProperty("pythonExecutable") as? String)
    ?: System.getenv("PYTHON_EXECUTABLE")
    ?: localProps.getProperty("pythonExecutable")
    ?: defaultPython

// PRoot 在 Android 10+ targetSdk>=29 的 W^X 限制下可能无法执行 rootfs 内二进制。
// 侧载构建 Linux 环境时可加 -PtargetSdk=28 绕过；默认仍保持现代 target。
val targetSdkVersion: Int = (project.findProperty("targetSdk") as? String)?.toIntOrNull() ?: 35

android {
    namespace = "com.example.aichat"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.aichat"
        minSdk = 26
        targetSdk = targetSdkVersion
        versionCode = 273
        versionName = "2.60.4"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("../debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        create("release") {
            val ks = rootProject.file("release.keystore")
            if (ks.exists()) {
                storeFile = ks
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as? String)
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as? String)
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // 有正式 keystore 用正式签名，否则退回 debug 签名保证 APK 可安装
            signingConfig = if (file("../release.keystore").exists()) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }

    packaging {
        jniLibs {
            // 让 libproot_exec.so 实际解压到 nativeLibraryDir，便于以可执行文件方式启动
            useLegacyPackaging = true
        }
    }
}

// 这些运行时必须内置进 APK，避免用户首次使用时还要联网下载。
val requiredBundledAssets = listOf(
    "src/main/assets/linux/alpine-aarch64.tar.gz",
    "src/main/assets/linux/alpine-x86_64.tar.gz",
    "src/main/assets/vm/alpine-virt.iso",
    "src/main/assets/vm/vmlinuz-virt",
    "src/main/assets/vm/initramfs-virt"
)

tasks.register("checkBundledLinuxAssets") {
    doLast {
        val missing = requiredBundledAssets.filter { !file(it).exists() || file(it).length() == 0L }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "缺少内置 Linux/VM 资源：${missing.joinToString()}；" +
                    "请先运行 fetch-linux-runtime.ps1 和 fetch-vm-assets.py 生成后再打包。"
            )
        }
        requiredBundledAssets.forEach { println("bundled: $it (${file(it).length()} bytes)") }
    }
}

tasks.named("preBuild") {
    dependsOn("checkBundledLinuxAssets")
}

chaquopy {
    defaultConfig {
        version = "3.13"
        buildPython(pythonExecutable)
        pip {
            // lunar_python 在 PyPI 只有源码包，Chaquopy 只接受 wheel，这里用本地构建的 wheel
            options("--find-links", file("../python-wheels").absolutePath)
            install("pandas")
            install("numpy")
            install("matplotlib")
            install("Pillow")
            install("openpyxl")
            install("python-docx")
            install("requests")
            install("beautifulsoup4")
            install("lxml")
            install("regex")
            install("skyfield")
            // 命理师：八字/农历/节气/干支/大运（纯 Python）
            install("lunar_python")
        }
    }
}

dependencies {
    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)

    // Compose UI
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Activity Compose
    implementation("androidx.activity:activity-compose:1.8.2")

    // Lifecycle + ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    // OkHttp（网络请求全部原生走 OkHttp；Retrofit 已移除——其 suspend 泛型签名会被 R8 剥离导致崩溃）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Gson（显式依赖；之前由 Retrofit converter-gson 传递引入）
    implementation("com.google.code.gson:gson:2.10.1")

    // Alpine rootfs tar.gz 解压（无额外 Android 端依赖问题）
    implementation("org.apache.commons:commons-compress:1.21")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Debug tooling
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Image loading
    implementation("io.coil-kt:coil-compose:2.6.0")
}
