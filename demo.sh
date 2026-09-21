#!/usr/bin/env bash
# ============================================================
#  Checkout & Rewards Service — Interactive Demo
#  Run:  chmod +x demo.sh && ./demo.sh
#  Requires: curl, jq  (brew install jq if missing)
# ============================================================
set -euo pipefail
BASE="${BASE:-http://localhost:8080}"

#  Colors 
BOLD='\033[1m'
CYAN='\033[1;36m'
GREEN='\033[1;32m'
YELLOW='\033[1;33m'
RED='\033[1;31m'
GRAY='\033[0;90m'
RESET='\033[0m'

#  Helpers 
step() {
  echo
  echo -e "${CYAN}${RESET}"
  echo -e "${BOLD}  STEP $1: $2${RESET}"
  echo -e "${CYAN}${RESET}"
}

info()    { echo -e "  ${GRAY}$*${RESET}"; }
success() { echo -e "  ${GREEN}  $*${RESET}"; }
warn()    { echo -e "  ${YELLOW}  $*${RESET}"; }
fail()    { echo -e "  ${RED}  $*${RESET}"; }
show_cmd(){ echo -e "  ${GRAY}$*${RESET}"; }

pause() {
  echo
  echo -e "  ${YELLOW}Press ENTER to continue...${RESET}"
  read -r
}

call() {
  # call METHOD PATH [extra curl args...]
  local method="$1"; local path="$2"; shift 2
  local url="$BASE$path"
  show_cmd "curl -s -X $method \"$url\" $*"
  echo
  HTTP=$(curl -s -o /tmp/demo_body.json -w "%{http_code}" -X "$method" "$url" "$@")
  cat /tmp/demo_body.json | jq . 2>/dev/null || cat /tmp/demo_body.json
  echo
  echo -e "  ${BOLD}HTTP $HTTP${RESET}"
}

check_server() {
  echo -e "${BOLD}Checking server on $BASE ...${RESET}"
  if ! curl -sf "$BASE/products" > /dev/null 2>&1; then
    echo
    fail "Server is not running at $BASE"
    echo
    echo "  Start it first:"
    echo -e "  ${YELLOW}  ./run.sh${RESET}        ← background (survives sleep)"
    echo -e "  ${YELLOW}  mvn spring-boot:run${RESET} ← foreground"
    echo
    exit 1
  fi
  success "Server is up"
}

# 
#  START
# 
clear
echo -e "${CYAN}${BOLD}"
echo "  "
echo "      Checkout & Rewards Service — Demo         "
echo "  "
echo -e "${RESET}"
echo "  This demo walks through the full lifecycle:"
echo "  products → cart → checkout → orders → coupons → admin report"
echo
check_server
pause

# 
step 1 "Browse the product catalog"
# 
info "GET /products — returns all products with live price and inventory."
info "Money is always in cents (1499 = \$14.99)."
echo
call GET /products
success "6 products seeded on first boot. SKU-GRINDER has only 3 units — our concurrency test fixture."
pause

# 
step 2 "Create a shopping cart"
# 
info "POST /carts — creates an empty cart with status OPEN."
echo
call POST /carts
CART_ID=$(cat /tmp/demo_body.json | jq -r '.cartId')
success "Cart created: $CART_ID"
pause

# 
step 3 "Add items to the cart"
# 
info "POST /carts/{id}/items — add SKU-COFFEE x2."
info "Inventory is NOT reserved here; it's checked at checkout."
echo
call POST "/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-COFFEE","quantity":2}'
success "Coffee added."
pause

info "Add SKU-GRINDER x1 (limited: only 3 in stock)."
echo
call POST "/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-GRINDER","quantity":1}'
success "Grinder added."
pause

info "Add the same product again — quantities are summed (no duplicate lines)."
echo
call POST "/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-COFFEE","quantity":1}'
success "Coffee is now quantity 3 on one line."
pause

# 
step 4 "Update and remove items"
# 
info "PUT /carts/{id}/items/{productId} — set coffee back to 2."
echo
call PUT "/carts/$CART_ID/items/SKU-COFFEE" \
  -H 'Content-Type: application/json' \
  -d '{"quantity":2}'
success "Coffee quantity replaced."
pause

info "Add SKU-MUG then remove it — to show DELETE."
call POST "/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-MUG","quantity":1}' > /dev/null 2>&1 || true
echo
call DELETE "/carts/$CART_ID/items/SKU-MUG"
success "Mug removed."
pause

# 
step 5 "View the cart (with live prices and totals)"
# 
info "GET /carts/{id} — shows current price, available inventory, and subtotal."
info "Cart carries productId + quantity only; prices are resolved live."
echo
call GET "/carts/$CART_ID"
success "subtotalCents shown — coffee (\$14.99 x2) + grinder (\$129.00 x1) = \$158.98"
pause

# 
step 6 "Checkout — place the order"
# 
info "POST /carts/{id}/checkout with Idempotency-Key header."
info "The key makes this retry-safe: same key + same body → same order, no duplicate."
echo
call POST "/carts/$CART_ID/checkout" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-checkout-1' \
  -d '{}'
ORDER_ID=$(cat /tmp/demo_body.json | jq -r '.orderId // empty')
if [[ -z "$ORDER_ID" ]]; then
  fail "Checkout failed — see response above."
  pause
  exit 1
fi
success "Order placed: $ORDER_ID"
info "Inventory decremented. Cart status is now CHECKED_OUT. Coupon is untouched (none used)."
pause

# 
step 7 "Retry the same checkout — idempotency replay"
# 
info "Send the exact same request again with the same Idempotency-Key."
info "Expected: HTTP 200 with the SAME orderId — no second order created."
echo
call POST "/carts/$CART_ID/checkout" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-checkout-1' \
  -d '{}'
REPLAYED=$(cat /tmp/demo_body.json | jq -r '.orderId // empty')
if [[ "$REPLAYED" == "$ORDER_ID" ]]; then
  success "Replay confirmed — same orderId: $ORDER_ID. No duplicate charge."
else
  warn "Unexpected orderId in replay: $REPLAYED"
fi
pause

# 
step 8 "Fetch the order directly"
# 
info "GET /orders/{id} — immutable snapshot. Product name and price are baked in."
info "Renaming or deleting a product later won't change this."
echo
call GET "/orders/$ORDER_ID"
success "Order snapshot: gross, discount, net, and line-level detail."
pause

# 
step 9 "Error cases — the service rejects bad inputs clearly"
# 

info "9a. Try to add an unknown product to a new cart."
echo
TMP_CART=$(curl -s -X POST "$BASE/carts" | jq -r '.cartId')
call POST "/carts/$TMP_CART/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-DOESNT-EXIST","quantity":1}'
warn "HTTP 400 unknown_product — stable error code for clients to switch on."
pause

info "9b. Try to modify the already-checked-out cart."
echo
call POST "/carts/$CART_ID/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-COFFEE","quantity":1}'
warn "HTTP 409 cart_not_open — cart status is CHECKED_OUT."
pause

info "9c. Reuse the same Idempotency-Key with a DIFFERENT body → 409."
echo
curl -s -X POST "$BASE/carts/$TMP_CART/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null
call POST "/carts/$TMP_CART/checkout" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-checkout-1' \
  -d '{"couponCode":"DOESNT-MATTER"}'
warn "HTTP 409 idempotency_key_reused — key already used with a different body."
pause

info "9d. Try to request a coupon before the milestone (5 orders) is reached."
echo
call POST "/admin/coupons/generate"
warn "HTTP 409 no_milestone_available — not enough orders yet."
pause

# 
step 10 "Place 4 more orders to hit the milestone (need 5 total)"
# 
info "Rewards config: every n=5 orders → one 10%-off coupon."
info "We already placed 1 order. Placing 4 more now..."
echo
for i in 2 3 4 5; do
  C=$(curl -s -X POST "$BASE/carts" | jq -r '.cartId')
  curl -s -X POST "$BASE/carts/$C/items" \
    -H 'Content-Type: application/json' \
    -d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null
  curl -s -X POST "$BASE/carts/$C/checkout" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: bulk-order-$i" \
    -d '{}' > /dev/null
  echo -e "  ${GREEN}  Order $i/5 placed${RESET}"
done
success "5 orders placed total — milestone reached!"
pause

# 
step 11 "Generate the milestone coupon"
# 
info "POST /admin/coupons/generate — mints one coupon for milestone 5."
info "UNIQUE(milestone) in the DB prevents concurrent admins from double-generating."
echo
call POST "/admin/coupons/generate"
COUPON_CODE=$(cat /tmp/demo_body.json | jq -r '.code // empty')
if [[ -z "$COUPON_CODE" ]]; then
  fail "Coupon generation failed — see above."
  pause
  exit 1
fi
success "Coupon generated: $COUPON_CODE (10% off, milestone 5)"
pause

# 
step 12 "Checkout WITH the coupon"
# 
info "Create a new cart and checkout using the coupon code."
echo
COUPON_CART=$(curl -s -X POST "$BASE/carts" | jq -r '.cartId')
curl -s -X POST "$BASE/carts/$COUPON_CART/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-GRINDER","quantity":1}' > /dev/null
info "Cart has 1x SKU-GRINDER (\$129.00). Applying 10% coupon = \$12.90 off."
echo
call POST "/carts/$COUPON_CART/checkout" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-coupon-checkout' \
  -d "{\"couponCode\":\"$COUPON_CODE\"}"
DISC=$(cat /tmp/demo_body.json | jq -r '.discountCents // 0')
NET=$(cat /tmp/demo_body.json | jq -r '.netCents // 0')
success "Discount: ${DISC} cents  |  Net: ${NET} cents"
pause

# 
step 13 "Try to reuse the coupon — it's single-use"
# 
info "Creating another cart and trying the same coupon code."
echo
REUSE_CART=$(curl -s -X POST "$BASE/carts" | jq -r '.cartId')
curl -s -X POST "$BASE/carts/$REUSE_CART/items" \
  -H 'Content-Type: application/json' \
  -d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null
call POST "/carts/$REUSE_CART/checkout" \
  -H 'Content-Type: application/json' \
  -H 'Idempotency-Key: demo-reuse-coupon' \
  -d "{\"couponCode\":\"$COUPON_CODE\"}"
warn "HTTP 409 coupon_not_available — status is REDEEMED."
pause

# 
step 14 "Admin report — full reconciliation"
# 
info "GET /admin/report — read-only aggregation of all orders, revenue, and coupon accounting."
info "Idempotent: calling it 10 times returns the same result."
echo
call GET "/admin/report"
success "Shows: totalOrders, grossRevenueCents, totalDiscountsCents, netRevenueCents,"
success "salesByProduct (units sold, revenue per SKU), coupons generated/available/redeemed."
pause

# 
echo
echo -e "${GREEN}${BOLD}"
echo "  "
echo "             Demo Complete!                     "
echo "  "
echo -e "${RESET}"
echo "  What we covered:"
echo "    1  Browse catalog             GET /products"
echo "    2  Create cart                POST /carts"
echo "    3  Add items (qty summing)    POST /carts/{id}/items"
echo "    4  Update / remove items      PUT  & DELETE"
echo "    5  View cart (live totals)    GET /carts/{id}"
echo "    6  Checkout + Idempotency-Key POST /carts/{id}/checkout"
echo "    7  Retry replay (same order)  same key → same response"
echo "    8  Fetch order snapshot       GET /orders/{id}"
echo "    9  Error cases                400/404/409 with stable codes"
echo "   10  Bulk orders to milestone   5 orders → coupon slot"
echo "   11  Generate coupon            POST /admin/coupons/generate"
echo "   12  Checkout with coupon       10% discount applied"
echo "   13  Coupon reuse rejected      409 coupon_not_available"
echo "   14  Admin report               GET /admin/report"
echo
echo -e "  H2 console: ${CYAN}http://localhost:8080/h2${RESET}  (browse the DB live)"
echo
