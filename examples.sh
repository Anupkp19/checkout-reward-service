#!/usr/bin/env bash
# End-to-end walk of every endpoint. Runs against a live server on :8080.
# Prints request → response for each step. Requires `curl` and `python3`.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
pp() { python3 -m json.tool; }
hr() { echo; echo "── $1 ──"; }

hr "list products"
curl -s "$BASE/products" | pp | head -40

hr "create cart"
CART=$(curl -s -X POST "$BASE/carts" | tee /tmp/cart.json | python3 -c 'import sys,json;print(json.load(sys.stdin)["cartId"])')
echo "cartId=$CART"

hr "add SKU-COFFEE x2"
curl -s -X POST "$BASE/carts/$CART/items" \
    -H 'content-type: application/json' \
    -d '{"productId":"SKU-COFFEE","quantity":2}' | pp

hr "add SKU-MUG x1"
curl -s -X POST "$BASE/carts/$CART/items" \
    -H 'content-type: application/json' \
    -d '{"productId":"SKU-MUG","quantity":1}' | pp

hr "update SKU-COFFEE to quantity=3"
curl -s -X PUT "$BASE/carts/$CART/items/SKU-COFFEE" \
    -H 'content-type: application/json' \
    -d '{"quantity":3}' | pp

hr "remove SKU-MUG"
curl -s -X DELETE "$BASE/carts/$CART/items/SKU-MUG" | pp

hr "view cart"
curl -s "$BASE/carts/$CART" | pp

hr "checkout (Idempotency-Key: demo-1)"
ORDER=$(curl -s -X POST "$BASE/carts/$CART/checkout" \
    -H 'content-type: application/json' \
    -H 'Idempotency-Key: demo-1' \
    -d '{}' | tee /dev/stderr | python3 -c 'import sys,json;print(json.load(sys.stdin)["orderId"])')
echo
echo "orderId=$ORDER"

hr "retry same key → same order replayed"
curl -s -X POST "$BASE/carts/$CART/checkout" \
    -H 'content-type: application/json' \
    -H 'Idempotency-Key: demo-1' \
    -d '{}' | pp | head -12

hr "GET the order"
curl -s "$BASE/orders/$ORDER" | pp | head -20

hr "attempt admin coupon generation (may 409 if n=5 not reached yet)"
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/admin/coupons/generate" | head -20 || true

hr "admin report"
curl -s "$BASE/admin/report" | pp

hr "negative cases"
echo "-- unknown product:"
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/carts/$CART/items" \
    -H 'content-type: application/json' -d '{"productId":"SKU-NOPE","quantity":1}'
echo
echo "-- second checkout on closed cart (returns same order):"
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/carts/$CART/checkout" \
    -H 'content-type: application/json' -H 'Idempotency-Key: demo-2' -d '{}' | head -3
echo
echo "-- reuse Idempotency-Key on a different cart (409):"
NEW=$(curl -s -X POST "$BASE/carts" | python3 -c 'import sys,json;print(json.load(sys.stdin)["cartId"])')
curl -s -X POST "$BASE/carts/$NEW/items" -H 'content-type: application/json' \
    -d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null
curl -s -w "\nHTTP %{http_code}\n" -X POST "$BASE/carts/$NEW/checkout" \
    -H 'content-type: application/json' \
    -H 'Idempotency-Key: demo-1' \
    -d '{}'
