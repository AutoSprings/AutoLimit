package com.autolimit.identity;

import java.util.Optional;

@FunctionalInterface
public interface CallerIdentityResolver {

    /**
     * Resolves the current request's authenticated user and roles.
     * Return an empty result only for anonymous callers. Failures must propagate;
     * a broken identity provider must never downgrade a user to an IP quota.
     * The result itself must not be null.
     */
    Optional<CallerIdentity> resolve();

}
