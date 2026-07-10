package com.finora.api.common.error;

public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " não encontrado(a): " + id);
    }

    public NotFoundException(String message) {
        super(message);
    }
}
