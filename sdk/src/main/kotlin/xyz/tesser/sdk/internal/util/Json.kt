package xyz.tesser.sdk.internal.util

import kotlinx.serialization.json.Json

/**
 * The single shared kotlinx.serialization Json instance for every
 * (de)serialization site in the SDK. Lowercase `json` to avoid shadowing the
 * type [kotlinx.serialization.json.Json].
 *
 * `ignoreUnknownKeys = true` is forward-compat insurance: when Tesser's API
 * adds a new field, deserialization keeps working instead of throwing.
 *
 * SDK code never constructs ad-hoc `Json {}` blocks. Always use this instance.
 */
internal val json: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }
