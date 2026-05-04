import com.vanniktech.maven.publish.SonatypeHost

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.dokka)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.binary.compatibility.validator)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    implementation(libs.bouncycastle)

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.kotest.assertions)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    // Sonatype migrated everyone to the Central Portal in 2024; OSSRH is sunset.
    // Vanniktech 0.30+ targets the Central Portal directly.
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL)

    // GPG-sign all artifacts for Maven Central. Keys are supplied via env vars in CI:
    //   ORG_GRADLE_PROJECT_signingInMemoryKey, ORG_GRADLE_PROJECT_signingInMemoryKeyPassword.
    signAllPublications()

    coordinates(group.toString(), "sdk", version.toString())

    pom {
        name.set("Tesser Kotlin SDK")
        description.set("Kotlin SDK for the Tesser API. Produces locally-signed wallet-creation payloads.")
        inceptionYear.set("2026")
        url.set("https://github.com/tesser-payments/sdk-kotlin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("tesser-payments")
                name.set("Tesser")
                url.set("https://tesser.xyz")
            }
        }
        scm {
            url.set("https://github.com/tesser-payments/sdk-kotlin")
            connection.set("scm:git:git://github.com/tesser-payments/sdk-kotlin.git")
            developerConnection.set("scm:git:ssh://github.com/tesser-payments/sdk-kotlin.git")
        }
    }
}
