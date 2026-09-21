# Checkout & Rewards Service

Spring Boot (Java 21) backend for a small ecommerce store: products, carts,
retry-safe checkout, orders, milestone coupons, and an admin report.

Correctness first: no oversell under contention, no double-charge on retry,
coupons are single-use and don't leak on failed checkouts, and money never
uses floats.

---

## Requirements

- Java 21
- Maven 3.9+
- `curl` (and optionally `jq` for pretty JSON output in demo steps)

---

## Quick start

Everything is driven through the `Makefile`. From the project root:

```bash
make help
```

Shows every available target grouped by purpose (server, demo, utilities).

### Start the server

```bash
make start
```

Builds the jar (`mvn -q -DskipTests package`) and launches it in the background
under `caffeinate` so the server keeps running when the laptop lid closes.

- Listens on `http://localhost:8080`
- Data file: `./data/checkout.mv.db` (H2, file-backed)
- Logs: `./server.log`
- PID:  `./server.pid`

Verify it is up:

```bash
make products
```

### Stop the server

```bash
make stop
```

### Rebuild without starting

```bash
make build
```

### Run the test suite

```bash
make test
```

The `ConcurrencyTest` suite hammers the live HTTP endpoint from 20 threads and
is the primary correctness gate. It covers oversell prevention, retry of the
same idempotency key, coupon single-use under a race, and coupon preservation
on a failed checkout.

---

## Run the demo

With the server running:

```bash
make demo
```

Executes all 15 steps end-to-end and prints request/response for each:

| Step | Target                  | What it does                                          |
|------|-------------------------|-------------------------------------------------------|
| 1    | `make products`         | List catalog                                          |
| 2    | `make cart-create`      | Create a cart (saves `CART_ID` to `/tmp/cart_id`)     |
| 3    | `make cart-add-coffee`  | Add 2x SKU-COFFEE                                     |
| 3    | `make cart-add-grinder` | Add 1x SKU-GRINDER                                    |
| 3    | `make cart-add-mug`     | Add 1x SKU-MUG                                        |
| 4    | `make cart-update-coffee` | Set SKU-COFFEE quantity to 3                        |
| 5    | `make cart-remove-mug`  | Remove SKU-MUG                                        |
| 6    | `make cart-view`        | View cart with live totals                            |
| 7    | `make checkout`         | Place order (saves `ORDER_ID` to `/tmp/order_id`)     |
| 8    | `make checkout-retry`   | Retry same key → same order replayed                  |
| 9    | `make order-get`        | Fetch immutable order snapshot                        |
| 10a  | `make error-bad-product`  | 400 `unknown_product`                               |
| 10b  | `make error-closed-cart`  | 409 `cart_not_open`                                 |
| 10c  | `make error-no-inventory` | 409 `insufficient_inventory`                        |
| 11   | `make milestone-orders` | Place 4 more orders to reach milestone 5              |
| 12   | `make coupon-generate`  | Mint coupon for milestone 5                           |
| 13   | `make coupon-checkout`  | Checkout with 10% coupon applied                      |
| 14   | `make coupon-reuse`     | 409 coupon already redeemed                           |
| 15   | `make report`           | Admin reconciliation report                           |

Each target is independently runnable — `make cart-view` after `make cart-create`
just works, because `CART_ID` and `ORDER_ID` are persisted to `/tmp` between calls.

---

## H2 console

Browse the live database while the server runs:

```
http://localhost:8080/h2
```

Login:

| Field       | Value                                                       |
|-------------|-------------------------------------------------------------|
| JDBC URL    | `jdbc:h2:file:./data/checkout;AUTO_SERVER=TRUE;MODE=LEGACY` |
| User Name   | `test`                                                      |
| Password    | `test`                                                      |
| Driver Class| `org.h2.Driver`                                             |

`AUTO_SERVER=TRUE` lets the console attach to the same DB file the Spring app
holds open, so you can inspect data without stopping the service.

Tables you'll see: `PRODUCTS`, `CARTS`, `CART_ITEMS`, `ORDERS`, `ORDER_LINES`,
`COUPONS`, `IDEMPOTENCY`.

### Seed data

Six products are seeded on first startup (only if the products table is empty
— restarts don't overwrite live inventory). `SKU-GRINDER` has 3 units and is
the intended "limited inventory" fixture.

---

## API summary

- **OpenAPI 3.1 spec:** [`openapi.yaml`](openapi.yaml). Paste into
  https://editor.swagger.io for an interactive view, or feed it to any codegen.
- **Full reference:** [`API.md`](API.md).

All bodies are JSON. Money is always integer cents (`long`). Errors follow the
shape `{ "code": "<stable_slug>", "message": "...", "details": { ... } }`.

### Products

`GET /products` → `200` list all products with current price and inventory.

### Carts

| Method   | Path                              | Purpose                                           | Success        |
|----------|-----------------------------------|---------------------------------------------------|----------------|
| `POST`   | `/carts`                          | Create empty cart.                                | `201 CartView` |
| `GET`    | `/carts/{id}`                     | View cart + totals + per-line current price.      | `200`          |
| `POST`   | `/carts/{id}/items`               | Add `{ productId, quantity }` (sums duplicates).  | `200`          |
| `PUT`    | `/carts/{id}/items/{productId}`   | Replace quantity `{ quantity }`.                  | `200`          |
| `DELETE` | `/carts/{id}/items/{productId}`   | Remove line.                                      | `200`          |

Common errors:
- `400 unknown_product` — product id not in catalog.
- `400 invalid_quantity` — quantity < 1.
- `404 cart_not_found`, `404 item_not_in_cart`.
- `409 cart_not_open` — cart already checked out.

### Checkout

`POST /carts/{id}/checkout`

- Body (optional): `{ "couponCode": "SAVE-XXXX" }`
- Header (recommended): `Idempotency-Key: <opaque string, ≤ 128 chars>`

Success returns `200 OrderView` with `orderId`, `lines`, `grossCents`,
`discountCents`, `netCents`, and coupon fields when applied.

Errors:
- `400 empty_cart`
- `400 invalid_coupon`
- `404 cart_not_found`
- `409 cart_not_open`
- `409 insufficient_inventory` — `details.shortfalls: [{productId, requested, available}]`
- `409 coupon_not_available`
- `409 product_missing`
- `409 idempotency_key_reused` — same key with a different request body

### Orders

`GET /orders/{id}` → `200 OrderView` (immutable snapshot).

### Admin

- `POST /admin/coupons/generate` — mints a coupon for the smallest reached-but-unrewarded milestone.
- `GET  /admin/report` — reconciled summary (read-only, idempotent).

Authentication/authorization is not implemented; all `/admin/*` routes are the
administrative surface (see [`DECISIONS.md`](DECISIONS.md)).

---

## Layout

```
src/main/java/com/example/checkout
├── CheckoutApplication.java
├── config/          RewardsConfig (n, x%), Seed
├── domain/          JPA entities: Product, Cart, CartItem, Order, OrderLine, Coupon, IdempotencyRecord
├── repo/            Spring Data repos with @Lock(PESSIMISTIC_WRITE) on the hot rows
├── service/         CartService, CheckoutService, CouponService, ReportService, IdempotencyService
├── web/             Controllers + Dtos
├── error/           ApiException + GlobalExceptionHandler
└── money/Money.java integer-cent arithmetic + HALF_UP rounded percent discount
```

See [`DECISIONS.md`](DECISIONS.md) for invariants, trade-offs, and what was deferred.

---

## Common workflows

Fresh start:

```bash
make stop
rm -f data/checkout.mv.db data/checkout.lock.db
make start
make demo
```

Just poke at one step:

```bash
make start
make cart-create
make cart-add-coffee
make checkout
make order-get
```
