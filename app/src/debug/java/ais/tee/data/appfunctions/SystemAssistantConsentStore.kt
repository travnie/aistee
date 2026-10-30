package ais.tee.data.appfunctions

/** Process-session consent only. No disk, backup, transfer or restored authorization. */
internal object SystemAssistantConsentStore {
    data class Snapshot(val enabled: Boolean, val generation: Long)

    private var enabled = false
    private var generation = 0L

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(enabled, generation)

    @Synchronized
    fun isEnabled(): Boolean = enabled

    @Synchronized
    fun setEnabled(value: Boolean) {
        enabled = value
        generation++
    }

    /** Immediate denial before any cancellable platform work. */
    @Synchronized
    fun beginUpdate(): Long {
        enabled = false
        return ++generation
    }

    @Synchronized
    fun isCurrent(expected: Long): Boolean = generation == expected

    /** An older enable operation cannot undo a newer opt-out. */
    @Synchronized
    fun completeUpdate(expected: Long, value: Boolean): Boolean {
        if (generation != expected) return false
        enabled = value
        generation++
        return true
    }
}
