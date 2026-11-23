package com.hrms.core.common;

/** Thrown when a resource doesn't exist, or exists in another tenant (which looks the same to the caller). */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String resource, Object id) {
        super(resource + " " + id + " not found");
    }
}
