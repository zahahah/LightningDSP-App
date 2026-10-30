plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("kotlin-kapt")
    id("com.google.devtools.ksp") version AndroidConfig.kspVersion
    id("dev.rikka.tools.refine") version AndroidConfig.rikkaRefineVersion
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {

    val SUPPORTED_ABIS = setOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
    compileSdk = AndroidConfig.compileSdk
    project.setProperty("archivesBaseName", "RootlessLightningDSP-v${AndroidConfig.versionName}")

    defaultConfig {
        targetSdk = AndroidConfig.targetSdk
        versionCode = AndroidConfig.versionCode
        versionName = AndroidConfig.versionName

        manifestPlaceholders["label"] = "RootlessLightningDSP"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "COMMIT_COUNT", "\"${getCommitCount()}\"")
        buildConfigField("String", "COMMIT_SHA", "\"${getGitSha()}\"")
        buildConfigField("String", "BUILD_TIME", "\"${getBuildTime()}\"")
        buildConfigField("boolean", "PREVIEW", "false")
        buildConfigField("boolean", "PLUGIN", "false")

        externalNativeBuild {
            cmake {
                arguments.addAll(listOf("-DANDROID_ARM_NEON=ON"))
                cFlags.add("-std=gnu11 -Wno-incompatible-pointer-types -Wno-implicit-int -Wno-implicit-function-declaration")
            }
        }

        ndk {
            abiFilters += SUPPORTED_ABIS
        }
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-${getCommitCount()}"
        }
        getByName("release") {
            //proguardFiles("proguard-android-optimize.txt", "proguard-rules.pro")
            isMinifyEnabled = false
            isShrinkResources = false
            // Release artifacts must be signed separately with the authorized owner key.
            signingConfig = null
        }
        create("preview") {
            initWith(getByName("release"))
            // Installable CI previews use development identity, never the owner release key.
            signingConfig = signingConfigs.getByName("debug")
            buildConfigField("boolean", "PREVIEW", "true")

            val debugType = getByName("debug")
            versionNameSuffix = debugType.versionNameSuffix
            matchingFallbacks.add("release")
        }
    }

    flavorDimensions += "version"
    flavorDimensions += "dependencies"
    productFlavors {
        create("fdroid") {
            dimension = "dependencies"
            buildConfigField("boolean", "FOSS_ONLY", "true")
        }
        create("full") {
            dimension = "dependencies"
            buildConfigField("boolean", "FOSS_ONLY", "false")
        }

        create("rootless") {
            dimension = "version"

            manifestPlaceholders["label"] = "RootlessLightningDSP"
            applicationId = "me.timschneeberger.rootlessjamesdsp"
            AndroidConfig.minSdk = 29
            minSdk = AndroidConfig.minSdk
            buildConfigField("boolean", "ROOTLESS", "true")
            buildConfigField("boolean", "PLUGIN", "false")
        }
        create("root") {
            dimension = "version"

            manifestPlaceholders["label"] = "LightningDSP"
            project.setProperty("archivesBaseName", "LightningDSP-v${AndroidConfig.versionName}-${AndroidConfig.versionCode + 4}")
            applicationId = "com.lightning.dsp"
            // Root package identity changed; this version applies only to the new package.
            versionCode = AndroidConfig.versionCode + 4
            versionName = AndroidConfig.versionName
            AndroidConfig.minSdk = 26
            minSdk = AndroidConfig.minSdk
            buildConfigField("boolean", "ROOTLESS", "false")
            buildConfigField("boolean", "PLUGIN", "false")
        }
        create("plugin") {
            dimension = "version"

            AndroidConfig.minSdk = 26
            minSdk = AndroidConfig.minSdk
            buildConfigField("boolean", "ROOTLESS", "false")
            buildConfigField("boolean", "PLUGIN", "true")
        }
    }

    sourceSets {
        // Use different app icon for non-release builds
        getByName("debug").res.srcDirs("src/debug/res")
    }

    // Export multiple CPU architecture split apks
    splits {
        abi {
            isEnable = true
            reset()
            include(*SUPPORTED_ABIS.toTypedArray())
            isUniversalApk = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += "ObsoleteSdkInt"
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
        compose = true
        // Disable unused features
        // The root flavor consumes LightningDSP's optional ldsp.control Binder API.
        aidl = true
        renderScript = false
        shaders = false
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    namespace = "lightning.dsp"
}

dependencies {
    add("rootCompileOnly", "de.robv.android.xposed:api:82")
    // Compose is scoped to the root Volume Manager UI; other flavors keep their existing screens.
    "rootImplementation"(platform("androidx.compose:compose-bom:2024.12.01"))
    "rootImplementation"("androidx.activity:activity-compose:1.10.0")
    "rootImplementation"("androidx.compose.ui:ui")
    "rootImplementation"("androidx.compose.foundation:foundation")
    "rootImplementation"("androidx.compose.material3:material3")
    "rootImplementation"("androidx.compose.material:material-icons-extended")
    // Compose is enabled at the Android module level, so keep its compiler runtime on
    // the non-root compile classpaths without packaging the UI libraries in those APKs.
    "rootlessCompileOnly"(platform("androidx.compose:compose-bom:2024.12.01"))
    "rootlessCompileOnly"("androidx.compose.runtime:runtime")
    "pluginCompileOnly"(platform("androidx.compose:compose-bom:2024.12.01"))
    "pluginCompileOnly"("androidx.compose.runtime:runtime")

    // Kotlin extensions
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.0.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.0")

    // AndroidX
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.4")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.databinding:databinding-runtime:8.7.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.mediarouter:mediarouter:1.7.0")

    // Material
    implementation("com.google.android.material:material:1.9.0")

    // Dependency injection
    implementation("io.insert-koin:koin-android:3.3.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Web API client
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.9.0")

    // Logging
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.github.bastienpaulfr:Treessence:1.0.0")

    // IO
    implementation("org.kamranzafar:jtar:2.3")
    implementation("com.squareup.okio:okio:3.6.0")

    // Room databases
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")

    // Script editor
    implementation(project(":codeview"))

    // Shizuku
    implementation("dev.rikka.shizuku:api:${AndroidConfig.shizukuVersion}")
    implementation("dev.rikka.shizuku:provider:${AndroidConfig.shizukuVersion}")

    // Used for backup file access
    implementation("com.github.tachiyomiorg:unifile:17bec43")

    // Root APIs
    "rootImplementation"("com.github.topjohnwu.libsu:core:5.0.4")

    // Hidden APIs
    implementation("dev.rikka.tools.refine:runtime:${AndroidConfig.rikkaRefineVersion}")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
    compileOnly(project(":hidden-api-refined"))
    implementation(project(":hidden-api-impl"))

    // Debug utilities
    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.10")
    debugImplementation("com.plutolib:pluto:2.0.9")
    "previewImplementation"("com.plutolib:pluto-no-op:2.0.9")
    releaseImplementation("com.plutolib:pluto-no-op:2.0.9")
    debugImplementation("com.plutolib.plugins:bundle-core:2.0.9")
    "previewImplementation"("com.plutolib.plugins:bundle-core-no-op:2.0.9")
    releaseImplementation("com.plutolib.plugins:bundle-core-no-op:2.0.9")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
