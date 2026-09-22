package ais.tee.data.model

/** SDK-independent policy result. Only ALLOW permits execution of the evaluated action. */
enum class CapabilityDecision {
    ALLOW,
    DENY,
    /** A missing permission can be requested for this otherwise supported action. */
    ASK,
    /** Hand off to an explicit user action; consent alone does not enable model execution. */
    REQUIRES_USER_INTERACTION,
}
