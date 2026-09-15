# IR-01 implementation and verification evidence

Baseline: `a3e7a6c55040bbebc8760750af4b5cfcfd0406aa`.
Branch: `review-fix-01-inventory-edit`.

## Regression sequence

1. Added `InventoryEditContainerTest` against unchanged business code. Real MyBatis mapper and isolated Testcontainers MySQL 8.0.46, all Flyway migrations applied. Both assertions failed: committed purchase expected 99 but stale metadata edit restored 100; committed restoration expected 100 but stale edit restored 99. Saved original Surefire output in `ir01-before.txt`; test/evidence commit `821ac23`.
2. Implemented metadata-only mapper update and separate versioned, audited signed-delta adjustment; shared implementation commit `5150684`.
3. First fixed run: InventoryEditContainerTest **2 passed** and existing AdminApiContractTest **7 passed**. V1.9 expression defaults verified on real MySQL, including post-migration inserts through unchanged mapper insert.

Commands from Windows PowerShell (the dotted Maven property must be quoted):

```powershell
docker run --rm --name hotshop-ir01-maven -v /var/run/docker.sock:/var/run/docker.sock -v D:/Codex/Projects/hotShop-review-01:/workspace -v hotshop-task04-m2:/root/.m2 -w /workspace -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 sh ./mvnw -pl task -am test '-Dtest=InventoryEditContainerTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' -q
# First fixed run also compiled admin and executed its existing API contract tests:
# replace -pl task with -pl task,admin and -Dtest with InventoryEditContainerTest,AdminApiContractTest
```

Initial command without PowerShell quotes split `-Dsurefire.failIfNoSpecifiedTests=false` and exited before compilation; this was a command quoting error, not a business regression result. Maven Wrapper uses 3.9.16, container Java 21.0.11. No Maven clean executed. Testcontainers creates a fresh MySQL database and destroys the container; no user database or volume was used. The existing named Maven dependency cache is used only as a dependency cache, with no install goal.

## Design and compatibility

- PUT product metadata now accepts AdminProductEditRequest without stock. Unknown stock sent by legacy clients is ignored by Jackson and never enters the Product passed to update; the mapper itself also never writes stock.
- Creation retains initial stock. POST `/{productId}/stock-adjustments` accepts a signed `delta`, a string `expectedVersion`, and `reason`; stale versions produce 409 STOCK_ADJUSTMENT_CONFLICT. An adjustment is bounded to valid integer inventory and increments the version. Audit includes before/after stock and version, delta, administrator and reason, atomically with the inventory write.
- The existing catalog version is an INT, exposed as a decimal string consistently. Product reads, list queries and response mapping now return it. All ordinary purchase/restoration, flash-sale purchase and timeout restoration paths increment it.
- V1.9 adds independent BIGINT expected balances to catalog and activity rows. Existing rows are baselined at migration; insert defaults copy each new row's initial actual stock. All legitimate later deltas adjust both balances in the same SQL statement and transaction. A direct actual-stock update does not update its expected balance. These are current independent balances, not event-sourced historical reconstruction.
- Upgrade requires pausing old business writers while migrating and replacing all write processes before resuming. Old SQL writers cannot be rolling-mixed with new writers because they do not maintain expected balances. Migration deliberately cannot establish whether pre-existing stock was already wrong; it establishes the deployment boundary. No foreign keys or changes to released migrations.
- Frontend, OpenAPI/client generation, real service joint transaction/audit tests, and final combined verification are coordinated by the integration branch; this branch's mapper tests are not claims of those full transaction paths.

Additional coverage prepared after the first fixed run:

- Real MySQL compare-and-set contention uses two executor threads, a readiness latch and start latch (no sleep): same version permits one adjustment, rejected negative stock changes nothing, purchase and restoration advance the version.
- V1.8 -> V1.9 upgrade seeds existing product/activity rows first, then verifies baseline initialization and confirms actual-stock tampering does not change the expected balance.
- Repeated development seeding updates expected balances by the same delta and increments versions; it does not reset the expected balance to hide an existing difference. The same adjustment was applied to load seeding and the existing observability fixture's activity reset.
- Standalone HTTP tests verify PUT without stock, safe handling of legacy PUT with stale stock, string version serialization and an understandable 409 conflict. These controller tests use a mocked audit service; actual audited transactions belong to the separate real-service joint test.

All task-modified text was scanned with strict UTF-8 decoding. Java/XML/SQL/Markdown/evidence text uses LF; PowerShell retains repository-required CRLF.

## Final feature-branch verification

Executed the final prepared tests with all seed compatibility changes present:

```powershell
docker run --rm --name hotshop-ir01-maven -v /var/run/docker.sock:/var/run/docker.sock -v D:/Codex/Projects/hotShop-review-01:/workspace -v hotshop-task04-m2:/root/.m2 -w /workspace -e TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal maven:3.9-eclipse-temurin-21 sh ./mvnw -pl task,admin -am test '-Dtest=InventoryEditContainerTest,AdminInventoryApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' -q
```

Exit 0. InventoryEditContainerTest: **5 passed, 0 skipped**, 32.88 seconds. AdminInventoryApiTest: **3 passed, 0 skipped**, 24.60 seconds. Saved Surefire summaries alongside this report. Previously executed existing AdminApiContractTest: **7 passed**, 69.43 seconds. The final standalone run did not rerun that unchanged existing Spring Boot contract class.

`git diff --check` succeeded. After test exit, `docker ps --format '{{.Names}} {{.Status}}'` returned no containers; temporary MySQL and Ryuk were removed automatically. No volume cleanup or user-data operation occurred. The shared Maven dependency cache remains intact. Load-seed full performance orchestration and the complete observability script were not executed here; their relevant SQL delta pattern matches the tested development seed. Integration branch must still perform combined service, frontend and reconciliation validation; these feature results are not the final integrated acceptance.

## Additional signed-delta input regression

Integration review identified that Jackson's default integer coercion would accept fractional or quoted deltas. Added parameterized HTTP cases for `1.5`, `"5"`, and `true`, plus a valid negative delta. On implementation commit `df65ef7`, the first two cases failed (expected HTTP 400, actual 200); boolean was already rejected. Red-test commit `3eb4a22`, **7 tests, 2 failures**, original output `ir01-delta-before.txt`. This is an additional defect found before integration acceptance, not an alteration of the original IR-01 report.

The fix attaches `StrictSignedIntegerDeserializer` only to AdminStockAdjustmentRequest.delta. It requires an integer JSON token, checks signed-int range through Jackson's getIntValue, preserves negative integer values and leaves zero rejection to the existing stock-adjustment business validation. Global Jackson behavior and OpenAPI's integer schema are unchanged.

Both red and green command:

```powershell
docker run --rm --name hotshop-ir01-http -v D:/Codex/Projects/hotShop-review-01:/workspace -v hotshop-task04-m2:/root/.m2 -w /workspace maven:3.9-eclipse-temurin-21 sh ./mvnw -pl admin -am test '-Dtest=AdminInventoryApiTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-DfailIfNoTests=false' -q
```

This DTO-only regression run starts no MySQL or other service container and does not modify OpenAPI definitions or generated clients.

Signed-delta green result: exit 0, **7 passed, 0 failures, 0 errors, 0 skipped**; see `ir01-delta-after.txt`. All signed-delta changed files strictly decode as UTF-8, and `git diff --check` passed.
