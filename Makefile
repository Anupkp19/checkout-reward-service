.DEFAULT_GOAL := help
BASE         ?= http://localhost:8080
IDEM_KEY     ?= demo-key-$(shell date +%s)

#  colours 
BOLD  := \033[1m
CYAN  := \033[1;36m
GREEN := \033[1;32m
RESET := \033[0m

#  helpers 
define header
	@echo ""
	@printf "$(CYAN)$(BOLD)$(1)$(RESET)\n"
	@echo ""
endef

# Pretty-print JSON — use jq if available, else python3
PP := $(shell command -v jq > /dev/null 2>&1 && echo "/usr/bin/jq ." || echo "python3 -m json.tool")

.PHONY: help start stop build test demo \
        products \
        cart-create cart-view \
        cart-add-coffee cart-add-grinder cart-add-mug \
        cart-update-coffee cart-remove-mug \
        checkout checkout-retry \
        order-get \
        error-bad-product error-closed-cart error-no-inventory \
        milestone-orders coupon-generate coupon-checkout coupon-reuse \
        report \
        h2

# 
# HELP
# 
help:
	@echo ""
	@printf "$(BOLD)Checkout & Rewards Service — Makefile$(RESET)\n"
	@echo ""
	@printf "$(CYAN)Server$(RESET)\n"
	@echo "  make start              Build jar and start server in background"
	@echo "  make stop               Kill the background server"
	@echo "  make build              Build jar only (skip tests)"
	@echo "  make test               Run full test suite"
	@echo ""
	@printf "$(CYAN)Full demo (all steps in order)$(RESET)\n"
	@echo "  make demo               Run every step end-to-end"
	@echo ""
	@printf "$(CYAN)Individual demo steps$(RESET)\n"
	@echo "  make products           Step  1 — list catalog"
	@echo "  make cart-create        Step  2 — create a cart  (saves CART_ID to /tmp/cart_id)"
	@echo "  make cart-add-coffee    Step  3 — add 2x SKU-COFFEE"
	@echo "  make cart-add-grinder   Step  3 — add 1x SKU-GRINDER"
	@echo "  make cart-add-mug       Step  3 — add 1x SKU-MUG"
	@echo "  make cart-update-coffee Step  4 — set SKU-COFFEE quantity to 3"
	@echo "  make cart-remove-mug    Step  5 — remove SKU-MUG"
	@echo "  make cart-view          Step  6 — view cart with live totals"
	@echo "  make checkout           Step  7 — place order  (saves ORDER_ID to /tmp/order_id)"
	@echo "  make checkout-retry     Step  8 — retry same key → same order replayed"
	@echo "  make order-get          Step  9 — fetch immutable order snapshot"
	@echo "  make error-bad-product  Step 10a — 400 unknown_product"
	@echo "  make error-closed-cart  Step 10b — 409 cart_not_open"
	@echo "  make error-no-inventory Step 10c — 409 insufficient_inventory"
	@echo "  make milestone-orders   Step 11 — place 4 more orders to reach milestone 5"
	@echo "  make coupon-generate    Step 12 — mint coupon for milestone 5"
	@echo "  make coupon-checkout    Step 13 — checkout with 10% coupon applied"
	@echo "  make coupon-reuse       Step 14 — 409 coupon already redeemed"
	@echo "  make report             Step 15 — admin reconciliation report"
	@echo ""
	@printf "$(CYAN)Utilities$(RESET)\n"
	@echo "  make h2                 Open H2 database console in browser"
	@echo ""

# 
# SERVER LIFECYCLE
# 
start:
	$(call header,Starting server)
	./run.sh

stop:
	$(call header,Stopping server)
	@if [ -f server.pid ] && kill -0 $$(cat server.pid) 2>/dev/null; then \
		kill $$(cat server.pid) && rm -f server.pid && echo "Server stopped."; \
	else \
		echo "Server is not running."; \
	fi

build:
	$(call header,Building jar)
	mvn -q -DskipTests package

test:
	$(call header,Running tests)
	mvn test

# 
# FULL DEMO
# 
demo: \
	products \
	cart-create cart-add-coffee cart-add-grinder cart-add-mug \
	cart-update-coffee cart-remove-mug cart-view \
	checkout checkout-retry order-get \
	error-bad-product error-closed-cart error-no-inventory \
	milestone-orders coupon-generate coupon-checkout coupon-reuse \
	report
	@echo ""
	@printf "$(GREEN)$(BOLD)  Demo complete!$(RESET)\n"
	@echo ""
	@echo "  Covered: products → cart → checkout → idempotency → errors"
	@echo "           → milestone → coupon generation → coupon redemption"
	@echo "           → coupon single-use enforcement → admin report"
	@echo ""
	@printf "  H2 console: $(CYAN)$(BASE)/h2$(RESET)\n"
	@echo ""

# 
# STEP 1 — PRODUCTS
# 
products:
	$(call header,Step 1 — List product catalog)
	@echo "  GET $(BASE)/products"
	@echo ""
	@curl -sf $(BASE)/products | $(PP)

# 
# STEP 2 — CREATE CART
# 
cart-create:
	$(call header,Step 2 — Create cart)
	@echo "  POST $(BASE)/carts"
	@echo ""
	@RESP=$$(curl -sf -X POST $(BASE)/carts); \
	echo "$$RESP" | $(PP); \
	echo "$$RESP" | python3 -c \
		"import sys,json; print(json.load(sys.stdin)['cartId'])" > /tmp/cart_id; \
	printf "\n  \033[1;32mCart ID: $$(cat /tmp/cart_id)\033[0m\n"

# 
# STEP 3 — ADD ITEMS
# 
cart-add-coffee: _require_cart
	$(call header,Step 3a — Add SKU-COFFEE x2)
	@echo "  POST $(BASE)/carts/$$(cat /tmp/cart_id)/items"
	@echo '  Body: {"productId":"SKU-COFFEE","quantity":2}'
	@echo ""
	@curl -sf -X POST $(BASE)/carts/$$(cat /tmp/cart_id)/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-COFFEE","quantity":2}' | $(PP)

cart-add-grinder: _require_cart
	$(call header,Step 3b — Add SKU-GRINDER x1  [only 3 in stock])
	@echo "  POST $(BASE)/carts/$$(cat /tmp/cart_id)/items"
	@echo '  Body: {"productId":"SKU-GRINDER","quantity":1}'
	@echo ""
	@curl -sf -X POST $(BASE)/carts/$$(cat /tmp/cart_id)/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-GRINDER","quantity":1}' | $(PP)

cart-add-mug: _require_cart
	$(call header,Step 3c — Add SKU-MUG x1  [will remove in step 5])
	@echo "  POST $(BASE)/carts/$$(cat /tmp/cart_id)/items"
	@echo '  Body: {"productId":"SKU-MUG","quantity":1}'
	@echo ""
	@curl -sf -X POST $(BASE)/carts/$$(cat /tmp/cart_id)/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-MUG","quantity":1}' | $(PP)

# 
# STEP 4 — UPDATE ITEM
# 
cart-update-coffee: _require_cart
	$(call header,Step 4 — Update SKU-COFFEE quantity to 3)
	@echo "  PUT $(BASE)/carts/$$(cat /tmp/cart_id)/items/SKU-COFFEE"
	@echo '  Body: {"quantity":3}'
	@echo ""
	@curl -sf -X PUT $(BASE)/carts/$$(cat /tmp/cart_id)/items/SKU-COFFEE \
		-H 'Content-Type: application/json' \
		-d '{"quantity":3}' | $(PP)

# 
# STEP 5 — REMOVE ITEM
# 
cart-remove-mug: _require_cart
	$(call header,Step 5 — Remove SKU-MUG)
	@echo "  DELETE $(BASE)/carts/$$(cat /tmp/cart_id)/items/SKU-MUG"
	@echo ""
	@curl -sf -X DELETE $(BASE)/carts/$$(cat /tmp/cart_id)/items/SKU-MUG | $(PP)

# 
# STEP 6 — VIEW CART
# 
cart-view: _require_cart
	$(call header,Step 6 — View cart with live prices and totals)
	@echo "  GET $(BASE)/carts/$$(cat /tmp/cart_id)"
	@echo ""
	@curl -sf $(BASE)/carts/$$(cat /tmp/cart_id) | $(PP)

# 
# STEP 7 — CHECKOUT
# 
checkout: _require_cart
	$(call header,Step 7 — Checkout with Idempotency-Key)
	@echo "  POST $(BASE)/carts/$$(cat /tmp/cart_id)/checkout"
	@echo '  Body: {}'
	@IKEY="demo-checkout-$$(cat /tmp/cart_id)"; \
	echo "  Idempotency-Key: $$IKEY"; \
	echo ""; \
	curl -sf -X POST $(BASE)/carts/$$(cat /tmp/cart_id)/checkout \
		-H 'Content-Type: application/json' \
		-H "Idempotency-Key: $$IKEY" \
		-d '{}' > /tmp/order_resp.json; \
	$(PP) < /tmp/order_resp.json; \
	python3 -c \
		"import sys,json; d=json.load(open('/tmp/order_resp.json')); print(d.get('orderId',''))" \
		> /tmp/order_id; \
	printf "\n  \033[1;32mOrder ID: $$(cat /tmp/order_id)\033[0m\n"

# 
# STEP 8 — RETRY (idempotency replay)
# 
checkout-retry: _require_cart
	$(call header,Step 8 — Retry same Idempotency-Key → same order replayed)
	@echo "  Same request. Same key. Expected: same orderId, no duplicate order."
	@echo ""
	@IKEY="demo-checkout-$$(cat /tmp/cart_id)"; \
	curl -sf -X POST $(BASE)/carts/$$(cat /tmp/cart_id)/checkout \
		-H 'Content-Type: application/json' \
		-H "Idempotency-Key: $$IKEY" \
		-d '{}' | $(PP)

# 
# STEP 9 — FETCH ORDER
# 
order-get: _require_order
	$(call header,Step 9 — Fetch immutable order snapshot)
	@echo "  GET $(BASE)/orders/$$(cat /tmp/order_id)"
	@echo ""
	@curl -sf $(BASE)/orders/$$(cat /tmp/order_id) | $(PP)

# 
# STEP 10 — ERROR CASES
# 
error-bad-product:
	$(call header,Step 10a — Error: unknown product  [expect 400])
	@echo "  POST /carts/{new}/items  productId=SKU-DOESNT-EXIST"
	@echo ""
	@ECART=$$(curl -sf -X POST $(BASE)/carts | python3 -c \
		"import sys,json; print(json.load(sys.stdin)['cartId'])"); \
	curl -s -w "\n  HTTP %{http_code}\n" \
		-X POST $(BASE)/carts/$$ECART/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-DOESNT-EXIST","quantity":1}' | $(PP) || true

error-closed-cart: _require_cart
	$(call header,Step 10b — Error: modify checked-out cart  [expect 409])
	@echo "  POST /carts/{checked_out_cart}/items"
	@echo ""
	@curl -s -w "\n  HTTP %{http_code}\n" \
		-X POST $(BASE)/carts/$$(cat /tmp/cart_id)/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-COFFEE","quantity":1}' | $(PP) || true

error-no-inventory:
	$(call header,Step 10c — Error: insufficient inventory  [expect 409])
	@echo "  Request 99x SKU-GRINDER — only 3 in stock."
	@echo ""
	@ECART=$$(curl -sf -X POST $(BASE)/carts | python3 -c \
		"import sys,json; print(json.load(sys.stdin)['cartId'])"); \
	curl -sf -X POST $(BASE)/carts/$$ECART/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-GRINDER","quantity":99}' > /dev/null; \
	curl -s -w "\n  HTTP %{http_code}\n" \
		-X POST $(BASE)/carts/$$ECART/checkout \
		-H 'Content-Type: application/json' \
		-H 'Idempotency-Key: inventory-error-test' \
		-d '{}' | $(PP) || true

# 
# STEP 11 — PLACE 4 MORE ORDERS (reach milestone 5)
# 
milestone-orders:
	$(call header,Step 11 — Place 4 more orders to hit milestone n=5)
	@echo "  Already have 1 order. Placing 4 more with SKU-COFFEE x1 each."
	@echo ""
	@for i in 2 3 4 5; do \
		C=$$(curl -sf -X POST $(BASE)/carts | python3 -c \
			"import sys,json; print(json.load(sys.stdin)['cartId'])"); \
		curl -sf -X POST $(BASE)/carts/$$C/items \
			-H 'Content-Type: application/json' \
			-d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null; \
		curl -sf -X POST $(BASE)/carts/$$C/checkout \
			-H 'Content-Type: application/json' \
			-H "Idempotency-Key: milestone-order-$$i" \
			-d '{}' > /dev/null; \
		printf "  $(GREEN)$(RESET)  Order $$i/5 placed\n"; \
	done
	@echo ""
	@printf "  $(GREEN)Milestone reached! 5 orders total.$(RESET)\n"

# 
# STEP 12 — GENERATE COUPON
# 
coupon-generate:
	$(call header,Step 12 — Generate milestone coupon)
	@echo "  POST $(BASE)/admin/coupons/generate"
	@echo ""
	@RESP=$$(curl -sf -X POST $(BASE)/admin/coupons/generate); \
	echo "$$RESP" | $(PP); \
	echo "$$RESP" | python3 -c \
		"import sys,json; print(json.load(sys.stdin).get('code',''))" > /tmp/coupon_code; \
	printf "\n  \033[1;32mCoupon: $$(cat /tmp/coupon_code)\033[0m\n"

# 
# STEP 13 — CHECKOUT WITH COUPON
# 
coupon-checkout:
	$(call header,Step 13 — Checkout using coupon  [10% off SKU-GRINDER = \$$12.90 discount])
	@if [ ! -f /tmp/coupon_code ] || [ -z "$$(cat /tmp/coupon_code)" ]; then \
		echo "  Run 'make coupon-generate' first."; exit 1; fi
	@echo "  POST $(BASE)/carts/{new_cart}/checkout"
	@echo "  Body: { \"couponCode\": \"$$(cat /tmp/coupon_code)\" }"
	@echo ""
	@CCART=$$(curl -sf -X POST $(BASE)/carts | python3 -c \
		"import sys,json; print(json.load(sys.stdin)['cartId'])"); \
	curl -sf -X POST $(BASE)/carts/$$CCART/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-GRINDER","quantity":1}' > /dev/null; \
	echo $$CCART > /tmp/coupon_cart_id; \
	curl -sf -X POST $(BASE)/carts/$$CCART/checkout \
		-H 'Content-Type: application/json' \
		-H 'Idempotency-Key: coupon-demo-checkout' \
		-d "{\"couponCode\":\"$$(cat /tmp/coupon_code)\"}" | $(PP)

# 
# STEP 14 — REUSE COUPON (expect 409)
# 
coupon-reuse:
	$(call header,Step 14 — Try to reuse the coupon  [expect 409])
	@if [ ! -f /tmp/coupon_code ] || [ -z "$$(cat /tmp/coupon_code)" ]; then \
		echo "  Run 'make coupon-generate' first."; exit 1; fi
	@echo "  Same coupon code on a new cart."
	@echo ""
	@RCART=$$(curl -sf -X POST $(BASE)/carts | python3 -c \
		"import sys,json; print(json.load(sys.stdin)['cartId'])"); \
	curl -sf -X POST $(BASE)/carts/$$RCART/items \
		-H 'Content-Type: application/json' \
		-d '{"productId":"SKU-COFFEE","quantity":1}' > /dev/null; \
	curl -s -w "\n  HTTP %{http_code}\n" \
		-X POST $(BASE)/carts/$$RCART/checkout \
		-H 'Content-Type: application/json' \
		-H 'Idempotency-Key: coupon-reuse-test' \
		-d "{\"couponCode\":\"$$(cat /tmp/coupon_code)\"}" | $(PP) || true

# 
# STEP 15 — ADMIN REPORT
# 
report:
	$(call header,Step 15 — Admin reconciliation report)
	@echo "  GET $(BASE)/admin/report"
	@echo ""
	@curl -sf $(BASE)/admin/report | $(PP)

# 
# UTILITIES
# 
h2:
	$(call header,Opening H2 console)
	@open $(BASE)/h2 || xdg-open $(BASE)/h2 || echo "Open $(BASE)/h2 in your browser."

#  internal guards 
_require_cart:
	@if [ ! -f /tmp/cart_id ] || [ -z "$$(cat /tmp/cart_id)" ]; then \
		echo "  No cart found. Run 'make cart-create' first."; exit 1; fi

_require_order:
	@if [ ! -f /tmp/order_id ] || [ -z "$$(cat /tmp/order_id)" ]; then \
		echo "  No order found. Run 'make checkout' first."; exit 1; fi
