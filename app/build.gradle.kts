import java.util.Properties

plugins {
    // AGP 9 содержит встроенную поддержку Kotlin, отдельный плагин kotlin-android не нужен.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "io.github.linguaplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.linguaplayer"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.0.0-rc2"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    /*
     * Подпись release-сборки. Ключ — личный секрет владельца, в репозиторий не попадает.
     * Источники (первый найденный):
     *  1) keystore.properties в корне проекта (исключён из git):
     *     storeFile=/путь/к/lingua-release.jks
     *     storePassword=...
     *     keyAlias=lingua
     *     keyPassword=...
     *  2) переменные окружения LINGUA_KEYSTORE_FILE, LINGUA_KEYSTORE_PASSWORD,
     *     LINGUA_KEY_ALIAS, LINGUA_KEY_PASSWORD (для GitHub Actions).
     * Без ключа собирается неподписанный app-release-unsigned.apk.
     */
    val keystoreFile = rootProject.file("keystore.properties")
    val signing: Map<String, String>? = when {
        keystoreFile.exists() -> Properties().apply { keystoreFile.inputStream().use { load(it) } }
            .let { p -> p.stringPropertyNames().associateWith { p.getProperty(it) } }
        System.getenv("LINGUA_KEYSTORE_FILE") != null -> mapOf(
            "storeFile" to System.getenv("LINGUA_KEYSTORE_FILE"),
            "storePassword" to System.getenv("LINGUA_KEYSTORE_PASSWORD").orEmpty(),
            "keyAlias" to System.getenv("LINGUA_KEY_ALIAS").orEmpty(),
            "keyPassword" to System.getenv("LINGUA_KEY_PASSWORD").orEmpty(),
        )
        else -> null
    }
    signingConfigs {
        if (signing != null) {
            create("release") {
                storeFile = file(signing.getValue("storeFile"))
                storePassword = signing["storePassword"]
                keyAlias = signing["keyAlias"]
                keyPassword = signing["keyPassword"]
            }
        }
    }

    buildTypes {
        release {
            if (signing != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            // Необязательно: своё зеркало Maven для загрузки Android-рантайма Robolectric
            // (./gradlew test -ProbolectricRepoUrl=https://...). По умолчанию — Maven Central.
            providers.gradleProperty("screenshotsDir").orNull?.let {
                test.systemProperty("lingua.screenshots", it)
            }
            providers.gradleProperty("robolectricRepoUrl").orNull?.let {
                test.systemProperty("robolectric.dependency.repo.url", it)
            }
        }
    }
}

room {
    // Схемы БД сохраняются в репозитории — нужны для проверки миграций.
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.work.runtime)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
}
