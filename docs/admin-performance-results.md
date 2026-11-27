# Admin page performance validation

Completed November 27, 2026. The changes preserve displayed results and existing APIs.

## Root causes fixed

1. Staff Media fetched its page, then lazily loaded owner records while mapping business IDs. Fetching the same visible owners in the page query avoids those extra database reads.
2. Dashboard fetched two adjacent order windows separately. One query now fetches their exact union; the existing store-time boundary splits the result without changing totals or charts.

No new cache, index, dependency or authorization shortcut was added.

## Measured result

Five uncached, read-only compiled-service samples per case, against the same PostgreSQL database:

| Read path | Before median | After median | SQL count |
|---|---:|---:|---|
| Media, ID-sorted 20-row page and mapping | 613 ms | 317 ms | 4 → 2 |
| Dashboard, 7 days | 748 ms | 595 ms | 5 → 4 |
| Dashboard, 30 days | 753 ms | 607 ms | 5 → 4 |
| Dashboard, 90 days | 753 ms | 605 ms | 5 → 4 |

An alternating old/new query-strategy experiment also improved: Media median 464.5 → 324 ms; Dashboard 784 → 616.5 ms. No database writes were needed.

Saved JSON matched exactly for all Dashboard fields except the naturally changing `generatedAt`, and for ordered Media rows and page totals.

These are database/service timings, not site-wide load time or production p95. Chromium verified both pages render after HTTP 200. Warm post-change navigation took 1,007 ms on Media and 1,366/1,413 ms on Dashboard. Cross-region database latency, required session restoration and development compilation remain; they were not bypassed.

## Tests and review

- Forty focused tests passed.
- Full Maven suite: 1,767 tests, zero failures, zero errors, 187 skipped; BUILD SUCCESS.
- Regression guards cover cold-context SQL counts, full and short Media pages, hidden/deleted owners, exact midnight partitioning, one Dashboard order read, unchanged KPI math and weekly charts.
- Docker-based tests were skipped. Standalone probes ran the actual Hibernate/PostgreSQL read paths.
- Surefire still warns that one test JVM is slow to exit.
- Hy4 Flash Free reviewed findings/spec/plan before implementation and approved the final diff. Its cold-context and boundary recommendations were accepted before coding.
- GPT-6.1 Sol, high effort, independently approved the diff and reran all 40 focused tests. It noted a non-blocking test gap: the SQL-count assertion's ascending-ID page covers only product-owned media; a descending-ID page would cover both owner types. The broader visibility tests cover both types, but this narrower query-budget guard remains deferred.

## Artifacts

- [Findings and measurements](superpowers/specs/2026-11-26-admin-read-performance-findings.md)
- [Design spec](superpowers/specs/2026-11-26-admin-read-performance-design.md)
- [Completed implementation plan and review decisions](superpowers/plans/2026-11-26-admin-read-performance.md)

The repository ignores `docs/superpowers/`; those working artifacts exist locally and are not included with this report. This results report is outside that ignored directory.

Validation started the backend with Flyway explicitly disabled to avoid running new, unrelated user migrations. Neither the tracked app configuration nor `.env` was changed. Existing Next.js security advisories remain unrelated; upgrade before deploying.
