plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.xjyzs.operator"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.xjyzs.operator"
        minSdk = 26
        targetSdk = 37
        versionCode = 6
        versionName = "2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        androidResources.localeFilters+= listOf("zh")

        signingConfigs {
            val hasSigningInfo = System.getenv("KEY_STORE_PASSWORD") != null &&
                    System.getenv("KEY_ALIAS") != null &&
                    System.getenv("KEY_PASSWORD") != null &&
                    file("${project.rootDir}/keystore.jks").exists()
            if (hasSigningInfo) {
                create("release") {
                    storeFile = file("${project.rootDir}/keystore.jks")
                    storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
                    keyAlias = System.getenv("KEY_ALIAS") ?: ""
                    keyPassword = System.getenv("KEY_PASSWORD") ?: ""
                    enableV1Signing=false
                }
            }
        }

        flavorDimensions += "abi"
        productFlavors {
            val signingConfig = if (signingConfigs.findByName("release") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            create("x86") {
                dimension = "abi"
                ndk { abiFilters.add("x86") }
                this.signingConfig = signingConfig
            }
            create("x86_64") {
                dimension = "abi"
                ndk { abiFilters.add("x86_64") }
                this.signingConfig = signingConfig
            }
            create("arm") {
                dimension = "abi"
                ndk { abiFilters.add("armeabi-v7a") }
                this.signingConfig = signingConfig
            }
            create("arm64Minsdk35") {
                dimension = "abi"
                ndk { abiFilters.add("arm64-v8a") }
                minSdk=35
                this.signingConfig = signingConfig
            }
            create("arm64Minsdk29") {
                dimension = "abi"
                ndk { abiFilters.add("arm64-v8a") }
                minSdk=29
                this.signingConfig = signingConfig
            }
            create("arm64Minsdk26") {
                dimension = "abi"
                ndk { abiFilters.add("arm64-v8a") }
                minSdk=26
                this.signingConfig = signingConfig
            }
            create("universal") {
                dimension = "abi"
                this.signingConfig = signingConfig
            }
        }

        buildTypes {
            release {
                isMinifyEnabled = true
                isShrinkResources = true
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                packaging {
                    resources {
                        excludes += setOf(
                            "DebugProbesKt.bin",
                            "kotlin-tooling-metadata.json",
                            "META-INF/**",
                            "kotlin/**"
                        )
                    }
                }
                tasks.configureEach {
                    doLast {
                        outputs.files.forEach { outputDir ->
                            val filesToDelete = setOf("PublicSuffixDatabase.list")
                            for (i in filesToDelete){
                                val file=outputDir.resolve(i)
                                if (file.exists()) {
                                    file.delete()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    buildFeatures {
        compose = true
        aidl = true
    }
}

dependencies {
    compileOnly(project(":hidden-api"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.wear.ongoing)
    implementation(libs.androidx.appcompat)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("com.google.code.gson:gson:2.14.0")
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.savedstate.ktx)
}