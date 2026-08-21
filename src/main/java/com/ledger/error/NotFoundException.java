package com.ledger.error;

import org.springframework.http.HttpStatus;

public class NotFoundException extends LedgerException {

    public NotFoundException(String what, Object id) {
        super(HttpStatus.NOT_FOUND,
                "https://ledger.example/problems/not-found",
                what + " not found",
                "No " + what.toLowerCase() + " with id " + id);
    }
}
