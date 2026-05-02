// :sdk-ktor — RESERVED FOR PHASE B
//
// This module will ship a `KtorHttpTransport` adapter that lets Ktor-using
// applications share their `HttpClient` with the Tesser SDK. Intentionally
// empty in v0.0.1 — see docs/superpowers/specs/2026-04-30-kotlin-sdk-design.md §7.7.
//
// When Phase B lands, this module will look like:
//   plugins {
//     alias(libs.plugins.kotlin.jvm)
//     alias(libs.plugins.ktlint)
//     alias(libs.plugins.dokka)
//     alias(libs.plugins.maven.publish)
//   }
//   kotlin { jvmToolchain(17); explicitApi() }
//   dependencies {
//     api(project(":sdk"))
//     implementation("io.ktor:ktor-client-core:3.x")
//   }
//   mavenPublishing { ... coordinates(group.toString(), "sdk-ktor", version.toString()) ... }

plugins {
  alias(libs.plugins.kotlin.jvm)
}

kotlin {
  jvmToolchain(17)
}
