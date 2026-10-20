plugins { alias(libs.plugins.android.application); alias(libs.plugins.kotlin.compose) }

// Local builds keep their ordinary version. One workflow owns the CI counter;
// all build/test jobs use its same number and exact checkout SHA.
val ciNumber = providers.environmentVariable("SOLSLEEP_BUILD_NUMBER").orNull
val ciSha = providers.environmentVariable("SOLSLEEP_BUILD_SHA").orNull
val ciAttempt = providers.environmentVariable("SOLSLEEP_BUILD_ATTEMPT").orNull
require(listOf(ciNumber, ciSha, ciAttempt).all { it == null } ||
    listOf(ciNumber, ciSha, ciAttempt).all { it != null }) { "CI version requires number, attempt and SHA" }
val ciVersionCode = ciNumber?.let {
    require(it.matches(Regex("[1-9][0-9]{0,9}"))) { "Invalid CI build number" }
    val number = it.toLong()
    require(number <= 20_999_899L) { "CI version counter exhausted" }
    require(ciAttempt!!.matches(Regex("[1-9][0-9]?"))) { "Invalid CI build attempt" }
    require(ciSha!!.matches(Regex("[0-9a-f]{40}"))) { "Invalid CI source SHA" }
    (10_000L + number * 100 + ciAttempt.toInt()).toInt()
}
val privateDebugStore = providers.environmentVariable("SOLSLEEP_DEBUG_KEYSTORE").orNull
android {
    namespace = "dev.bananajeans.solsleep"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.bananajeans.solsleep"
        minSdk = 29
        targetSdk = 37
        versionCode = ciVersionCode ?: 1
        versionName = if (ciNumber == null) "0.1.0-dev" else "0.1.0-dev.$ciNumber.$ciAttempt.${ciSha!!.take(12)}"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        if (privateDebugStore != null) {
            storeFile = file(privateDebugStore)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    sourceSets.getByName("androidTest").assets.directories.add(rootProject.file("schemas").path)
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    lint { abortOnError = true; checkReleaseBuilds = true }
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":core"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity)
    implementation(libs.lifecycle)
    implementation(libs.lifecycle.runtime)
    implementation(libs.core)
    implementation(libs.coroutines)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.espresso)
    androidTestImplementation(libs.test.runner)
    androidTestImplementation(libs.test.ext)
}
