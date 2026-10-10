plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.sahay.engine"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true   // Robolectric
    }
}

// Person C's small test pack is copied next to the unit-test resources (git-ignored, see engine/.gitignore).
// If it is not in the repo yet, the tests build an equivalent pack on the fly (see TestPacks).
val sampleSqlite = rootProject.projectDir.resolve("../samples/mahabalipuram-sample.sqlite")
val copySampleSqlite by tasks.registering(Copy::class) {
    description = "Copies samples/mahabalipuram-sample.sqlite into src/test/resources (does nothing if the file is missing)."
    from(sampleSqlite)
    into(layout.projectDirectory.dir("src/test/resources"))
}
tasks.matching { it.name.endsWith("UnitTestJavaRes") }.configureEach { dependsOn(copySampleSqlite) }

dependencies {
    implementation(project(":core:contracts"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.play.services.location)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.process)

    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.maplibre.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
