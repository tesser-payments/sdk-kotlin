package xyz.tesser.sdk

/**
 * Options that tune a [LocalSigner.signStep] call.
 *
 * Empty in the initial step-signing release. Reserved as a public type so
 * future fields (cancellation signals, alternate signature schemes, gas
 * overrides) can be added with default values, keeping callers using
 * `SignStepOptions()` backward-compatible.
 */
public class SignStepOptions
