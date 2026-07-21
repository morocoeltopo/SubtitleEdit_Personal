import groovy.json.JsonSlurper

plugins {
    id("com.android.application")
}

fun registerApkExport(variantName: String) {
    val taskName = "export${variantName.replaceFirstChar { it.uppercase() }}Apks"
    val exportTask = tasks.register(taskName) {
        doLast {
            val outputDir = layout.buildDirectory.dir("outputs/apk/$variantName").get().asFile
            val metadataFile = outputDir.resolve("output-metadata.json")
            if (!metadataFile.isFile) {
                logger.lifecycle("未找到 $variantName APK 元数据，跳过导出")
                return@doLast
            }

            val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
            val elements = metadata["elements"] as? List<*> ?: return@doLast
            val versionName = android.defaultConfig.versionName ?: "unknown"
            val exportDir = outputDir.resolve("export").apply { mkdirs() }

            elements.forEach { element ->
                val item = element as? Map<*, *> ?: return@forEach
                val sourceName = item["outputFile"] as? String ?: return@forEach
                val filters = item["filters"] as? List<*>
                val architecture = filters
                    ?.mapNotNull { (it as? Map<*, *>)?.get("value") as? String }
                    ?.firstOrNull()
                    ?: "universal"
                val source = outputDir.resolve(sourceName)
                if (source.isFile) {
                    source.copyTo(
                        exportDir.resolve("SubtitleEdit-release-$versionName-$architecture.apk"),
                        overwrite = true
                    )
                }
            }
            logger.lifecycle("已导出 $variantName APK：${exportDir.absolutePath}")
        }
    }
    tasks.matching {
        it.name == "assemble${variantName.replaceFirstChar { char -> char.uppercase() }}"
    }.configureEach {
        finalizedBy(exportTask)
    }
}

registerApkExport("debug")
registerApkExport("release")

android {
    namespace = "com.subtitleedit"
    compileSdk = 34
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.subtitleedit"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                targets("model_archive_jni")
                arguments("-DANDROID_STL=c++_static")
            }
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }

    packaging {
        jniLibs {
            // sherpa-onnx and the standalone demixing runtime both provide ORT/libc++.
            // Keep a single copy in the APK; the demixing code remains isolated at API level.
            pickFirsts += setOf("**/libonnxruntime.so", "**/libc++_shared.so")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // DocumentFile - for file operations in selected directories
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // FFmpegKit - for audio decoding to PCM
    implementation("com.arthenica:ffmpeg-kit-min:6.0")

    // OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // tar.bz2 extraction for downloadable speech models
    implementation("org.apache.commons:commons-compress:1.26.1")

    // Standalone ONNX Runtime Java API for HTDemucs vocal separation.
    // Match the ONNX Runtime shared library already shipped with sherpa-onnx.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.27.0")

    // sherpa-onnx for Whisper speech recognition
    // Kotlin API 源码已集成到 app/src/main/java/com/k2fsa/sherpa/onnx/
    // Native 库已放置到 app/src/main/jniLibs/
    // 无需额外依赖配置

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
