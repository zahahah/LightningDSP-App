object AndroidConfig {
    var minSdk = 29

    const val compileSdk = 35
    const val targetSdk = 34

    const val versionName = "1.7-beta-1"
    // Root flavor adds four; keep the published package upgradeable beyond beta-2/code 66.
    const val versionCode = 63

    const val kotlinVersion = "2.1.0"
    const val kspVersion = "2.1.0-1.0.29"
    const val shizukuVersion = "12.2.0"
    const val rikkaRefineVersion = "4.3.0"
}
