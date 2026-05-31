dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // Add cloudstream3 dependency
    implementation("com.lagradost:cloudstream3:pre-release")
}

// Use an integer for version numbers
version = 1

cloudstream {
    description = "Watch anime from animetoki.com"
    authors = listOf("VickyGittub")
    status = 1
    tvTypes = listOf("Anime")
    language = "en"
}

android {
    buildFeatures {
        buildConfig = true
        viewBinding = true
    }
}
