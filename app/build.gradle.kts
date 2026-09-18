import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ---------------------------------------------------------------------------
// Подпись APK одним постоянным ключом.
//
// Порядок поиска параметров подписи:
//   1. keystore.properties в корне — его создаёт CI из секретов (приватный ключ);
//   2. keystore/keystore.properties — постоянный ключ, лежащий в репозитории.
//
// Оба типа сборки (debug и release) подписываются одним и тем же ключом, поэтому
// любой APK из CI ставится поверх ранее установленного без переустановки.
// ---------------------------------------------------------------------------
fun loadProperties(file: File): Properties = Properties().apply {
    if (file.exists()) file.inputStream().use { load(it) }
}

val ciSigningProps = loadProperties(rootProject.file("keystore.properties"))
val repoSigningProps = loadProperties(rootProject.file("keystore/keystore.properties"))
val signingProps = if (ciSigningProps.getProperty("storeFile") != null) ciSigningProps else repoSigningProps

val releaseStoreFile = signingProps.getProperty("storeFile")?.let { rootProject.file(it) }
val hasSigning = releaseStoreFile?.exists() == true

val versionProps = Properties().apply {
    val file = rootProject.file("version.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val baseVersionCode = versionProps.getProperty("versionCode")?.toIntOrNull() ?: 1
val baseVersionName = versionProps.getProperty("versionName") ?: "1.0.0"

// CI задаёт номер сборки (run_number) через -PversionCode / -PversionName
val ciVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull()
val ciVersionName = project.findProperty("versionName") as String?

android {
    namespace = "ai.arena.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.arena.mobile"
        minSdk = 26
        targetSdk = 34
        versionCode = ciVersionCode ?: baseVersionCode
        versionName = ciVersionName ?: baseVersionName
    }

    signingConfigs {
        if (hasSigning) {
            create("stable") {
                storeFile = releaseStoreFile
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
                    ?: signingProps.getProperty("storePassword")
                storeType = signingProps.getProperty("storeType") ?: "PKCS12"
                // Постоянный ключ => обновления ставятся поверх установленного APK
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("stable")
            }
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
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
        )
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
    // Блокировка входа: биометрия или код блокировки устройства
    implementation("androidx.biometric:biometric:1.1.0")

    // Org.json на JVM: в юнит-тестах android.jar отдаёт только заглушки
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
