# Inventory reservation cutover

## Scope and safety boundary

This runbook defines an operator-approved, one-way policy activation. It does **not** authorize production inspection, data repair, deployment, activation, provider queries, refunds, or shipping. Obtain separate approval for actual data, each cohort's disposition, bounded locked changes, and the deployment window.

Task 2 supplies configuration and a read-only backend inventory only. Do not activate until checkout, callback, URL, staff/admin quarantine, and worker guards are implemented and verified. Tasks 4/6/8/9 own those runtime guards; Task 6 must prove effective-deadline/late-success/pause races on real PostgreSQL, and Tasks 8/9 must prove paused URL issuance and worker admission. Property tests are not that proof.

## Deployment configuration

| Property | Environment alias | Default | Meaning |
|---|---|---|---|
| `app.inventory.reservation-policy-enabled` | `INVENTORY_RESERVATION_POLICY_ENABLED` | `false` | Sole gate for effective deadline interpretation. |
| `app.inventory.online-work-paused` | `INVENTORY_ONLINE_WORK_PAUSED` | `false` | Reject new online checkout and payment-URL issuance only. |
| `app.inventory.expiry-sweeping-paused` | `INVENTORY_EXPIRY_SWEEPING_PAUSED` | `false` | Stop new scheduled expiry claims only. |

`ReservationPolicy.isActive()`, `isOnlineWorkPaused()`, and `isSweepingPaused()` read independent immutable deployment values. There is no HTTP control, configuration refresh endpoint, or persistent activation latch. A stopped worker does not imply an inactive policy. Before first activation, all three defaults remain false; pauses may be enabled independently for maintenance.

After first approved activation, **retain `reservation-policy-enabled=true` on every serving instance, restart, recovery build, and rollback build**. Setting it false is unsupported, including as a kill switch. This invariant is operator-enforced, not database-enforced. Keep activation records and deployment checks outside the application. If configuration consistency cannot be guaranteed, block activation; after activation, keep maintenance in force rather than deploy an unsafe rollback.

### Required runtime policy matrix

These are caller requirements, not guards already wired by Task 2. “Online admission” includes checkout and new URL issuance. “Claims” means new scheduled claims, not a callback's own locked expiry/release decision.

| Active | Online paused | Sweep paused | Online admission | Scheduled claims | Callback/deadline interpretation |
|---|---|---|---|---|---|
| false | false | false | Existing pre-cutover eligibility | None | Legacy; proposed deadlines ineffective |
| false | false | true | Existing pre-cutover eligibility | None | Legacy; proposed deadlines ineffective |
| false | true | false | Reject with existing safe 409 envelope | None | Legacy; proposed deadlines ineffective |
| false | true | true | Reject with existing safe 409 envelope | None | Legacy; proposed deadlines ineffective |
| true | false | false | Effective deadline/quarantine checks | Eligible unpaid online only | Effective cutoff and null-deadline quarantine |
| true | false | true | Effective deadline/quarantine checks | None | Effective cutoff and null-deadline quarantine |
| true | true | false | Reject with existing safe 409 envelope | Eligible unpaid online only | Effective cutoff and null-deadline quarantine |
| true | true | true | Reject with existing safe 409 envelope | None | Effective cutoff and null-deadline quarantine |

- Neither pause affects COD semantics or permits online settlement of COD. Fully verified callbacks remain available during both pauses.
- Inactive ordinary legacy pending orders retain the old no-deadline contract, even if an approved-but-not-effective proposed batch contains past timestamps. An order older than 15 minutes may have a newly issued, still-valid URL. Abandoning a pre-activation proposal must not classify received money as late. Conflicting cancellation/release/payment evidence still requires review.
- Active eligible reservations compare database time sampled **after locking**: `databaseTime >= paymentExpiresAt` means expired. A sweep pause cannot let a late callback revive a reservation. A callback may make its own locked expiry/release decision while sweeping is paused.
- After activation, unpaid online null-deadline orders are quarantined. Deny new URLs, automatic completion, expiry, and settlement; a verified VNPay callback needing review returns `RspCode = "99"` without lifecycle writes. Confirmed-payment duplicates remain idempotent. Do not fill a deadline in a callback.
- Online pause changes admission only. Resume never replaces or extends a deadline. Keep received-money evidence, release-once markers, and atomic source-event contracts intact.

### Verified URL and review boundaries (Task 8)

- URL issuance checks the online pause before admission, then locks order and payment in that order with PostgreSQL transaction-local `lock_timeout = '2s'`. It refreshes both protected entities, verifies ownership, and samples `DatabaseClock` only after the locks for the active cutoff decision. Lock conflicts retain the existing safe HTTP409 envelope. Unknown/foreign orders retain the existing not-found response.
- Active eligible PENDING/PENDING online reservations sign the stored absolute `payment_expires_at`, converted to `Asia/Ho_Chi_Minh` for VNPay's second-resolution timestamp. Formatting discards subsecond precision; it never rounds up or adds time. Exact equality and later times are refused with HTTP409. Active null deadlines and contradictory unpaid/release/amount/item evidence require review. URL issuance/rejection never expires, releases, backfills, or emits a lifecycle fact.
- Inactive ordinary pending reservations retain the old issuance-time-plus-15-minute builder contract, including old orders and past proposed deadlines. This does not activate or change the stored proposal. The compatible builder overload remains available for inactive callers. Existing COD/paid/nonpending refusals remain unchanged.
- Online pause refuses issuance regardless of activation or sweep pause. Sweep pause alone changes neither URL cutoff nor callback interpretation. Resume reuses the same effective deadline. Pauses do not turn activation off.
- A fully verified callback that raises `ReservationReviewRequiredException` returns VNPay `{RspCode:"99", Message:"Reservation requires review"}`. MoMo had no named review message; it uses its existing HTTP400 bad-request `ApiResponse` shape with `code=400`, `message="reservation requires review"`, and `data=null`. Neither path acknowledges success, invents a settlement result, or writes lifecycle/stock/payment facts for the review case. Ordinary recorded `REFUND_REQUIRED` receipts retain their prior acknowledgements; review rejection does not claim a refund.
- Signature, merchant/reference/outcome checks and browser-return amount syntax remain mandatory. Browser return stays read-only and keeps HTTP303 redirect compatibility. Local signed fixtures and owned-scratch PostgreSQL checks do not authorize provider issuance, sandbox acceptance, activation, or actual legacy cutover.

## Restricted read-only inventory

`LegacyReservationQueries.inventory()` is an internal/operator-only Spring repository call. It has no public endpoint, startup hook, scheduler, provider call, or write method. Execute it only through an approved restricted backend operator session. This task exercises it only against seeded invocation-owned scratch databases.

The prepared, read-only SELECT includes every order, left-joins optional payments, and aggregates items before returning one row per order. `countsByPair` counts observed `(OrderStatus, PaymentMethod, PaymentStatus)` triples; null method/status explicitly means a missing payment, not COD or PENDING. A pair absent from the map has zero observed rows. Counts and evidence come from the same statement snapshot, not separate scans.

Each `OrderEvidence` includes surrogate/order/payment IDs, actual enum states, order/payment amounts, order creation time, stored deadline, cancellation reason, release marker, provider transaction reference, `paidAt`, item count, and item quantity. No names, addresses, phone numbers, email addresses, credentials, full payment URLs, or callback bodies are selected. Store any separately approved report with restricted access; do not expose it through customer APIs or routine logs. IDs/references must not become metric labels.

Evidence limits are explicit:

- `issuedUrlEvidence()` is `UNKNOWN`; `latestPossibleIssuedUrlExpiry()` is null. No issued-URL audit exists in the schema. Never derive a latest URL or its validity from `created_at`, `updated_at`, order status, or a proposed deadline.
- `fulfilmentEvidence()` is `UNKNOWN`, even for SHIPPING/COMPLETED. Order status and staff/customer role do not prove fulfilment or payment.
- `releaseEvidence()` is `RECORDED` only when `stock_released_at` exists. A missing marker means `UNKNOWN`, not “stock still reserved” or “already restored.” Existing cancelled rows commonly have null markers after the additive migration.
- Payment state, `paidAt`, amounts, and provider references are **recorded evidence**, not independent merchant reconciliation. Pending/failed records with conflicting money evidence remain unresolved.

The inventory recommendations are review queues, not automatic approval or lifecycle eligibility:

| Observed state | Recommendation and required disposition |
|---|---|
| Online CONFIRMED with payment PENDING or FAILED | `BLOCK_ACTIVATION`, including when other evidence conflicts or items are absent. `unresolvedConfirmedUnpaidCount` counts these rows. Reconcile actual payment, or approve a correction of a proven unfulfilled/unpaid order; staff acceptance is not proof of money. |
| Missing payment or missing items | `QUARANTINE`; obtain an owned reconciliation case. A confirmed-online blocker above remains a blocker. |
| COD with items/payment present, including confirmed/completed unpaid | `COD_UNCHANGED`; retain null deadline and existing permitted COD semantics. Review inconsistent evidence separately. Never sweep COD as unpaid online. |
| Online payment CONFIRMED or REFUND_REQUIRED | `PRESERVE_RECEIVED_MONEY`; do not expire as unpaid. Review conflicting order/release/fulfilment evidence without fabricating refunds or stock changes. |
| Online PENDING/PENDING, items present, no release marker or cancellation reason | `PENDING_RECONCILIATION`, regardless of proposed deadline age. Resolve URL/payment uncertainty and prove still unpaid before approving any candidate deadline. |
| Online COMPLETED/PENDING | `QUARANTINE`, null effective deadline, owned review. Never automatically reopen, cancel, restore stock, declare money received, or request another payment. |
| Online REFUNDED, cancelled/failed pairs, SHIPPING/unpaid, other inconsistencies | `QUARANTINE`; preserve all recorded evidence and obtain explicit disposition. A null release marker is not permission to restock. |

The blocker count is not a global “ready” flag. Missing/unknown evidence may independently block a cohort. Completed/unpaid cases may remain isolated only with owned cases and verified runtime quarantine guards. All five payment methods and five payment statuses are inventoried without assuming that method identifies a particular provider or that a role/status proves money.

## Maintenance, reconciliation, and first activation

1. Obtain approval and verify all runtime guard gates above. Keep policy inactive on every serving instance until the approved activation boundary. Do not treat this document, a successful scratch test, or an operator role as approval of actual data.
2. Set **both pause flags on every serving instance**, including callback-serving instances, scheduled workers, and restart/rollback templates. Confirm deployment configuration, not only worker process state. Hold conflicting staff/admin changes where required. COD may continue only if it does not violate the shared maintenance/locking boundary.
3. Drain already-admitted online checkout/URL requests and in-flight sweep transactions from every old instance. Pausing admission does not abort admitted work. Inventory/reconcile their committed outcomes before establishing the final issuance bound. Keep fully verified callbacks available; do not bypass signature, amount, currency, transaction-reference, or provider-outcome checks.
4. Obtain a restricted, separately approved actual inventory. Record cohort IDs, evidence sources, owned review cases, proposed actions, and unresolved blockers. An inventory is not a settlement snapshot: re-read each approved change under order/payment locks before application. Follow order, payment, then ascending surrogate variant locks; preserve existing short lock waits and safe conflict responses.
5. Honor outstanding valid URLs under the pre-cutover policy. After issuance and old requests are drained, establish the last possible issuance/expiry using authoritative issued-URL evidence or an approved conservative bound. Reconcile provider outcomes and delayed IPNs using approved merchant evidence. VNPay documents up to **10 IPN calls five minutes apart**; waiting 15 minutes or seeing no callback is not proof of no money. Unknown URL/payment history blocks that reservation's deadline activation. Do not add automatic provider query/refund integration.
6. Resolve all confirmed/unpaid nonterminal blockers before activation. Preserve received-money states. Keep completed/unpaid and other approved ambiguous cases quarantined with owned review cases. Do not infer unfulfilled/unpaid state from role, status, or timestamps.
7. Record approval of actual cohorts, URL/IPN reconciliation, dispositions, and the activation point **before writing effective legacy deadlines**. Apply only separately approved bounded locked changes while maintenance remains in force. `created_at + interval '15 minutes'` is at most a candidate for a reconciled, proven unpaid pending reservation. An already-due approved deadline is handled once by the ordinary post-activation lifecycle, not by stock restoration in the backfill. No startup, callback, inventory read, or migration performs legacy repair.
8. For old cancelled rows, assign a release marker only after separately proving the old authorized restock path already restored stock; do not increment stock again. If release history is unknown, retain an owned review case, not automatic restoration. Do not manufacture historical placement/confirmation events, payment facts, or fulfilment facts from schema changes.
9. Establish a consistent serving/callback cutover boundary, accounting for callbacks already admitted under the old interpretation. Prepare compatible non-serving instances and prove routing/drain can switch interpretation consistently while keeping fully verified callback reception available. Do not run mixed false/true callback responders as a normal rolling cutover. If consistent configuration, required drain, and callback reception cannot all be guaranteed, **block activation and retain maintenance**. This slice supplies no routing, buffering, or durable coordination mechanism.
10. At the recorded approved point, make policy true consistently across all serving instances, workers, restart templates, and approved rollback configurations. Verify effective-deadline and quarantine guards before resuming selected pause flags. Keep delivery and Realtime disabled unless their independent approvals exist. Actual data cutover and deployment remain separately authorized actions.

## Recovery and rollback

Before first activation, a proposed deadline batch remains ineffective. Abandoning the proposal is not a late-payment decision and does not authorize stock/payment writes. Preserve the pre-cutover contract for valid outstanding URLs and verified callbacks.

After activation, pause new online work and/or scheduled claims independently; for unsafe recovery use both pauses, drain admitted work, and hold conflicting mutations. Keep callback reception fully verified and effective deadlines/quarantine enforced. Preserve deadlines, release markers, received-money evidence, event identities, sequences, and atomic appends.

Restart or roll back **only** to code retaining the effective deadline, quarantine, release-once, and event contracts, with activation still true everywhere. Never use false, a stopped worker, deadline extension/replacement, legacy backfill reversal, or deadline-ignorant code as recovery. If no compatible consistent rollback exists, keep maintenance in force and escalate to the operator rather than deploy an unsafe rollback. Resume selected pauses without changing deadlines or other persisted facts.

## Evidence boundary

Task 2 tests verify property defaults/all eight flag combinations across fresh contexts, all enum triples plus missing data on real migrated PostgreSQL, local signed URL construction for an old order, explicit unknowns, read-only execution, and invocation-owned cleanup. They do not prove live URL/provider validity, runtime callback settlement, checkout admission, completion quarantine enforcement, scheduled claim suppression, cutover routing, or an irreversible database latch. Require the later task-specific real PostgreSQL and paused URL/worker regressions before live activation.
