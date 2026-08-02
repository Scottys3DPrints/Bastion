import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Signing comes from one of two places, never checked in either way:
//   - locally, keystore.properties beside this project;
//   - in CI, the BASTION_KEYSTORE_* environment variables fed from repo secrets.
// Both must resolve to the *same* key, because Android only installs an update
// over an app signed identically. That is the whole basis of "install once".
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

val ciKeystorePath: String? = System.getenv("BASTION_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val hasSigning = ciKeystorePath != null || keystoreProps.isNotEmpty()

/**
 * Version code must climb monotonically or Android refuses the install as a
 * downgrade. CI derives it from the run number, offset well clear of any
 * hand-built local APK so a CI build always supersedes one built on the laptop.
 */
val resolvedVersionCode = System.getenv("BASTION_VERSION_CODE")?.toIntOrNull()?.let { it + 1000 } ?: 19
val resolvedVersionName = System.getenv("BASTION_VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "0.19.0"

/** Where the app looks for its own updates. Overridable in Settings. */
val defaultUpdateUrl = System.getenv("BASTION_UPDATE_URL")?.takeIf { it.isNotBlank() }
    ?: "https://github.com/Scottys3DPrints/Bastion/releases/latest/download/bastion-update.json"

android {
    namespace = "com.bastion.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bastion.app"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
        vectorDrawables { useSupportLibrary = true }

        buildConfigField("String", "DEFAULT_UPDATE_URL", "\"$defaultUpdateUrl\"")
    }

    signingConfigs {
        if (hasSigning) {
            create("release") {
                if (ciKeystorePath != null) {
                    storeFile = file(ciKeystorePath)
                    storePassword = System.getenv("BASTION_KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("BASTION_KEY_ALIAS")
                    keyPassword = System.getenv("BASTION_KEY_PASSWORD")
                } else {
                    storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                    storePassword = keystoreProps.getProperty("storePassword")
                    keyAlias = keystoreProps.getProperty("keyAlias")
                    keyPassword = keystoreProps.getProperty("keyPassword")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
