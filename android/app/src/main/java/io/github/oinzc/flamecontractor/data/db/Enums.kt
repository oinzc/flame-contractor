package io.github.oinzc.flamecontractor.data.db

enum class AccountStatus(val wireValue: String) {
    PENDING_LOGIN("pending_login"),
    HEALTHY("healthy"),
    RUNNING("running"),
    PAUSED("paused"),
    AUTH_EXPIRED("auth_expired"),
    VERIFICATION_REQUIRED("verification_required"),
    DOM_BROKEN("dom_broken"),
    COOLDOWN("cooldown"),
    ;

    companion object {
        fun fromWireValue(value: String): AccountStatus =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown AccountStatus: $value")
    }
}

enum class TemplateType(val wireValue: String) {
    TEXT("text"),
    POOL("pool"),
    STICKER("sticker"),
    IMAGE("image"),
    ;

    companion object {
        fun fromWireValue(value: String): TemplateType =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown TemplateType: $value")
    }
}

enum class JobStatus(val wireValue: String) {
    SCHEDULED("scheduled"),
    LEASED("leased"),
    OPENING_CHAT("opening_chat"),
    SENDING("sending"),
    CONFIRMING("confirming"),
    SUCCEEDED("succeeded"),
    FAILED_RETRYABLE("failed_retryable"),
    FAILED_TERMINAL("failed_terminal"),
    UNKNOWN_REQUIRES_REVIEW("unknown_requires_review"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWireValue(value: String): JobStatus =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown JobStatus: $value")
    }
}

enum class AttemptStatus(val wireValue: String) {
    RUNNING("running"),
    SUCCEEDED("succeeded"),
    FAILED("failed"),
    ;

    companion object {
        fun fromWireValue(value: String): AttemptStatus =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown AttemptStatus: $value")
    }
}

enum class FailureClass(val wireValue: String) {
    NETWORK("network"),
    AUTH("auth"),
    VERIFICATION("verification"),
    TARGET_UNCERTAIN("target_uncertain"),
    DOM_CHANGED("dom_changed"),
    UNKNOWN_OUTCOME("unknown_outcome"),
    OTHER("other"),
    ;

    companion object {
        fun fromWireValue(value: String): FailureClass =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown FailureClass: $value")
    }
}

enum class ScanRunStatus(val wireValue: String) {
    RUNNING("running"),
    COMPLETED("completed"),
    FAILED("failed"),
    INTERRUPTED("interrupted"),
    CANCELLED("cancelled"),
    ;

    companion object {
        fun fromWireValue(value: String): ScanRunStatus =
            entries.firstOrNull {
                it.wireValue.equals(value, ignoreCase = true) ||
                    it.name.equals(value, ignoreCase = true)
            } ?: throw IllegalArgumentException("Unknown ScanRunStatus: $value")
    }
}
