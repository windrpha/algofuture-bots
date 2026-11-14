package com.algofuture.shared.infrastructure.rest;

import jakarta.ws.rs.core.Response;

public final class CorsHelper {

    private static final String ORIGIN = "*";
    private static final String HEADERS = "Content-Type,X-Amz-Date,Authorization,X-Api-Key,X-Amz-Security-Token";
    private static final String METHODS = "OPTIONS, GET, POST";

    private CorsHelper() {
    }

    public static Response.ResponseBuilder apply(Response.ResponseBuilder builder) {
        return builder
                .header("Access-Control-Allow-Origin", ORIGIN)
                .header("Access-Control-Allow-Headers", HEADERS)
                .header("Access-Control-Allow-Methods", METHODS);
    }
}
