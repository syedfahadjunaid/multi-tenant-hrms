package com.hrms.core.common;

/** The request conflicts with the resource's current state, e.g. approving a cancelled request. HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
