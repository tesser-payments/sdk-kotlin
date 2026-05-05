package xyz.tesser.sdk.internal.util

import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * SDK-internal SLF4J logger factory. Single source for logger acquisition;
 * tests can swap behavior via SLF4J test backend without touching SDK code.
 */
internal fun logger(name: String = "xyz.tesser.sdk"): Logger = LoggerFactory.getLogger(name)
