# Checkout & Rewards Service — API Documentation

**Base URL:** `http://localhost:8080`  
**Content-Type:** `application/json` (all requests with a body)

---

## Conventions

### Money
All monetary values are `long` integers in **cents**. `1499` = $14.99. No floats anywhere.

### Error envelope
Every error — regardless of HTTP status — returns this shape:
```json
{
  "code": "snake_case_slug",
  "message": "Human-readable description.",
  "details": { }
}
```
`details` is only present when extra context is available. Branch on `code`, not HTTP status alone — many different situations return `409`.

### Idempotency
The checkout endpoint supports an optional `Idempotency-Key` header (opaque string, ≤ 128 chars).
- **Same key + same body** → replays the stored response, no side effects.
- **Same key + different body** → `409 idempotency_key_reused`.
- **No key** → still safe: the server checks if the cart already has an order and returns it instead of creating a second one.

---

## Data Models

### `Product`
```json
{
  "id": "SKU-COFFEE",
  "name": "Single-origin coffee 250g",
  "unitPriceCents": 1499,
  "inventory": 100
}
```

### `Cart`
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "name": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998,
      "availableInventory": 100,
      "priceChangedSinceAdd": false
    }
  ],
  "subtotalCents": 2998
}
```
`status` values: `OPEN` · `CHECKED_OUT` · `ABANDONED`  
`availableInventory` is `null` if the product was deleted after being added.

### `Order`
```json
{
  "orderId": "ord_a1b2c3d4e5f6789012345",
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "placedAt": "2026-09-20T10:00:00Z",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "productName": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998
    }
  ],
  "grossCents": 2998,
  "discountCents": 300,
  "netCents": 2698,
  "couponCode": "SAVE-K7X2M9RQ",
  "couponPercent": 10
}
```
Order lines are an **immutable snapshot** — product name and price are baked in at checkout time.  
`couponCode` and `couponPercent` are `null` if no coupon was used.

### `Coupon`
```json
{
  "code": "SAVE-K7X2M9RQ",
  "percent": 10,
  "milestone": 5,
  "status": "AVAILABLE",
  "createdAt": "2026-09-20T10:00:00Z"
}
```
`status` values: `AVAILABLE` · `REDEEMED`

---

## Endpoints

---

### GET /products

Returns all products with current price and inventory.

**curl**
```bash
curl -s http://localhost:8080/products
```

**Response `200`**
```json
[
  { "id": "SKU-COFFEE",     "name": "Single-origin coffee 250g", "unitPriceCents": 1499,  "inventory": 100 },
  { "id": "SKU-KETTLE",    "name": "Electric kettle 1.7L",       "unitPriceCents": 6900,  "inventory": 25  },
  { "id": "SKU-MUG",       "name": "Ceramic mug",                "unitPriceCents": 999,   "inventory": 200 },
  { "id": "SKU-GRINDER",   "name": "Manual burr grinder",        "unitPriceCents": 12900, "inventory": 3   },
  { "id": "SKU-FILTERS",   "name": "Paper filters (100 pack)",   "unitPriceCents": 499,   "inventory": 500 },
  { "id": "SKU-SUBSCRIBE", "name": "Monthly bean subscription",  "unitPriceCents": 2999,  "inventory": 50  }
]
```

---

### POST /carts

Creates a new empty cart with status `OPEN`.

**curl**
```bash
curl -s -X POST http://localhost:8080/carts
```

**Request body:** none

**Response `201`**
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [],
  "subtotalCents": 0
}
```

> Save the `cartId` — you will need it for every cart and checkout call below.

---

### GET /carts/{id}

Returns the cart with **live** prices and inventory on every line. Prices are resolved at read time, not snapshotted at add time.

**curl**
```bash
curl -s http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890
```

**Request body:** none

**Response `200`**
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "name": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998,
      "availableInventory": 100,
      "priceChangedSinceAdd": false
    },
    {
      "productId": "SKU-GRINDER",
      "name": "Manual burr grinder",
      "unitPriceCents": 12900,
      "quantity": 1,
      "lineTotalCents": 12900,
      "availableInventory": 3,
      "priceChangedSinceAdd": false
    }
  ],
  "subtotalCents": 15898
}
```

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `cart_not_found` | 404 | No cart with that ID |

---

### POST /carts/{id}/items

Adds a product to the cart. If a line for that product already exists, quantities are **summed** — no duplicate lines are created.

**curl**
```bash
curl -s -X POST http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/items \
  -H 'Content-Type: application/json' \
  -d '{
    "productId": "SKU-COFFEE",
    "quantity": 2
  }'
```

**Request body**
```json
{
  "productId": "SKU-COFFEE",
  "quantity": 2
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `productId` | string | yes | Must exist in the catalog |
| `quantity` | int | yes | ≥ 1 |

**Response `200`** — updated Cart
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "name": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998,
      "availableInventory": 100,
      "priceChangedSinceAdd": false
    }
  ],
  "subtotalCents": 2998
}
```

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `unknown_product` | 400 | `productId` not found in catalog |
| `invalid_quantity` | 400 | `quantity` < 1 |
| `validation_error` | 400 | Missing required field |
| `cart_not_found` | 404 | No cart with that ID |
| `cart_not_open` | 409 | Cart is already checked out or abandoned |

**Error response examples**
```json
{ "code": "unknown_product",  "message": "product not found", "details": { "productId": "SKU-FAKE" } }
{ "code": "invalid_quantity", "message": "quantity must be >= 1" }
{ "code": "cart_not_open",    "message": "cart is not open for modification", "details": { "status": "CHECKED_OUT" } }
```

---

### PUT /carts/{id}/items/{productId}

Replaces the quantity on an existing cart line. To add to the existing quantity instead, use `POST /items`.

**curl**
```bash
curl -s -X PUT http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/items/SKU-COFFEE \
  -H 'Content-Type: application/json' \
  -d '{
    "quantity": 3
  }'
```

**Request body**
```json
{
  "quantity": 3
}
```

| Field | Type | Required | Constraint |
|---|---|---|---|
| `quantity` | int | yes | ≥ 1 |

**Response `200`** — updated Cart
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "name": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 3,
      "lineTotalCents": 4497,
      "availableInventory": 100,
      "priceChangedSinceAdd": false
    }
  ],
  "subtotalCents": 4497
}
```

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `invalid_quantity` | 400 | `quantity` < 1 |
| `cart_not_found` | 404 | No cart with that ID |
| `item_not_in_cart` | 404 | Product not on any line in this cart |
| `cart_not_open` | 409 | Cart is not `OPEN` |

---

### DELETE /carts/{id}/items/{productId}

Removes a line from the cart.

**curl**
```bash
curl -s -X DELETE http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/items/SKU-COFFEE
```

**Request body:** none

**Response `200`** — updated Cart (line removed)
```json
{
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "status": "OPEN",
  "lines": [],
  "subtotalCents": 0
}
```

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `cart_not_found` | 404 | No cart with that ID |
| `item_not_in_cart` | 404 | Product not on any line |
| `cart_not_open` | 409 | Cart is not `OPEN` |

---

### POST /carts/{id}/checkout

Validates the cart and places an order. All-or-nothing — any failure leaves inventory, coupons, and cart state completely unchanged.

#### Without coupon

**curl**
```bash
curl -s -X POST http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/checkout \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-unique-key-001' \
  -d '{}'
```

**Request body**
```json
{}
```

**Response `200`**
```json
{
  "orderId": "ord_a1b2c3d4e5f6789012345",
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "placedAt": "2026-09-20T10:00:00Z",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "productName": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998
    }
  ],
  "grossCents": 2998,
  "discountCents": 0,
  "netCents": 2998,
  "couponCode": null,
  "couponPercent": null
}
```

#### With coupon

**curl**
```bash
curl -s -X POST http://localhost:8080/carts/b2c3d4e5-f6a7-8901-bcde-f12345678901/checkout \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-unique-key-002' \
  -d '{
    "couponCode": "SAVE-K7X2M9RQ"
  }'
```

**Request body**
```json
{
  "couponCode": "SAVE-K7X2M9RQ"
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `couponCode` | string | no | A valid, unredeemed coupon code |

**Response `200`** — 10% off $129.00 grinder = $12.90 discount
```json
{
  "orderId": "ord_b2c3d4e5f6789012345ab",
  "cartId": "b2c3d4e5-f6a7-8901-bcde-f12345678901",
  "placedAt": "2026-09-20T10:05:00Z",
  "lines": [
    {
      "productId": "SKU-GRINDER",
      "productName": "Manual burr grinder",
      "unitPriceCents": 12900,
      "quantity": 1,
      "lineTotalCents": 12900
    }
  ],
  "grossCents": 12900,
  "discountCents": 1290,
  "netCents": 11610,
  "couponCode": "SAVE-K7X2M9RQ",
  "couponPercent": 10
}
```

#### Retry — same Idempotency-Key

Sending the same key and body again replays the stored response. No new order is created and no inventory is touched.

**curl**
```bash
curl -s -X POST http://localhost:8080/carts/a1b2c3d4-e5f6-7890-abcd-ef1234567890/checkout \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: my-unique-key-001' \
  -d '{}'
```

**Response `200`** — identical `orderId` as the original call

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `empty_cart` | 400 | Cart has no items |
| `invalid_coupon` | 400 | `couponCode` does not exist |
| `cart_not_found` | 404 | No cart with that ID |
| `cart_not_open` | 409 | Cart is already `CHECKED_OUT` or `ABANDONED` |
| `insufficient_inventory` | 409 | One or more products lack enough stock |
| `coupon_not_available` | 409 | Coupon exists but is already redeemed |
| `product_missing` | 409 | A product in the cart was deleted from the catalog |
| `idempotency_key_reused` | 409 | Same key used with a different request body |

**Error response examples**
```json
{ "code": "empty_cart", "message": "cart is empty" }

{ "code": "invalid_coupon", "message": "coupon does not exist", "details": { "code": "SAVE-NOPE" } }

{
  "code": "insufficient_inventory",
  "message": "one or more products lack sufficient inventory",
  "details": {
    "shortfalls": [
      { "productId": "SKU-GRINDER", "requested": 5, "available": 3 }
    ]
  }
}

{ "code": "coupon_not_available",   "message": "coupon is not available for redemption", "details": { "code": "SAVE-K7X2M9RQ", "status": "REDEEMED" } }
{ "code": "product_missing",        "message": "cart references products that no longer exist", "details": { "productIds": ["SKU-DELETED"] } }
{ "code": "idempotency_key_reused", "message": "Idempotency-Key was reused with a different request body" }
```

---

### GET /orders/{id}

Returns an immutable order snapshot. Product names and prices are baked in at checkout — catalog changes never alter past orders.

**curl**
```bash
curl -s http://localhost:8080/orders/ord_a1b2c3d4e5f6789012345
```

**Request body:** none

**Response `200`**
```json
{
  "orderId": "ord_a1b2c3d4e5f6789012345",
  "cartId": "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
  "placedAt": "2026-09-20T10:00:00Z",
  "lines": [
    {
      "productId": "SKU-COFFEE",
      "productName": "Single-origin coffee 250g",
      "unitPriceCents": 1499,
      "quantity": 2,
      "lineTotalCents": 2998
    },
    {
      "productId": "SKU-MUG",
      "productName": "Ceramic mug",
      "unitPriceCents": 999,
      "quantity": 1,
      "lineTotalCents": 999
    }
  ],
  "grossCents": 3997,
  "discountCents": 0,
  "netCents": 3997,
  "couponCode": null,
  "couponPercent": null
}
```

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `order_not_found` | 404 | No order with that ID |

---

### POST /admin/coupons/generate

Mints one coupon for the smallest order milestone that has been reached but not yet rewarded.

**How milestones work:** Default config is `n=5, xPercent=10`. Every 5 successful orders earns one 10%-off coupon slot. This endpoint generates one coupon per call. If 15 orders have been placed and no coupons generated yet, call it 3 times to catch up (milestones 5, 10, 15).

**curl**
```bash
curl -s -X POST http://localhost:8080/admin/coupons/generate
```

**Request body:** none

**Response `200`**
```json
{
  "code": "SAVE-K7X2M9RQ",
  "percent": 10,
  "milestone": 5,
  "status": "AVAILABLE",
  "createdAt": "2026-09-20T10:00:00Z"
}
```

> Save the `code` — use it in the checkout body as `couponCode`.

**Errors**

| Code | HTTP | Condition |
|---|---|---|
| `no_milestone_available` | 409 | No unrewarded milestone reached yet |

**Error response example**
```json
{
  "code": "no_milestone_available",
  "message": "no unrewarded milestone is eligible",
  "details": {
    "orderCount": 3,
    "n": 5,
    "eligibleMilestones": 0,
    "couponsAlreadyGenerated": 0
  }
}
```

---

### GET /admin/report

Returns a full reconciliation of all orders, revenue, and coupon activity. Read-only and idempotent — repeated calls return the same result.

**curl**
```bash
curl -s http://localhost:8080/admin/report
```

**Request body:** none

**Response `200`**
```json
{
  "totalOrders": 6,
  "grossRevenueCents": 37488,
  "totalDiscountsCents": 1290,
  "netRevenueCents": 36198,
  "salesByProduct": [
    { "productId": "SKU-COFFEE",  "name": "Single-origin coffee 250g", "unitsSold": 5, "grossCents": 7495  },
    { "productId": "SKU-GRINDER", "name": "Manual burr grinder",       "unitsSold": 1, "grossCents": 12900 },
    { "productId": "SKU-MUG",     "name": "Ceramic mug",               "unitsSold": 3, "grossCents": 2997  }
  ],
  "couponsGenerated": 1,
  "couponsAvailable": 0,
  "couponsRedeemed": 1,
  "coupons": [
    {
      "code": "SAVE-K7X2M9RQ",
      "percent": 10,
      "milestone": 5,
      "status": "REDEEMED",
      "createdAt": "2026-09-20T10:00:00Z",
      "redeemedAt": "2026-09-20T10:05:00Z",
      "redeemedOnOrderId": "ord_b2c3d4e5f6789012345ab"
    }
  ]
}
```

| Field | Description |
|---|---|
| `totalOrders` | Total successfully placed orders |
| `grossRevenueCents` | Sum of all order `grossCents` |
| `totalDiscountsCents` | Sum of all order `discountCents` |
| `netRevenueCents` | Sum of all order `netCents` |
| `salesByProduct` | Per-SKU breakdown: units sold and gross revenue |
| `couponsGenerated` | Total coupons ever minted |
| `couponsAvailable` | Coupons not yet redeemed |
| `couponsRedeemed` | Coupons used on an order |
| `coupons` | Full list with redemption detail |

---

## Error Code Reference

| Code | HTTP | Raised by |
|---|---|---|
| `validation_error` | 400 | `POST /items`, `PUT /items/{id}` — missing field or constraint |
| `bad_request` | 400 | Any — malformed request body |
| `unknown_product` | 400 | `POST /items` — `productId` not in catalog |
| `invalid_quantity` | 400 | `POST /items`, `PUT /items/{id}` — `quantity` < 1 |
| `invalid_coupon` | 400 | Checkout — `couponCode` not found |
| `empty_cart` | 400 | Checkout — cart has no lines |
| `cart_not_found` | 404 | Any cart op — no cart with that ID |
| `item_not_in_cart` | 404 | `PUT /items/{id}`, `DELETE /items/{id}` — product not on any line |
| `order_not_found` | 404 | `GET /orders/{id}` — no order with that ID |
| `cart_not_open` | 409 | Cart ops, checkout — cart is `CHECKED_OUT` or `ABANDONED` |
| `insufficient_inventory` | 409 | Checkout — not enough stock; `details.shortfalls` shows each gap |
| `coupon_not_available` | 409 | Checkout — coupon already redeemed |
| `product_missing` | 409 | Checkout — a cart item's product was deleted |
| `idempotency_key_reused` | 409 | Checkout — same key, different body |
| `no_milestone_available` | 409 | `POST /admin/coupons/generate` — no unrewarded milestone |

---

## Configuration

| Property | Default | Description |
|---|---|---|
| `rewards.n` | `5` | Orders per coupon milestone |
| `rewards.xPercent` | `10` | Discount % on each generated coupon |
| `server.port` | `8080` | HTTP port |
| `spring.h2.console.path` | `/h2` | H2 web console (dev only) |

---

## Running Locally

**Requirements:** Java 21 · Maven 3.9+

```bash
# Start in background (survives laptop sleep on macOS)
./run.sh

# Start in foreground
mvn spring-boot:run

# Run tests
mvn test

# Stop background server
kill "$(cat ./server.pid)"
```

- Data: `./data/checkout.mv.db`
- Logs: `./server.log`
- H2 console: `http://localhost:8080/h2`
