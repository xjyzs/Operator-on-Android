plugins {
    alias(libs.plugins.android.library)
}
android{
    compileSdk = 37

    namespace = "com.xjyzs.hidden_api"

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        targetCompatibility = JavaVersion.VERSION_21
        sourceCompatibility = JavaVersion.VERSION_21
    }
}
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}
