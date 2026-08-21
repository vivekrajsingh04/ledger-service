package com.ledger.error;

import org.springframework.http.HttpStatus;

/** Base for errors that map onto a specific RFC 7807 problem type. */
public abstract class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String title;

    protected LedgerException(HttpStatus status, String type, String title, String detail) {
        super(detail);
        this.status = status;
        this.type = type;
        this.title = title;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }
}
