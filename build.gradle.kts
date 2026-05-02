plugins {
  alias(libs.plugins.kotlin.jvm) apply false
  alias(libs.plugins.kotlinx.serialization) apply false
  alias(libs.plugins.ktlint) apply false
  alias(libs.plugins.dokka) apply false
  alias(libs.plugins.maven.publish) apply false
  alias(libs.plugins.binary.compatibility.validator) apply false
}

allprojects {
  group = "xyz.tesser"
  version = "0.0.1-SNAPSHOT"

  repositories {
    mavenCentral()
  }
}
