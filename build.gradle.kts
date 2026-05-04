plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.binary.compatibility.validator) apply false
}

// `group` and `version` are declared in `gradle.properties` so the release
// workflow can read them with a shell one-liner. Gradle automatically applies
// them to every project in the build.
allprojects {
  repositories {
    mavenCentral()
  }
}
