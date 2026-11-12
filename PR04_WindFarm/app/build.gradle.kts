plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// На Windows Gradle-тести не знаходять класи (ClassNotFoundException), якщо шлях до build-теки
// містить кирилицю. Тому для такого шляху build-тека переноситься в ASCII-шлях у тимчасовій теці.
if (System.getProperty("os.name").startsWith("Windows") && rootDir.absolutePath.any { it.code > 127 }) {
    layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/wf-build/${rootProject.name}/app"))
}

android {
    namespace = "ua.kpi.windfarm4"
    compileSdk = 35
    defaultConfig {
        applicationId = "ua.kpi.windfarm4"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    // Мапа OpenStreetMap (без ключа API)
    implementation(libs.osmdroid)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
