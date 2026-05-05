plugins {
    // Auto-provision JDKs declared via Gradle toolchains.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "tesser-sdk-kotlin"

include(":sdk")
include(":examples:create-wallet")
