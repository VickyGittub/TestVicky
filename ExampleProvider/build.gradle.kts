dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version number
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
