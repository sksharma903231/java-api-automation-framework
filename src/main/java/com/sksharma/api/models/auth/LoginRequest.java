package com.sksharma.api.models.auth;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Immutable authentication request DTO.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginRequest(String username, String password, Integer expiresInMins) {

    public LoginRequest(String username, String password) {
        this(username, password, null);
    }
}
