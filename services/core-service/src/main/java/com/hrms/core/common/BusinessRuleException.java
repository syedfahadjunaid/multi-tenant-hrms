package com.hrms.core.common;

/**
 * The request is well-formed but breaks a business rule (dates overlap, not enough leave left,
 * ...). Maps to HTTP 422 with the offending field, so a form can show the message in place.
 */
public class BusinessRuleException extends RuntimeException {

    private final String field;

    /** @param field the request field to blame, or {@code null} if it isn't about one field */
    public BusinessRuleException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
