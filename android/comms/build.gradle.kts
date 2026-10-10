plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sahay.comms"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // Production Ed25519 public key (base64). Used to verify signed wires when the active pack has no key.
        buildConfigField("String", "PRODUCTION_PUBLIC_KEY_B64", "\"XFgaI5xjK7l1FvSKWASy9TEdO8YSZ6A1r3U8e/JxrHk=\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true   // Robolectric
        unitTests.isReturnDefaultValues = true       // android.util.Log in plain JVM tests
    }
}

// docs/wire_test_vectors.json (Person C) is copied next to the unit-test resources and never edited
// (git-ignored, see comms/.gitignore).
val wireVectors = rootProject.projectDir.resolve("../docs/wire_test_vectors.json")
val copyWireVectors by tasks.registering(Copy::class) {
    description = "Copies docs/wire_test_vectors.json into src/test/resources (does nothing if the file is missing)."
    from(wireVectors)
    into(layout.projectDirectory.dir("src/test/resources"))
}
tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach { dependsOn(copyWireVectors) }

dependencies {
    implementation(project(":core:contracts"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.tink.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
