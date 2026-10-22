package dev.floresm.transactions.domain.exception;

import java.util.UUID;

/** A requested resource does not exist. Mapped to HTTP 404 by api/error/GlobalExceptionHandler.java. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, UUID id) {
        super("%s %s not found".formatted(resource, id));
    }
}
