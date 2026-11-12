plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp) // кодогенерація Room
}

// На Windows unit-тести Gradle не знаходять класи, якщо шлях до build-теки містить кирилицю:
// для такого шляху build-тека переноситься в ASCII-шлях у тимчасовій теці.
if (System.getProperty("os.name").startsWith("Windows") && rootDir.absolutePath.any { it.code > 127 }) {
    layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/tp-build/${rootProject.name}/app"))
}

android {
    namespace = "ua.kpi.travel"
    compileSdk = 35
    defaultConfig {
        applicationId = "ua.kpi.travel"
        minSdk = 26          // java.time без desugaring
        targetSdk = 34
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
    testOptions { unitTests.isReturnDefaultValues = true }
}

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

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
    // Room — локальна БД подорожей
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Retrofit — геокодування, прогноз погоди (Open-Meteo), курси валют (open.er-api.com)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp.logging)
    // WorkManager — нагадування про активності
    implementation(libs.androidx.work.runtime.ktx)
    // OpenStreetMap — мапа маршруту
    implementation(libs.osmdroid)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
