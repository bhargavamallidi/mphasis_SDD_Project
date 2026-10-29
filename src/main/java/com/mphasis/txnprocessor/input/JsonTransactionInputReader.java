package com.mphasis.txnprocessor.input;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mphasis.txnprocessor.model.InputRecord;
import com.mphasis.txnprocessor.model.TransactionRequest;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JsonTransactionInputReader implements TransactionInputReader {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public List<InputRecord> read(Path path) throws IOException {
        JsonNode root = objectMapper.readTree(path.toFile());
        if (root == null || !root.isArray()) {
            throw new IOException("Input JSON must be an array of transaction objects.");
        }

        List<InputRecord> records = new ArrayList<>();
        for (JsonNode node : root) {
            records.add(parseRecord(node));
        }
        return List.copyOf(records);
    }

    private InputRecord parseRecord(JsonNode node) {
        if (!node.isObject()) {
            return InputRecord.invalid("INVALID_JSON_RECORD");
        }
        try {
            String transactionId = text(node, "transactionId");
            String requestId = text(node, "requestId");
            Long sequence = integer(node, "sequenceNumber");
            String accountId = text(node, "accountId");
            String transactionType = text(node, "transactionType");
            BigDecimal amount = decimal(node, "amount");
            String currency = text(node, "currency");
            return InputRecord.valid(new TransactionRequest(
                    transactionId, requestId, sequence, accountId, transactionType, amount, currency));
        } catch (IllegalArgumentException exception) {
            return InputRecord.invalid("INVALID_JSON_RECORD");
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Expected text field");
        }
        return value.textValue();
    }

    private Long integer(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("Expected integer field");
        }
        return value.longValue();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isNumber()) {
            throw new IllegalArgumentException("Expected numeric field");
        }
        return value.decimalValue();
    }
}
