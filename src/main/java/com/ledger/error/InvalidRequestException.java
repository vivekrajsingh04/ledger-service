package com.ledger.error;

import org.springframework.http.HttpStatus;

public class InvalidRequestException extends LedgerException {

    public InvalidRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST,
                "https://ledger.example/problems/invalid-request",
                "Invalid request",
                detail);
    }
}
