plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.google.services) apply false
}

/**
 * One version for both apps. Releases pass it from the git tag (v1.2.3 -> -PappVersionName=1.2.3);
 * local builds use gradle.properties. The version code is derived from it (1.2.3 -> 10203), which
 * is also how the apps compare themselves with the latest GitHub release.
 */
val appVersionName: String = providers.gradleProperty("appVersionName").get()
val appVersionCode: Int = appVersionName.split(".").map { it.toInt() }
    .also { require(it.size == 3) { "appVersionName must look like 1.2.3, got $appVersionName" } }
    .let { (major, minor, patch) -> major * 10000 + minor * 100 + patch }

extra["appVersionName"] = appVersionName
extra["appVersionCode"] = appVersionCode
