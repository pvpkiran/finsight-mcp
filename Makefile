# ─────────────────────────────────────────────────────────────
#  FinSight MCP — developer convenience commands
#
#  Profiles:
#    local  — real infra, mock adapters, localhost JWK
#             Use for: make mcp-test-* (no ngrok needed)
#
#    prod   — real infra, real adapters (Stripe+pgvector+OBP), ngrok JWK
#             Use for: Claude Desktop, Stripe sandbox testing
#             Requires: ngrok tunnels, FINSIGHT_PUBLIC_URL, KEYCLOAK_PUBLIC_URL,
#                       STRIPE_API_KEY, OBP_CONSUMER_KEY, OBP_USERNAME, OBP_PASSWORD
#
#  Usage: make <target>
# ─────────────────────────────────────────────────────────────

.PHONY: infra infra-down infra-status infra-logs infra-clean \
        token-full token-payments token-fraud decode-token keycloak-ready \
        app app-prod \
        mcp-list-tools mcp-test-all \
        mcp-test-get-txn mcp-test-decline mcp-test-reconcile \
        mcp-test-fraud mcp-test-fraud-explain mcp-test-fraud-score mcp-test-velocity \
        mcp-test-banks mcp-test-route mcp-test-account mcp-test-consent mcp-test-all-accounts \
        stripe-create-payment stripe-create-declined stripe-test-get stripe-test-decline stripe-test-full stripe-fraud-full\
        help

STRIPE_API_KEY ?= $(shell echo $$STRIPE_API_KEY)
MCP_URL = http://localhost:8080/mcp
MCP_INIT = {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}

# Default IDs for local profile
# Override for prod: make mcp-test-account ACCOUNT_ID=test-bank/fgarcia_account1
ACCOUNT_ID     ?= acc-de-001
REQUISITION_ID ?= req-demo-001

# ── Infra ─────────────────────────────────────────────────────

## Start all infrastructure (PostgreSQL, Redis, Kafka, Keycloak)
infra:
	@echo "🚀 Starting FinSight infrastructure..."
	@chmod +x finsight-infra/kafka/create-topics.sh
	@docker-compose up -d postgres redis
	@echo "⏳ Waiting for PostgreSQL..."
	@until docker-compose exec -T postgres pg_isready -U finsight -d finsight > /dev/null 2>&1; do sleep 2; done
	@echo "✓  PostgreSQL ready"
	@docker-compose exec -T postgres psql -U finsight -d finsight \
	  -c "CREATE SCHEMA IF NOT EXISTS keycloak; CREATE SCHEMA IF NOT EXISTS finsight; CREATE SCHEMA IF NOT EXISTS audit;" \
	  > /dev/null 2>&1
	@echo "✓  Schemas ready"
	@until docker-compose exec -T redis redis-cli -a redis_secret ping > /dev/null 2>&1; do sleep 2; done
	@echo "✓  Redis ready"
	@docker-compose up -d kafka
	@echo "⏳ Waiting for Kafka (~30s)..."
	@until docker-compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do sleep 3; done
	@echo "✓  Kafka ready"
	@docker-compose up -d kafka-init
	@sleep 5
	@docker-compose up -d keycloak kafka-ui redis-ui jaeger
	@echo "⏳ Waiting for Keycloak (~60s)..."
	@until curl -sf http://localhost:9000/health/ready > /dev/null 2>&1; do sleep 5; done
	@echo "✓  Keycloak ready"
	@echo ""
	@echo "✅ All services running!"
	@echo "  PostgreSQL → localhost:5432  |  Redis → localhost:6379  |  Kafka → localhost:29092"
	@echo "  Keycloak   → http://localhost:8180 (admin/admin_secret)"
	@echo "  Kafka UI   → http://localhost:8090  |  Redis UI → http://localhost:8001  |  Jaeger UI → http://localhost:16686"

## Stop all infrastructure
infra-down:
	@docker-compose down

## Show status of all containers
infra-status:
	@docker-compose ps

## Tail logs from all containers
infra-logs:
	@docker-compose logs -f

## Tail logs for a specific service: make infra-logs-keycloak
infra-logs-%:
	@docker-compose logs -f $*

## Wipe all volumes and start fresh
infra-clean:
	@echo "⚠️  This will DELETE all data. Are you sure? [y/N]" && read ans && [ $${ans:-N} = y ]
	@docker-compose down -v --remove-orphans

# ── Tokens ────────────────────────────────────────────────────

## Get JWT token with ALL scopes (for local profile testing)
token-full:
	@curl -sf -X POST http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-full" -d "password=agent123" \
	  -d "scope=openid payment:read fraud:read banking:read system:read" \
	  | jq -r '.access_token'

## Get JWT token for payments agent only
token-payments:
	@curl -sf -X POST http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-payments" -d "password=agent123" \
	  -d "scope=openid payment:read system:read" \
	  | jq -r '.access_token'

## Get JWT token for fraud agent only
token-fraud:
	@curl -sf -X POST http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-fraud" -d "password=agent123" \
	  -d "scope=openid fraud:read system:read" \
	  | jq -r '.access_token'

## Decode the full-access token and pretty print claims
decode-token:
	@$(MAKE) -s token-full | cut -d. -f2 | base64 -d 2>/dev/null | jq .

## Check Keycloak health
keycloak-ready:
	@curl -sf http://localhost:9000/health/ready | jq .

# ── App ───────────────────────────────────────────────────────

## Start app — local profile
## Real infra + mock adapters + localhost JWK
## Use for: make mcp-test-* (no ngrok needed)
app:
	@cd finsight-mcp-server && ../mvnw spring-boot:run \
	  -Dspring-boot.run.profiles=local

## Start app — prod profile
## Real infra + real adapters (Stripe+pgvector+OBP) + ngrok JWK
## Use for: Claude Desktop connection, Stripe sandbox testing
## Requires: ngrok tunnels running, env vars: FINSIGHT_PUBLIC_URL, KEYCLOAK_PUBLIC_URL,
##           STRIPE_API_KEY, OBP_CONSUMER_KEY, OBP_USERNAME, OBP_PASSWORD
app-prod:
	@cd finsight-mcp-server && ../mvnw spring-boot:run \
	  -Dspring-boot.run.profiles=prod

# ── MCP Testing (local profile only) ─────────────────────────
# All targets below require: make app (running in another terminal)
# These use mock data — for real data testing use app-prod + Claude Desktop

## List all registered MCP tools
mcp-list-tools:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result.tools[] | {name, description: .description[:80]}'

## Test getTransaction — txn-001 (MediaMarkt Berlin, 250 EUR, CAPTURED)
mcp-test-get-txn:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"getTransaction","arguments":{"transactionId":"txn-001"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test explainDecline — txn-002 (Zalando, DECLINED, insufficient_funds)
mcp-test-decline:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"explainDecline","arguments":{"transactionId":"txn-002"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test reconcileTransactions — last 24h, all statuses
mcp-test-reconcile:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"reconcileTransactions","arguments":{"hoursBack":24,"statusFilter":"ALL"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test scoreTransaction — txn-005 (Apple Store US, 3200 EUR, CRITICAL risk)
mcp-test-fraud:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"scoreTransaction","arguments":{"transactionId":"txn-005"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test explainFraudSignals — txn-005 (4 signals: HIGH_AMOUNT, GEO_MISMATCH, VELOCITY, DECLINE)
mcp-test-fraud-explain:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"explainFraudSignals","arguments":{"transactionId":"txn-005"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test scoreTransaction for a specific transaction
## Usage: make mcp-test-fraud-score TXN=txn-001
mcp-test-fraud-score:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"scoreTransaction\",\"arguments\":{\"transactionId\":\"$(TXN)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test checkVelocity — IP 203.0.113.5 (suspicious, 15min window)
mcp-test-velocity:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"checkVelocity","arguments":{"dimension":"IP","value":"203.0.113.5","windowMinutes":15}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test listConnectedBanks — German banks
## local:  returns 5 mock German banks
## prod:   returns 20 real OBP banks
mcp-test-banks:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"listConnectedBanks","arguments":{"countryCode":"DE"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test analyzePaymentRoute — 250 EUR, DE, CARD
mcp-test-route:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"analyzePaymentRoute","arguments":{"amount":"250.00","currency":"EUR","merchantId":"merchant-de-001","countryCode":"DE","paymentMethod":"CARD"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test fetchAccountData
## local: make mcp-test-account                                    → acc-de-001 (Deutsche Bank mock)
## prod:  make mcp-test-account ACCOUNT_ID=test-bank/fgarcia_account1 → real OBP account
mcp-test-account:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"fetchAccountData\",\"arguments\":{\"accountId\":\"$(ACCOUNT_ID)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test checkConsent
## local: make mcp-test-consent                                    → acc-de-001 (mock, VALID)
## prod:  make mcp-test-consent ACCOUNT_ID=test-bank/fgarcia_account1 → real OBP
mcp-test-consent:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"checkConsent\",\"arguments\":{\"accountId\":\"$(ACCOUNT_ID)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test fetchAllAccounts
## local: make mcp-test-all-accounts                                    → req-demo-001 (2 mock accounts)
## prod:  make mcp-test-all-accounts REQUISITION_ID=test-bank           → real OBP accounts
mcp-test-all-accounts:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"fetchAllAccounts\",\"arguments\":{\"requisitionId\":\"$(REQUISITION_ID)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Run all MCP tests in sequence (local profile, mock data)
mcp-test-all: mcp-list-tools mcp-test-get-txn mcp-test-decline mcp-test-reconcile \
              mcp-test-fraud mcp-test-fraud-explain mcp-test-velocity \
              mcp-test-banks mcp-test-route mcp-test-account mcp-test-consent mcp-test-all-accounts

# ── Stripe Testing (prod profile only) ───────────────────────
# These targets require app-prod running and STRIPE_API_KEY set.
# They create real Stripe sandbox PaymentIntents then query them via MCP.
# Note: token-full uses localhost Keycloak — only works if app-prod
# is configured with issuer-uri matching localhost (not ngrok).
# For full prod testing with Claude Desktop, use Stripe Dashboard instead.

## Create a test PaymentIntent in Stripe sandbox
stripe-create-payment:
	@echo "Creating test PaymentIntent in Stripe sandbox..."
	@curl -s https://api.stripe.com/v1/payment_intents \
	  -u $(STRIPE_API_KEY): \
	  -d amount=8999 -d currency=eur \
	  -d "payment_method_types[]=card" \
	  -d "metadata[merchant_name]=Zalando" \
	  -d "metadata[country_code]=DE" \
	  | jq '{id, amount, currency, status}'

## Create and decline a test PaymentIntent (insufficient funds)
stripe-create-declined:
	@echo "Creating declined PaymentIntent in Stripe sandbox..."
	@PI_ID=$$(curl -s https://api.stripe.com/v1/payment_intents \
	  -u $(STRIPE_API_KEY): \
	  -d amount=8999 -d currency=eur \
	  -d "payment_method_types[]=card" \
	  -d "metadata[merchant_name]=Zalando" \
	  -d "metadata[country_code]=DE" \
	  | jq -r '.id') && \
	echo "PaymentIntent: $$PI_ID" && \
	curl -s https://api.stripe.com/v1/payment_intents/$$PI_ID/confirm \
	  -u $(STRIPE_API_KEY): \
	  -d "payment_method=pm_card_visa_chargeDeclinedInsufficientFunds" \
	  | jq '{id: .error.payment_intent.id, status: .error.payment_intent.status, decline_code: .error.decline_code}'

## Test getTransaction with a real Stripe PaymentIntent ID
## Usage: make stripe-test-get PI=pi_xxx
stripe-test-get:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"getTransaction\",\"arguments\":{\"transactionId\":\"$(PI)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test explainDecline with a real Stripe PaymentIntent ID
## Usage: make stripe-test-decline PI=pi_xxx
stripe-test-decline:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"explainDecline\",\"arguments\":{\"transactionId\":\"$(PI)\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Full Stripe test: create declined payment → getTransaction → explainDecline
## Requires: app-prod running, STRIPE_API_KEY set
stripe-test-full:
	@echo "🔄 Creating declined PaymentIntent in Stripe sandbox..."
	@PI_ID=$$(curl -s https://api.stripe.com/v1/payment_intents \
	  -u $(STRIPE_API_KEY): \
	  -d amount=8999 -d currency=eur \
	  -d "payment_method_types[]=card" \
	  -d "metadata[merchant_name]=Zalando" \
	  -d "metadata[country_code]=DE" \
	  | jq -r '.id') && \
	curl -s https://api.stripe.com/v1/payment_intents/$$PI_ID/confirm \
	  -u $(STRIPE_API_KEY): \
	  -d "payment_method=pm_card_visa_chargeDeclinedInsufficientFunds" > /dev/null && \
	echo "✓  Declined: $$PI_ID" && \
	TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	echo "📋 Transaction details:" && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"getTransaction\",\"arguments\":{\"transactionId\":\"$$PI_ID\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result' && \
	echo "💳 Decline explanation:" && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"explainDecline\",\"arguments\":{\"transactionId\":\"$$PI_ID\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Full prod test: create Stripe PaymentIntent → score for fraud → explain signals
## Requires: app-prod running, STRIPE_API_KEY set
stripe-fraud-full:
	@echo "🔄 Creating PaymentIntent in Stripe sandbox..."
	@PI_ID=$$(curl -s https://api.stripe.com/v1/payment_intents \
	  -u $(STRIPE_API_KEY): \
	  -d amount=32000 -d currency=eur \
	  -d "payment_method_types[]=card" \
	  -d "metadata[merchant_name]=Apple Store" \
	  -d "metadata[country_code]=US" \
	  | jq -r '.id') && \
	curl -s https://api.stripe.com/v1/payment_intents/$$PI_ID/confirm \
	  -u $(STRIPE_API_KEY): \
	  -d "payment_method=pm_card_visa_chargeDeclinedInsufficientFunds" > /dev/null && \
	echo "✓  Created and declined: $$PI_ID" && \
	TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - -d '$(MCP_INIT)' 2>/dev/null \
	  | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	echo "🔍 Fraud score:" && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"scoreTransaction\",\"arguments\":{\"transactionId\":\"$$PI_ID\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result' && \
	echo "📊 Fraud signals:" && \
	curl -s -X POST $(MCP_URL) \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"explainFraudSignals\",\"arguments\":{\"transactionId\":\"$$PI_ID\"}}}" \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

# ── Help ──────────────────────────────────────────────────────

help:
	@echo ""
	@echo "FinSight MCP — available commands"
	@echo "════════════════════════════════════════════════════════"
	@echo ""
	@echo "  PROFILES:"
	@echo "    local  — real infra + mock adapters + localhost JWK"
	@echo "             → use for make mcp-test-* (no ngrok needed)"
	@echo "    prod   — real infra + Stripe/pgvector/OBP + ngrok JWK"
	@echo "             → use for Claude Desktop + Stripe sandbox testing"
	@echo ""
	@echo "  Infra:"
	@echo "    make infra                Start PostgreSQL, Redis, Kafka, Keycloak"
	@echo "    make infra-down           Stop all services"
	@echo "    make infra-status         Show container status"
	@echo "    make infra-logs           Tail all logs"
	@echo "    make infra-logs-keycloak  Tail Keycloak logs"
	@echo "    make infra-clean          Wipe all volumes"
	@echo ""
	@echo "  UIs:"
	@echo "    Keycloak → http://localhost:8180  (admin/admin_secret)"
	@echo "    Kafka UI → http://localhost:8090"
	@echo "    Redis UI → http://localhost:8001  (host=redis port=6379 pass=redis_secret)"
	@echo "    Jaeger UI → http://localhost:16686  (distributed traces)"
	@echo ""
	@echo "  Tokens (for local profile testing):"
	@echo "    make token-full      JWT with all scopes (payment+fraud+banking)"
	@echo "    make token-payments  JWT with payment scope only"
	@echo "    make token-fraud     JWT with fraud scope only"
	@echo "    make decode-token    Decode and inspect JWT claims"
	@echo "    make keycloak-ready  Check Keycloak health"
	@echo ""
	@echo "  App:"
	@echo "    make app       local profile — mock adapters, no ngrok needed"
	@echo "    make app-prod  prod profile  — real adapters, requires ngrok + env vars"
	@echo ""
	@echo "  MCP Testing (requires: make app):"
	@echo "    make mcp-list-tools          List all 11 registered tools"
	@echo "    make mcp-test-get-txn        getTransaction txn-001 (MediaMarkt, CAPTURED)"
	@echo "    make mcp-test-decline        explainDecline txn-002 (Zalando, insufficient_funds)"
	@echo "    make mcp-test-reconcile      reconcileTransactions (last 24h)"
	@echo "    make mcp-test-fraud          scoreTransaction txn-005 (Apple Store, CRITICAL)"
	@echo "    make mcp-test-fraud-explain  explainFraudSignals txn-005 (4 signals)"
	@echo "    make mcp-test-fraud-score    scoreTransaction TXN=txn-xxx"
	@echo "    make mcp-test-velocity       checkVelocity IP 203.0.113.5"
	@echo "    make mcp-test-banks          listConnectedBanks DE (5 mock banks)"
	@echo "    make mcp-test-route          analyzePaymentRoute 250 EUR DE CARD"
	@echo "    make mcp-test-account        fetchAccountData ACCOUNT_ID=acc-de-001"
	@echo "    make mcp-test-consent        checkConsent ACCOUNT_ID=acc-de-001"
	@echo "    make mcp-test-all-accounts   fetchAllAccounts REQUISITION_ID=req-demo-001"
	@echo "    make mcp-test-all            Run all 12 tests in sequence"
	@echo ""
	@echo "  Open Banking (prod profile overrides):"
	@echo "    make mcp-test-account ACCOUNT_ID=test-bank/fgarcia_account1"
	@echo "    make mcp-test-consent ACCOUNT_ID=test-bank/fgarcia_account1"
	@echo "    make mcp-test-all-accounts REQUISITION_ID=test-bank"
	@echo ""
	@echo "  Stripe Testing (requires: make app-prod + STRIPE_API_KEY):"
	@echo "    make stripe-test-full              Create declined PI → getTransaction → explainDecline"
	@echo "    make stripe-create-declined        Create a declined PaymentIntent only"
	@echo "    make stripe-test-get PI=pi_xxx     getTransaction with real Stripe PI"
	@echo "    make stripe-test-decline PI=pi_xxx explainDecline with real Stripe PI"
	@echo ""