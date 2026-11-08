plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.pishi.termbridge"
    compileSdk = 35

    defaultConfig {
        // Working application ID for the foundation. It can still be
        // changed before the first public release; after release it
        // must not change. See docs/DEVELOPMENT.md.
        applicationId = "com.pishi.termbridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 5
        versionName = "0.1.4-foundation"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Item R-06: release signing comes from the environment
    // (CI secrets), never from files in the repo. When the
    // variables are absent the release build type simply stays
    // unsigned, so local and CI debug flows are unaffected.
    // Secret names and setup: docs/RELEASE_SIGNING.md.
    signingConfigs {
        create("release") {
            val keystoreFile = System.getenv("TB_KEYSTORE_FILE")
            if (!keystoreFile.isNullOrBlank()) {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("TB_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("TB_KEY_ALIAS")
                keyPassword = System.getenv("TB_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
        release {
            // Item R-06: R8 minification + resource shrinking
            // are ON. The keep rules in proguard-rules.pro are
            // load-bearing — most importantly Bouncy Castle,
            // which SshCryptoProvider loads by reflection
            // (Class.forName), a reference R8 cannot see.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Standard Android defaults, plus the OSGi manifest
            // that sshj's Bouncy Castle jars each ship under
            // META-INF/versions/9 (duplicate across bcprov,
            // bcpkix, bcutil; unused on Android, so excluding
            // it cannot change runtime behaviour).
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            // Audit #3 A-19 follow-up (Android CI run #70, 2026-11-06):
            // Bouncy Castle 1.85 ships META-INF/LICENSE.md in each of
            // bcprov, bcpkix and bcutil, and the build fails with
            // DuplicateRelativeFileException before any test or APK
            // step. The license files are identical notices, unused
            // at runtime, so excluding the duplicate is safe.
            excludes += "META-INF/LICENSE.md"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    // Prompt 04 persistence + credential storage. Room is the ADR-02
    // choice; the compiler runs via KSP only, never as a runtime dep.
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    // Prompt 05 SSH transport. ADR-03 choice: sshj 0.40.0, Apache 2.0.
    // Verified against the ADR before adding: version, group, and
    // licence match the prompt-01 record (13 May 2025 sources).
    implementation(libs.sshj)
    implementation(libs.bouncycastle.bcprov)
    constraints {
        // A-19 (was R-07): force Bouncy Castle 1.85 across the
        // whole graph. sshj 0.40.0 pulls BC 1.84 transitively,
        // which carries GHSA-9pwp and GHSA-qp49 (bcprov) and
        // GHSA-wg6q (bcpkix); 1.85 clears all three. bcpkix is
        // transitive-only in this graph, so without the explicit
        // constraint it would stay behind the direct bcprov
        // declaration. (bcutil follows bcpkix 1.85's own POM.)
        implementation(libs.bouncycastle.bcprov)
        implementation(libs.bouncycastle.bcpkix)
    }
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    // Compose BOM must be declared inside dependencies {}, not at the
    // top level (a top-level platform() call fails in Kotlin DSL).
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    // Prompt 03 design system: navigation and screen icons. This is
    // the only new dependency in prompt 03; no protocol library here.
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    // Prompt 11 import/export: JSON tree API only (JsonObject /
    // JsonArray builders and parsers). No @Serializable plugin is
    // needed, because no generated serializer is used — the codec
    // walks the tree explicitly so unknown fields can be rejected
    // fail-closed. Apache 2.0, same family as kotlinx-coroutines.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
