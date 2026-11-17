# Implementation ledger — DEMO_IMPLEMENTATION_PLAN.md

2026-11-17. User authorized implementation, Java 17, no external deployment tooling.

## Preflight decisions

| Tasks | Shared contract / check | Ruling |
| --- | --- | --- |
| 1 → 2, 4 | H2 schema and JDBC transactions | Use `link_key` rather than reserved SQL identifier `key`; API still returns `key`. |
| 2 → 3 | ShortLink mapping and product state | Cache only immutable mapping; product checked per request. |
| 3 → 4 | GET versus HEAD, visitors | Explicit HEAD route; identity allocated only for eligible GET. |
| 4 → 5 | Demo repeatability | Fixed buckets tested with controlled time; HTTP demo uses separate visitors. |
| 1, 6 | Persistent initialization | An app_meta marker prevents deleted seed products from reappearing after restart, including when all products are deleted. |
| 1 | Startup prerequisites | Java 17.0.12 available; Maven downloaded after sandbox TLS failure and approved escalation. |
| 2 | Collision contracts | Atomic database constraints and bounded retry; failures never cached as missing. |
| 3 | Local imagery | Generate bundled PNG illustration using Java2D; no external image fetch. |
| 4 | Failure isolation | Separate JDBC write transaction; duplicate outcome distinct from infrastructure failure. |
| 5 | Scope | Java HTTP smoke program and authenticated demo endpoints only. |
| 6 | Evidence | Save actual test/demo logs and available conversation records; explicitly label gaps rather than fabricate. |

Ruling: current repository has no commits and staged user IDE files; implement in place on new `feat/short-link-demo` branch without committing user's files. A worktree requires an existing base commit.

## Tasks

- [x] 1. Bootstrapping and product database
- [x] 2. Codec and links
- [x] 3. Pages and degradation
- [x] 4. Statistics and identity
- [x] 5. Abuse protection and reproducible demo
- [x] 6. Verification, review and documentation

## Completion evidence

- Task 1: Java17 + official Wrapper checksum; clean-source build and file-backed initialization passed.
- Task 2: 20 core tests plus two 10,000-sample properties; null-state validation corrected.
- Task 3: rendered desktop/mobile pages and raw OG validation; product status changes observed through cached links.
- Task 4: real H2 concurrency/dedup/date/filter/group tests plus fault injection for isolated writes.
- Task 5: live demo all10 steps passed, including404/410/429; wrong verify-only server rejected with nonzero exit.
- Task 6: final44 tests, Spotless and packaging passed; stopped server and restarted executable JAR, persistence check passed; clean source copy demo passed.

Ruling: independent review identified four issues; three fixed by root and re-reviewed, product partial-update race fixed by root after the original worker session became unavailable. Deterministic regression first failed ACTIVE vs OFF_SHELF, then passed. Reviewer subsequently closed all four findings. No claim is made that the temporary worker interruption was a product defect.

Ruling: final artifacts remain in current feature branch without staging or committing the user's existing IDE configuration. Completion results and raw evidence are in docs/validation.md and docs/evidence/.

## Workbench delivery (2026-11-17)

- Added demo-only workbench, product selection/edit/status/delete/restore, persisted latest100 link history, copy/open actions and stats filtering/grouping.
- Added4 regression/acceptance tests, extended non-demo route checks. Final48 tests + formatting + JAR build passed.
- Browser verified main and failure flows in isolated in-memory database; custom credential errors now visible inside dialogs. Mobile viewport override did not take effect, so mobile runtime verification remains a documented limitation.
- README and evidence updated; original IDE staging preserved, no commits made.
