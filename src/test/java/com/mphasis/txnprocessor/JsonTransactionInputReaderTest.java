package com.mphasis.txnprocessor;

import com.mphasis.txnprocessor.input.JsonTransactionInputReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JsonTransactionInputReaderTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void isolatesMalformedRecordsInArray() throws IOException {
        Path input = temporaryDirectory.resolve("transactions.json");
        Files.writeString(input, """
                [
                  {"transactionId":"T1","requestId":"R1","sequenceNumber":1,
                   "accountId":"A","transactionType":"CREDIT","amount":2,"currency":"USD"},
                  {"transactionId":"T2","requestId":"R2","sequenceNumber":"bad"}
                ]
                """);

        var records = new JsonTransactionInputReader().read(input);

        assertEquals(2, records.size());
        assertNotNull(records.get(0).request());
        assertEquals("INVALID_JSON_RECORD", records.get(1).parseErrorCode());
    }
}
