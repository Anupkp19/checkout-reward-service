# DECISIONS


1. **No oversell.** For any product, the sum of quantities across all successfully placed orders never exceeds the seed inventory.
2. **A cart yields at most one order.** Two checkouts of the same cart either return the same order or one fails cleanly.
3. **Retry-safe checkout.** A retry with the same `Idempotency-Key` and the same body returns the same response; a retry with the same key and a *different* body returns 409 (client bug).
4. **Coupon single-use.** A coupon is redeemed on exactly one successful order.
5. **Coupon durability.** A failed checkout does not consume its coupon.
6. **Milestone uniqueness.** Every Nth order reveals exactly one coupon slot, and each slot is filled at most once.
7. **Immutable orders.** An order captures product name and unit price at checkout time; deleting or renaming a product later does not distort past orders.
8. **Money is integer.** All arithmetic is in `long` cents; percent discounts use `BigDecimal` with `HALF_UP`.
9. **Reporting reconciles and is idempotent.** The report is a pure aggregation of orders and coupons; repeated calls return the same result.

Every invariant has at least one integration test that would fail if the invariant were violated. `ConcurrencyIT` is the primary evidence.

---

## Ambiguities & the semantics I chose

| Ambiguity | Chosen semantics |
|---|---|
| Cart price/inventory changes between add and checkout. | **Inventory is authoritative at checkout time**; the cart carries only `productId` + `quantity`. `GET /carts/{id}` shows the *current* unit price and inventory so the client can react. Adding an item does not "reserve" stock. |
| What happens when the client retries a request without an idempotency key. | We still refuse to double-order per cart: `CheckoutService` looks up an existing order by `cartId` before doing any work and returns it. Clients that don't send keys therefore get retry safety *up to the first response the server produced*, but lose the ability to distinguish "same request replayed" from "different request with the same cart". This is explicit in the code and the README. |
| Coupon on an order whose total is below the discount. | `discount = min(gross, percentOf(gross))` so `net >= 0`. |
| Cart with 0 items at checkout. | 400 `empty_cart`. |
| Adding the same product twice to a cart. | Quantities are summed on the existing line (no duplicate lines). |
| A coupon exists but never gets redeemed. | Stays `AVAILABLE` indefinitely. No expiry. Deferred. |
| Multiple pending coupons queued (e.g. 15 orders placed with none generated). | `POST /admin/coupons/generate` mints one per call, from the smallest unrewarded milestone. The admin has to call three times to catch up. Simpler than a "mint all" endpoint and matches the spec's "an administrator can request coupon generation". |

---

## Material design decisions

### Decision: Pessimistic row locks instead of optimistic retry

**Context:** Two concurrent checkouts can (a) oversell an item, (b) share a coupon, or (c) double-order the same cart. I needed one predictable rule that covers all three.

**Options considered:**
- Optimistic (`@Version`) with retry-on-conflict at the controller. Simple in the happy path, but conflict rates spike exactly when we most need correctness (a hot product, a hot coupon). And retry loops interact poorly with idempotency keys.
- Application-level `synchronized` mutex per product/coupon id. Doesn't survive multi-instance.
- Pessimistic `SELECT ... FOR UPDATE` on the mutable rows.

**Choice:** `@Lock(LockModeType.PESSIMISTIC_WRITE)` on `products`, `carts`, and `coupons` inside the checkout transaction. Product locks are acquired in id-sorted order to prevent deadlocks between carts that overlap on multiple products.

**Why:** The critical section is short (one DB round-trip's worth of work) and contention is rare on a normal load; when it isn't rare, a serialized queue is exactly the behavior I want. This works identically on H2 today and on Postgres tomorrow.

**Consequences:** Straightforward code; no retry logic in the service. Requires JPA to open a real transaction (annotated everywhere it matters). Cost is a small amount of blocking on the hot rows.

### Decision: `Idempotency-Key` header + `cartId` fallback

**Context:** The brief demands retries not create a second order or charge inventory twice.

**Options considered:**
- Idempotency keys alone. Complete solution, but relies on clients cooperating.
- Uniqueness on `orders.cart_id`. Cheap, but a raw uniqueness violation is a bad error to return.
- Both.

**Choice:** Both. `CheckoutService` first checks a stored idempotency response (retry replay); then locks the cart and, if an order already exists for that cart, returns it. `orders.cart_id` is `UNIQUE` in the schema as a belt-and-braces guarantee.

**Why:** Belt for good clients, braces for lazy ones, and the database catches anything the app logic missed. The "same key, different body → 409" branch prevents accidental key reuse from silently succeeding.

**Consequences:** Idempotency records accumulate. In production I'd prune by TTL (see "Deferred").

### Decision: Snapshot lines on the order

**Context:** "An order must retain enough information to explain what the customer purchased ... even if product data later changes."

**Choice:** `OrderLine` copies `productId`, `productName`, `unitPriceCents`, `quantity`, and computes `lineTotalCents = unit * quantity`. No FK back to `products.id` for the sake of restoring a name — we already have the name on the line.

**Why:** The order table becomes an immutable audit log. Reporting reads only orders — the product table can be renamed or wiped without changing historical revenue.

**Consequences:** Slightly more storage; no join to render an order.

### Decision: Coupon milestone is a unique long, not a status flag

**Context:** "A coupon is generated only if the configured order milestone has been reached and a coupon has not already been generated for that milestone." Two admins hitting `generate` at once must not both succeed for the same milestone.

**Choice:** `coupons.milestone` is a `UNIQUE` column. `CouponService.generateNextEligible` computes the next milestone from `orders.count() / n` minus `coupons.count()`, inserts with that value, and treats `DataIntegrityViolationException` as "someone raced me — recount and try the next slot once". Second failure returns 409.

**Why:** The database is the arbiter of uniqueness. No advisory lock table, no coordination service.

**Consequences:** Two-attempt bounded retry inside one request keeps the endpoint predictable; a persistent race (three admins simultaneously) is theoretically possible but degrades to a 409 that the caller can retry.

### Decision: `long` cents + `BigDecimal` for the percent step

**Context:** "Calculate money without floating-point rounding errors."

**Choice:** Every stored amount is `long` cents. The single place we do a percent multiplication is `Money.percentDiscountCents`, which builds `BigDecimal` values and rounds with `HALF_UP`. That's the deterministic rule the tests assert against.

**Why:** `long` arithmetic is exact and fast; centralizing the one non-integer operation gives us one thing to reason about.

**Consequences:** Any future currency with sub-cent minor units would need to bump the minor-unit scale in one place.

### Decision: Structured error envelope with stable `code` slugs

**Context:** "Return errors that are distinguishable and useful to an API client."

**Choice:** Every error is `{ code, message, details? }`. `code` is a stable snake-case slug (`insufficient_inventory`, `coupon_not_available`, `idempotency_key_reused`, ...). Clients switch on `code`, humans read `message`, and `details` carries structured extras (e.g. which product ran out and by how much).

**Why:** Enum-shaped codes are safer to depend on than status codes alone (many things return 409). Extras remove the need for a client to parse strings.

---

## Transaction, concurrency, and idempotency strategy in one paragraph

Checkout runs in a single `READ_COMMITTED` transaction. It (1) replays an idempotent response if one exists, (2) row-locks the cart, (3) short-circuits with the existing order if the cart already produced one, (4) row-locks the products in id-sorted order, (5) validates inventory against the locked snapshot, (6) row-locks the coupon (if any), (7) builds the order snapshot, decrements inventory, marks the coupon redeemed, closes the cart, saves the order, and (8) stores the idempotency record so retries replay. Any failure aborts the transaction, so locks release with no state change and the coupon stays `AVAILABLE`.

Coupon generation is its own transaction, guarded by a `UNIQUE(milestone)` constraint. Orders are counted with a plain `count(*)`, which is safe because the counter is monotonic — a milestone reached is a milestone that stays reached.

---

## Money and rounding rules

- Every persisted amount is `long` cents.
- Line total: `unitPriceCents * quantity`, using `Math.multiplyExact` so any overflow throws instead of wrapping.
- Discount: `BigDecimal(gross) * BigDecimal(percent) / 100` with `RoundingMode.HALF_UP` to zero scale (whole cents).
- `discount = min(gross, computed)` so net never goes negative.

---

## Error model

Codes actually used (see `GlobalExceptionHandler`):

| Code | HTTP |
|---|---|
| `validation_error` | 400 |
| `bad_request` | 400 |
| `unknown_product` | 400 |
| `invalid_quantity` | 400 |
| `invalid_coupon` | 400 |
| `empty_cart` | 400 |
| `cart_not_found` | 404 |
| `item_not_in_cart` | 404 |
| `order_not_found` | 404 |
| `cart_not_open` | 409 |
| `insufficient_inventory` | 409 |
| `coupon_not_available` | 409 |
| `product_missing` | 409 |
| `idempotency_key_reused` | 409 |
| `no_milestone_available` | 409 |

`5xx` is reserved for genuine server faults.

---

## What I implemented vs deferred

**Implemented:**
- Full cart lifecycle with per-line current-price/inventory hints.
- Retry-safe checkout with idempotency-key + cart-level fallback.
- Pessimistic locking for inventory and coupons.
- Milestone coupon generation with concurrency-safe uniqueness.
- Order snapshot immutability.
- Admin report with product breakdown and coupon accounting.
- Structured errors with stable codes.
- Integration tests covering happy path, idempotency, coupon lifecycle, reporting, and four concurrency invariants.

**Deferred (explicitly):**
- **AuthN/AuthZ.** All `/admin/*` routes are the admin surface; there is no gate. In production I would put an `HttpSecurity` filter on `/admin/**` requiring a role, and probably a per-user idempotency namespace so keys can't collide across tenants.
- **Payment.** Successful checkout = successful payment. A real integration would introduce a payment step *after* inventory reservation but *before* order finalization, with the coupon reservation held under a short TTL. This changes the state machine (`Cart → Reserved → PaymentPending → Placed | Reversed`) — non-trivial and out of scope for the timebox.
- **Idempotency-record TTL.** Records accumulate. A daily prune of anything older than 24h is enough.
- **Coupon expiry.** Currently coupons never expire.
- **Cart abandonment.** The `ABANDONED` status exists but nothing writes it. A background job would move stale open carts.
- **Rate limiting and admin audit logs.** Standard hardening, not novel.
- **OpenAPI generation.** README documents the endpoints; `springdoc-openapi` would auto-generate a spec in a few lines.

---

## How this would evolve for multiple instances and production scale

The design already assumes it: pessimistic row locks and DB uniqueness constraints, no in-memory state. Moving from H2 to Postgres is a dependency swap. What changes:

- **Locks become `SELECT ... FOR UPDATE` in Postgres.** Same JPA annotations, same semantics, better concurrency due to MVCC.
- **Idempotency table gets a partial index on `(endpoint, key)` and a TTL job.**
- **Coupon generation** could move to a background dispatcher keyed off an `orders` counter, but the current on-demand admin endpoint stays valid.
- **Reporting** would grow past "scan all orders". Two paths: a materialized view refreshed on a schedule, or a running counter maintained by an outbox → analytics store. Either is standard; the current implementation is correct but O(orders).
- **Multiple app instances** are already safe: no in-process state, coordination is entirely in the DB.
- **Payment webhooks** would go through an inbound idempotency table with the same scheme used for `Idempotency-Key`.

---

## How I used AI tools

I used Claude Code (this session) to author the whole project against the brief. It produced the initial skeleton, the entity mapping, and the first pass of every controller and service. I directed the model — picked the stack, the concurrency strategy, the DTO shape, and the invariants — and let it produce the code.

**One case where I materially redirected the model's output:** the first draft of `CheckoutService` treated `Idempotency-Key` as the *only* protection against double-orders, so a retry without a key on a cart that had already succeeded could produce a second order. I insisted on a second layer: the transaction now locks the cart, looks up `orders.findByCartId`, and returns the existing order if there is one — with `orders.cart_id UNIQUE` as a schema-level backstop. The `ConcurrencyIT.checkoutWithoutIdempotencyKeyStillProtectsAgainstDoubleOrder` test enforces it.

I also rejected a first-draft coupon generator that assumed a single admin caller and used `count() + 1` without a uniqueness constraint. That version would have double-issued a coupon under two concurrent admin requests. The current implementation uses `UNIQUE(milestone)` and treats the DB integrity violation as the source of truth.

---

## If I had another two hours

1. **Set up Postgres + Testcontainers.** Rerun the concurrency suite against a real DB. H2's lock behavior is close but not identical.
2. **Move the payment abstraction in.** A `PaymentGateway` interface with a fake implementation, and split checkout into `reserve → charge → finalize`. Then write a test for a charge that succeeds on the gateway but times out on our side.
3. **Rate-limit the admin surface and add an admin audit log.** Cheap, worth doing.
4. **springdoc-openapi + a Postman collection** committed to the repo.
