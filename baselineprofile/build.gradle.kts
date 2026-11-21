plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.android.built.in1.kotlin)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.xjyzs.baselineprofile"
    compileSdk {
        version = release(37)
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    defaultConfig {
        minSdk = 29
        targetSdk = 37

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        missingDimensionStrategy(
            "abi",
            providers.gradleProperty("baselineProfileFlavor").getOrElse("universal")
        )
    }

    targetProjectPath = ":app"
}

// This is the configuration block for the Baseline Profile plugin.
// You can specify to run the generators on a managed devices or connected devices.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.espresso.core)
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)?.applicationId }
        )
    }
}

tasks.withType<Test>().configureEach {
    doFirst {
        systemProperty("android-test.uninstall-after-tests", "false")
    }
}