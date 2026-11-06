package com.project.dacn.integration.realtime;

/** Server-selected identity; authenticated accounts must already have passed the active-account check. */
public record RealtimePrincipal(Long userId, boolean authenticated) {

    public RealtimePrincipal {
        if (authenticated ? userId == null || userId <= 0 : userId != null) {
            throw new IllegalArgumentException("invalid realtime principal");
        }
    }

    public static RealtimePrincipal guest() {
        return new RealtimePrincipal(null, false);
    }

    public static RealtimePrincipal authenticated(long userId) {
        return new RealtimePrincipal(userId, true);
    }
}
