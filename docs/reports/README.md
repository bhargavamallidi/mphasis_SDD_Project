# Captured run reports

Reports were generated on 2026-10-30 from the project root using Java 17 and Maven:

- Test suite: `mvn clean test`
- Sample application: `mvn -q exec:java`

The test run passed all 12 tests with zero failures, errors, or skips. `maven-test-run.txt` contains the full Maven console log, and `surefire/` contains the per-test-class Surefire text and XML reports. `sample-run.txt` contains the application's full output, including the three expected final balances and four ledger entries.

These are captured artifacts from this verification run. To refresh after changing source or tests, run the refresh commands in the project's `README.md`.
