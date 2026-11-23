plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

val requestedVersionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
val requestedVersionName = (project.findProperty("versionName") as String?)?.takeIf { it.isNotBlank() } ?: "0.5.0-dev"

val releaseKeystorePath = System.getenv("ANDROID_KEYSTORE_PATH")?.takeIf { it.isNotBlank() }
val releaseKeystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")?.takeIf { it.isNotBlank() }
val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
val hasReleaseSigning = releaseKeystorePath != null &&
    file(releaseKeystorePath).isFile &&
    releaseKeystorePassword != null &&
    releaseKeyAlias != null &&
    releaseKeyPassword != null

android {
    namespace = "io.github.oinzc.flamecontractor"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.oinzc.flamecontractor"
        minSdk = 26
        targetSdk = 35
        versionCode = requestedVersionCode
        versionName = requestedVersionName
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val verifySelectorBundleSync = tasks.register("verifySelectorBundleSync") {
    val backendBundle = rootProject.file("../backend/app/execution/selectors_bundle.json")
    val androidBundle = file("src/main/assets/selectors_bundle.json")
    inputs.file(backendBundle)
    inputs.file(androidBundle)
    doLast {
        if (!backendBundle.exists()) {
            throw GradleException("Missing backend selector bundle: ${backendBundle.absolutePath}")
        }
        if (!androidBundle.exists()) {
            throw GradleException("Missing Android selector bundle: ${androidBundle.absolutePath}")
        }
        val expected = backendBundle.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val actual = androidBundle.readText(Charsets.UTF_8).replace("\r\n", "\n")
        if (expected != actual) {
            throw GradleException(
                "selectors_bundle.json mismatch between backend/app/execution/selectors_bundle.json " +
                    "and android/app/src/main/assets/selectors_bundle.json.",
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifySelectorBundleSync)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.webkit)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
}
