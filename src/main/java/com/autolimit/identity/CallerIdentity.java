package com.autolimit.identity;

import java.util.List;

public record CallerIdentity(String userId, List<String> roles) {

    public CallerIdentity{
        if (userId == null || userId.isBlank()) {

            throw new IllegalArgumentException("userId cannot be blank");

        }

        roles = List.copyOf(roles);
    }

}
