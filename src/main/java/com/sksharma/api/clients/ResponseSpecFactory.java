package com.sksharma.api.clients;

import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.ResponseSpecification;
import org.hamcrest.Matchers;

/**
 * Factory providing reusable ResponseSpecifications only for genuinely shared expectations.
 * Endpoints remain free to assert specific status codes, headers, and bodies directly.
 */
public final class ResponseSpecFactory {

    private ResponseSpecFactory() {
        // Prevent instantiation
    }

    /**
     * Shared expectation that the response returns JSON content-type.
     */
    public static ResponseSpecification getJsonResponseSpec() {
        return new ResponseSpecBuilder()
                .expectContentType(ContentType.JSON)
                .build();
    }

    /**
     * Shared expectation that the response status is in the 2xx success range with JSON body.
     */
    public static ResponseSpecification getSuccessResponseSpec() {
        return new ResponseSpecBuilder()
                .expectStatusCode(Matchers.both(Matchers.greaterThanOrEqualTo(200)).and(Matchers.lessThan(300)))
                .expectContentType(ContentType.JSON)
                .build();
    }
}
