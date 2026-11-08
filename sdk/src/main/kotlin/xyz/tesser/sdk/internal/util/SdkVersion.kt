package xyz.tesser.sdk.internal.util

import java.util.Properties

private const val SDK_VERSION_RESOURCE = "/xyz/tesser/sdk/sdk-version.properties"

internal val SDK_VERSION: String = loadSdkVersion()

private fun loadSdkVersion(): String {
    val properties = Properties()
    val stream =
        SdkVersionResource::class.java.getResourceAsStream(SDK_VERSION_RESOURCE)
            ?: error("Missing SDK version resource $SDK_VERSION_RESOURCE")
    stream.use { properties.load(it) }
    return properties.getProperty("version")?.takeIf { it.isNotBlank() }
        ?: error("SDK version resource $SDK_VERSION_RESOURCE has no version")
}

private object SdkVersionResource
