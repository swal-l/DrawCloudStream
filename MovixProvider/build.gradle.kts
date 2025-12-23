dependencies {
    implementation("com.google.android.material:material:1.12.0")
    // implementation("androidx.recyclerview:recyclerview:1.3.2")
}

// Use an integer for version numbers
version = 3

cloudstream {
    description = "Extension for Movix.club"
    authors = listOf("Antigravity")
    
    // Status: 1 = Ok, 3 = Beta
    status = 3 

    tvTypes = listOf("TvSeries", "Movie", "Anime")
    
    requiresResources = false
    language = "fr"

    iconUrl = "https://movix.club/assets/movix-CzqwVOTS.png"
}