# Independent implementation review

Reviewed on 2026-11-17 against `DEMO_IMPLEMENTATION_PLAN.md`. Scope: all production Java classes, SQL/configuration, templates/CSS, and current codec/link/stats/web/acceptance tests. Documentation and DemoSmoke were still being completed and are not reported as defects. No Maven invocation, production/test source edits, or commits were made by this reviewer. The only reviewer-written artifact is this report.

Verification used the existing compiled application/dependency classpath recorded in Surefire, JDK 17 JShell, isolated in-memory H2 databases, and an ephemeral embedded Tomcat port. No existing demo database was accessed. Source was changing concurrently; original line references below identify the reviewed pre-fix code, and fix status is recorded separately.

## Findings

### P1 — Raw URI matching permits unauthenticated access to management handlers

Location: `src/main/java/com/example/shortlink/web/ApiTokenFilter.java`, original lines 35–41 (`getRequestURI()` and `startsWith("/api/")`).

Tomcat/Spring route decoded path segments and remove matrix parameters, while the filter compares the original URI. On actual embedded Tomcat, without an Authorization header:

```text
GET /api/stats?from=2026-11-13&to=2026-11-14       -> 401
GET /%61pi/stats?from=2026-11-13&to=2026-11-14     -> 200, statistics JSON
GET /api;x=1/stats?from=2026-11-13&to=2026-11-14   -> 200, statistics JSON
```

The same mismatch covers creation and demo mutation/reset handlers and skips their body and API-specific rate limits. Root independently reproduced unauthenticated creation returning 201 in `.tools/path-security-red.log`. Fix by enforcing a canonical path before dispatch, or use the exact same normalized path representation as routing. Test real HTTP because servlet mock behavior does not prove container normalization.

Status during review: root added a rejection before dispatch for percent escapes, semicolons, repeated slashes, and dot segments, plus `HttpPathSecurityTest`. Source re-review found no remaining alias bypass in the configured root-context application. Final regression execution is owned by root.

### P2 — JSON number coercion silently changes the product ID

Location: `src/main/java/com/example/shortlink/link/LinkController.java`, `CreateRequest.productId`; `src/main/resources/application.yml`, original Jackson configuration lines 25–27.

With Jackson 2.19.2's original configuration, deserializing the following request yields `CreateRequest[productId=1001, sharerId=123, channel=wechat]`:

```json
{"productId":1001.9,"sharerId":123,"channel":"wechat"}
```

`@Positive Long` validation runs after the fractional value has been truncated, so the service creates a link to a different ID instead of rejecting the malformed identifier. Numeric attribution also bypasses the intended string JSON type. Root independently reproduced the decimal request returning 201 in `.tools/protocol-red.log`.

Status during review: root disabled `ACCEPT_FLOAT_AS_INT` and added explicit scalar coercion failures in `AppConfig`. Bean validation still rejects null/missing fields. Source re-review is satisfactory; final HTTP regressions are owned by root.

### P2 — Generic exception handling converts protocol errors into 500

Location: `src/main/java/com/example/shortlink/web/GlobalExceptionHandler.java`, original lines 37–40.

Actual HTTP requests with a valid token returned these results:

```text
GET /api/links                              -> 500 INTERNAL_ERROR
POST /api/links, Content-Type: text/plain    -> 500 INTERNAL_ERROR
```

The captured exception types were `HttpRequestMethodNotSupportedException` and `HttpMediaTypeNotSupportedException`. These are expected client protocol failures and should retain 405 (with Allow) and 415, respectively. The broad handler overrides Spring's normal resolver and misreports client errors as service failures.

Status during review: root added explicit 405/415 handlers, with the Allow header for 405. Final HTTP regressions are owned by root.

### P2 — A concurrent title-only PATCH can undo a committed off-shelf change

Location: `src/main/java/com/example/shortlink/product/JdbcProductGateway.java`, formatted lines 54–60.

`patch` reads the whole product and always updates both title and status, copying unspecified fields from the old read. Two valid partial updates can overwrite each other under the default read-committed transaction isolation. Deterministic reproduction used a real H2 database and `TransactionTemplate`, pausing both requests after the initial read:

1. Both PATCH calls read product 1001 with status ACTIVE.
2. The status-only PATCH commits OFF_SHELF.
3. The title-only PATCH continues with its earlier snapshot and commits the new title together with stale status ACTIVE.
4. The final persisted product has the new title and ACTIVE status.

The observed result was `Product[id=1001, title=new-title, ..., status=ACTIVE]`. `@Transactional` alone does not serialize the initial read. Lock the row while reading (`SELECT ... FOR UPDATE`) or update only explicitly supplied fields and read the final result. Add a coordinated concurrent regression so a title change cannot re-enable an off-shelf product.

Status: reported to root; fix/regression pending at initial report creation.

## Other reviewed behavior

- Base62 handles canonicality, case sensitivity, overflow, and independent generated-input properties; no issue found.
- Link insertion is autocommit on a separately acquired connection. Known business conflicts re-read the winner; only confirmed key conflicts retry. No collision/transaction defect found.
- Atomic Caffeine loading plus `asMap().compute` serializes creation publication with an in-flight miss, so a late negative result cannot remain after creation returns. Positive/negative TTL and capacity are bounded.
- Current product state is queried on each access and is not part of the immutable link cache. Reset concurrency is expressly excluded by the plan.
- Stats writes own their transaction; duplicate inserts and other failures are classified outside it. Event time uses UTC offsets; query boundaries use Hong Kong midnight. Global UV is independently distinct, not summed from groups.
- Independently checked H2 repeatable-read visibility with two connections: total count was 1 before a concurrent committed insert and remained 1 afterward. No snapshot inconsistency was found in the two-query totals/groups design.
- HEAD and known preview user agents are excluded before identity creation/recording. Stats failures are caught for fail-open redirects; link/product read failures reach the 503 handler. Existing and newly added tests cover these paths.
- SQL values are bound, group columns are whitelisted, redirect paths are server-derived, and Thymeleaf escapes product content. No arbitrary-target redirect, SQL injection, or template XSS found.
- The seed marker preserves modifications and even deletion of all products on restart. File-backed restart/clean-copy acceptance remains root's delivery verification; absence of that evidence during active work is not a code defect.
- Visual CSS/template source is coherent and responsive; this review did not perform rendered browser QA.

## Evidence limits

Existing Surefire reports were inspected but not treated as proof of the concurrently edited final tree. The in-memory reproductions demonstrate the original findings, not final full-suite success. Final formatting, Maven verify, real HTTP demo, and persistence checks must be reported from root's completed runs.

## Scoped final disposition — path, JSON, and protocol fixes

At root's request, re-reviewed only the current `ApiTokenFilter`, `AppConfig`, Jackson configuration, and `GlobalExceptionHandler`, together with existing regression source and `.tools/verify-reviewed.log`. No additional server launch, probing, Maven execution, or broader issue search was performed for this pass.

The first three findings are closed for the configured demo:

- **Path authentication:** canonical-path rejection executes before static/health exemptions, rate limiting, body wrapping, or dispatch. `HttpPathSecurityTest` exercises the real embedded server and proves 400 for `/%61pi/links`, `/api;v=1/links`, `/./api/links`, and `//api/links`, with canonical unauthenticated `/api/links` still returning 401. The guard also explicitly rejects parent/current directory segments and trailing dot segments by inspection.
- **JSON coercion:** `accept-float-as-int: false` prevents fractional IDs from becoming another product ID. The customizer rejects integer/float/boolean input for textual fields and string input for integer fields. The HTTP regression proves 400 for the reported fractional product ID and numeric sharer ID, while existing normal create/reuse tests still pass.
- **405/415:** specific exception handlers now precede generic fallback by Spring's exception-type selection and retain the shared safe error shape. The 405 handler copies supported methods into `Allow`. The HTTP regression proves 405 for authenticated GET `/api/links` and 415 for text/plain creation.

Evidence independently inspected: `.tools/verify-reviewed.log` completed at **2026-11-17 08:45:50 +08:00**, reports **43 tests, 0 failures, 0 errors, 0 skipped**, includes both 10,000-case Base62 properties, packages the executable JAR, passes Spotless, and ends **BUILD SUCCESS**. Surefire confirms `HttpPathSecurityTest` 1/1 and `ShareFlowTest` 6/6 passing.

Limits of this disposition: rejecting every percent-encoded path is intentional for this demo's ASCII routes, rather than a general path normalization implementation. Review and evidence apply to the configured root context and embedded Tomcat. The regression tests directly cover the listed cases, not every protocol exception or scalar shape; for example, the `Allow` header is present by source inspection but is not asserted by the current protocol test. These are evidence boundaries, not additional reported defects. The product PATCH lost-update finding remains separately assigned for correction and is not closed by this 43-test run.

## Final disposition — product partial-update race resolved

Re-reviewed only `JdbcProductGateway.patch`, its coordinated regression in `LinkRepositoryTest`, and the supplied RED/final verification evidence. No new execution, probing, or general issue search was performed.

**The fourth finding is closed.** After validating field names and values, `patch` now builds SQL assignments exclusively from the fixed `title`/`status` whitelist and includes only fields explicitly supplied by the request. Values remain bound parameters. A title-only request therefore cannot write an earlier status, regardless of the initial existence read. The updated row is reread for the response; a concurrent disappearance is still handled as not found.

The regression `titleOnlyPatchCannotRestoreStatusFromAnEarlierRead` uses real H2 transactions and latches to pause the title PATCH after its initial read, commit OFF_SHELF, then resume the title PATCH. It checks both the returned status and persisted title/status. `.tools/patch-race-red.log` records the original failure (`expected OFF_SHELF but was ACTIVE`) at 2026-11-17 08:46:40 +08:00, so this test demonstrates the reported bug rather than merely mirroring the fixed implementation.

Final evidence inspected: `.tools/verify-final.log` ends **BUILD SUCCESS** at **2026-11-17 20:40:32 +08:00**, with **44 tests, 0 failures, 0 errors, 0 skipped**, executable JAR packaging, and Spotless success. `LinkRepositoryTest` reports 7 passing tests, including the race regression. All four actionable findings from this review now have source fixes and regression evidence. This closes the code-review findings; live demo, restart persistence, and delivery documentation remain separate acceptance evidence owned by root.

## 页面工作台补充审查（2026-11-17）

独立审查检查了恢复/历史API、默认及自定义Token、CSP、日期包含结束日转换和UI工作流。未发现阻塞缺陷；发现P3：模态框中的失败仅写到背后的全局状态栏。已在开放弹窗内增加role=alert错误区域，错误凭证重试与正确凭证连接已在浏览器验证。最终48项测试通过。
