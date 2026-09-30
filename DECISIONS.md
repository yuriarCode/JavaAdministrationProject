# DECISIONS.md

Inventory reservation service. This document records the assumptions, what was left out, and what I would change before going to production.

## How it is organized

| Class | Responsibility |
| --- | --- |
| `Inventory` | Entry point (signature untouched). Only wires the service with the current rules. |
| `InMemoryInventoryService` | Orchestrates reserve / confirm / available and the alerts. |
| `ProductStock` | State of one SKU: stock, reserved units, expiry queue, alert state. |
| `ReservationRecord` | Lifecycle of a reservation: `PENDING → CONFIRMED` or `PENDING → EXPIRED`. |
| `CategoryPolicies` / `CategoryPolicy` | The **single** table with each category's payment window and per-order limit. |
| `CompositeStockAlertListener` | Sends one alert to several channels (email today, more tomorrow). |

The `com.store.inventory.api` package was not touched. The three original tests pass unchanged.

## Main decisions

1. **App retries (idempotency).** The app resends an order on slow connections, so `reserve` with the same `orderId` and the same data returns the original reservation: it does not hold units again or extend the window. A repeated `confirm` is a no-op. This is not in the contract, but without it a retry would take stock away from other customers.
2. **New categories every season.** The enum lives in `api` and cannot be edited here. The rules live in a table (`CategoryPolicies.defaults()`) with no default policy: a category without a rule fails with `IllegalStateException` when registering the product, and `CategoryPoliciesTest.everyCategoryHasAPolicy` breaks the build. This avoids silently applying wrong rules (e.g. "no limit").
3. **No overselling under concurrency.** One lock per product (`synchronized`), not a global one, so a popular SKU does not slow down the rest. `ConcurrencyTest` fires 256 simultaneous orders for 50 units and requires exactly 50 reservations.
4. **Lazy expiration.** No threads or timers: expired reservations are released when the product is touched, using only the injected `Clock`. It is deterministic and testable without `sleep`.
5. **Low-stock alerts outside the lock.** The listener (email) may be slow or fail; it is never called while holding the lock, and a failure does not undo the reservation. If it fails, the alert is re-armed and retried on the next operation on that product.

## Assumptions (where the README is ambiguous)

- **Exact expiry:** a reservation stops being live *at* `expiresAt` (not after). Confirming at exactly that instant fails.
- **Confirming an expired reservation** throws `IllegalStateException` (contract). The units may already have been sold to another customer.
- **Reserving with an `orderId` already used with different data** (another SKU or quantity) throws `IllegalArgumentException`. If the previous reservation has already expired, the new attempt replaces it.
- **Validation order in `reserve`:** invalid quantity → unknown product (no units) → per-order limit → stock. So asking for 3 units of `FLASH_SALE` fails on the limit even if there is no stock.
- **Low-stock alert:** emitted when a reservation leaves availability at ≤ 5 (5 counts). It is not emitted when initial stock is added already low, nor for products with 0 units that were never reserved.
- **"Until it is restocked":** the alert is re-armed when stock comes in (`addStock`) or when availability goes back above 5 (because a reservation expired). When in doubt I prefer one alert too many over losing a real one; the strict alternative (re-arm only on `addStock`) could leave a product unannounced if it sells out after its reservations expired.
- **Registering a product twice** with the same category is harmless; with a different category it is rejected, because it would change the rules of reservations in flight.

## What I left out

- **Persistence:** everything lives in memory and is lost on restart.
- **Multi-instance:** locks are local to the process. With several instances, two of them could oversell the same SKU.
- **Order cleanup:** the `orderId` map grows without bound (kept to detect retries).
- **Explicit release** (cancelling a reservation before it expires): not in the contract.
- **Metrics and traces** (reservations, rejections, expirations, failed alerts). There is only a warning log when an alert fails.
- **Concrete alert channels** (email, Slack): only the mechanism to combine them exists.

## What I would change before production

1. **A database as the source of truth** with concurrency control there: a conditional update (`UPDATE ... SET reserved = reserved + :n WHERE stock - reserved >= :n`) or a row lock on the SKU. A unique key on `orderId` so idempotency works across instances.
2. **Expiration via a job** that releases expired reservations (or a query that ignores them), because in-memory lazy expiration does not work with several instances.
3. **Outbox pattern for alerts:** store the alert in the same transaction and deliver it asynchronously with retries. Today it is delivered synchronously on the client's thread, and if the process dies right before alerting, the alert is lost.
4. **Policy table in configuration or a DB**, so a rule change does not require a deploy. Today it still means touching code and deploying.
5. **Purging old orders** and metrics for reservations, expirations, rejections and failed alerts.
6. **Concurrency tests against the real DB** (today only the in-memory model is tested).

## How to verify

```bash
mvn test   # Java 21; 41 tests (3 original + 38 new)
```

The new tests cover: payment window per category (with a 1-second boundary), per-order limit, retries, expiration and reservation by another customer, alerts (threshold, no repeats, re-arm, failing listener), coverage of the category enum, multiple channels, and concurrency.
