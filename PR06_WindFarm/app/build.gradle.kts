import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Дані підпису release беруться з keystore.properties (не потрапляє в репозиторій, див. RELEASE.md)
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// На Windows Gradle-тести не знаходять класи (ClassNotFoundException), якщо шлях до build-теки
// містить кирилицю. Тому для такого шляху build-тека переноситься в ASCII-шлях у тимчасовій теці.
if (System.getProperty("os.name").startsWith("Windows") && rootDir.absolutePath.any { it.code > 127 }) {
    layout.buildDirectory.set(file("${System.getProperty("java.io.tmpdir")}/wf-build/${rootProject.name}/app"))
}

android {
    namespace = "ua.kpi.windfarm6"
    compileSdk = 35

    defaultConfig {
        applicationId = "ua.kpi.windfarm6"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "1.6.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            if (keystoreProps.isNotEmpty()) {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            enableUnitTestCoverage = true      // звіт покриття: ./gradlew createDebugUnitTestCoverageReport
            buildConfigField("boolean", "MINIFIED", "false")
        }
        release {
            isMinifyEnabled = true             // R8: стиснення, оптимізація, обфускація
            isShrinkResources = true           // видалення невикористаних ресурсів
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "MINIFIED", "true")
            // без keystore.properties release підписується debug-ключем, щоб збірка не падала
            signingConfig = if (keystoreProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    // Два варіанти застосунку: демо (обмежений) і повний
    flavorDimensions += "edition"
    productFlavors {
        create("demo") {
            dimension = "edition"
            applicationIdSuffix = ".demo"
            versionNameSuffix = "-demo"
            buildConfigField("int", "MAX_STATIONS", "3")
            resValue("string", "app_name", "Вітрова ЕС · Демо")
        }
        create("full") {
            dimension = "edition"
            buildConfigField("int", "MAX_STATIONS", "100")
            resValue("string", "app_name", "Вітрова ЕС · ПР6")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
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
    implementation(libs.kotlinx.serialization.json)   // JSON (заняття 22)
    implementation(libs.androidx.exifinterface)        // орієнтація фото (заняття 23)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
