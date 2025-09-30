plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
//    id("org.jetbrains.kotlin.plugin.compose") // Không cần version, đã khai báo trong settings.gradle.kts
    id("com.google.gms.google-services")
}

android {
    buildFeatures { compose = true }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14" // hợp với Kotlin 1.9.24
    }
    namespace = "com.example.childmonitoringapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "ParentMonitoringApp_1.a1"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

//        buildConfigField("String", "API_BASE_URL", "\"http://thaolinh-001-site1.qtempurl.com/\"")
        buildConfigField("String", "API_KEY", "\"demo-secret-key\"")
        buildConfigField("String", "API_BASE_URL", "\"http://159.223.73.53/\"")

    }

    buildTypes {
        debug {
            // dùng CÙNG key như server hiện tại
            buildConfigField("String","API_KEY","\"demo-secret-key\"")
        }
        release {
            buildConfigField("String","API_KEY","\"demo-secret-key\"")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
        compose = true // Bắt buộc nếu dùng Compose
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    // Compose và test
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // WorkManager cho tác vụ nền
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // AppCompat và Core KTX
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")

    // OkHttp (nếu cần server khác)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // FFmpeg (nếu cần xử lý video)
//    implementation("io.github.xch168:ffmpeg-kit-full-gpl:1.0.2")
//    implementation("com.arthenica:ffmpeg-kit-full-gpl:6.0-2.LTS")
    // Firebasex
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-storage-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx") // Tùy chọn


    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}