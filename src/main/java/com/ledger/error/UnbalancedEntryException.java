package com.ledger.error;

import java.util.Map;
import org.springframework.http.HttpStatus;

/** Postings do not sum to zero for at least one currency. */
public class UnbalancedEntryException extends LedgerException {

    private final Map<String, Long> imbalanceByCurrency;

    public UnbalancedEntryException(Map<String, Long> imbalanceByCurrency) {
        super(HttpStatus.UNPROCESSABLE_ENTITY,
                "https://ledger.example/problems/unbalanced-entry",
                "Entry does not balance",
                "Postings must sum to zero for every currency. Imbalance: "
                        + imbalanceByCurrency);
        this.imbalanceByCurrency = imbalanceByCurrency;
    }

    public Map<String, Long> getImbalanceByCurrency() {
        return imbalanceByCurrency;
    }
}
