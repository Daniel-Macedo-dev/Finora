package com.finora.api.identity;

public enum UserStatus {
    /** Normal account able to authenticate. */
    ACTIVE,
    /** Blocked account; cannot authenticate. */
    DISABLED,
    /**
     * Placeholder owner of pre-multiuser (v1) data. Cannot authenticate until
     * claimed through the environment-gated legacy claim flow.
     */
    PENDING_LEGACY_CLAIM
}
