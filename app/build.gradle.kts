plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.childmonitoringapp"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.childmonitoringapp"
        minSdk = 29
//        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true  // Nếu không dùng Compose, đổi thành false
    }
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    // Compose và test (giữ nguyên libs.* của bạn)
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

    // Dependencies cho app giám sát (sửa version AppCompat thành stable 1.6.1)
    implementation("androidx.appcompat:appcompat:1.6.1")  // Stable version, fix Unresolved AppCompatActivity
//    implementation("androidx.core:core-ktx:1.15.0")  // Fix ActivityCompat, ContextCompat
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("io.github.xch168:ffmpeg-kit-full-gpl:1.0.2") // Nếu đã fix JitPack

    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-storage-ktx:20.4.0")
    implementation("com.google.firebase:firebase-database-ktx:20.4.0")

}