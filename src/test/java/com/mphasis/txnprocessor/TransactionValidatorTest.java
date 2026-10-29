package com.mphasis.txnprocessor;

import com.mphasis.txnprocessor.model.TransactionRequest;
import com.mphasis.txnprocessor.validation.TransactionValidator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionValidatorTest {
    private final TransactionValidator validator = new TransactionValidator();

    @Test
    void acceptsValidCreditAndNormalizesCurrency() {
        var result = validator.validate(new TransactionRequest(
                "T1", "R1", 1L, "A", "credit", new BigDecimal("10.00"), "usd"));

        assertTrue(result.isEmpty());
    }

    @Test
    void rejectsMissingFieldsAndNonPositiveAmount() {
        assertEquals("MISSING_REQUIRED_FIELD", validator.validate(new TransactionRequest(
                "T1", null, 1L, "A", "CREDIT", BigDecimal.ONE, "USD")).orElseThrow());
        assertEquals("INVALID_AMOUNT", validator.validate(new TransactionRequest(
                "T1", "R1", 1L, "A", "CREDIT", BigDecimal.ZERO, "USD")).orElseThrow());
    }

    @Test
    void rejectsInvalidCurrencySequenceAndUnsupportedType() {
        assertEquals("INVALID_CURRENCY", validator.validate(new TransactionRequest(
                "T1", "R1", 1L, "A", "CREDIT", BigDecimal.ONE, "US1")).orElseThrow());
        assertEquals("INVALID_SEQUENCE", validator.validate(new TransactionRequest(
                "T1", "R1", 0L, "A", "CREDIT", BigDecimal.ONE, "USD")).orElseThrow());
        assertEquals("UNSUPPORTED_TRANSACTION_TYPE", validator.validate(new TransactionRequest(
                "T1", "R1", 1L, "A", "REVERSAL", BigDecimal.ONE, "USD")).orElseThrow());
    }
}
