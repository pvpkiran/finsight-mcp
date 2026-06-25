# ─────────────────────────────────────────────────────────────
#  FinSight MCP — developer convenience commands
#  Usage: make <target>
# ─────────────────────────────────────────────────────────────

.PHONY: infra infra-down infra-status infra-logs infra-clean \
        token-full token-payments token-fraud \
        decode-token keycloak-ready app app-full help \
        mcp-session mcp-list-tools mcp-test-decline mcp-test-fraud \
        mcp-test-banks mcp-test-route mcp-test-account mcp-test-all

# ── Infra ─────────────────────────────────────────────────────

## Start all infrastructure
infra:
	@echo "🚀 Starting FinSight infrastructure..."
	@chmod +x finsight-infra/kafka/create-topics.sh
	@docker-compose up -d postgres redis
	@echo "⏳ Waiting for PostgreSQL..."
	@until docker-compose exec -T postgres pg_isready -U finsight -d finsight > /dev/null 2>&1; do sleep 2; done
	@echo "✓  PostgreSQL ready"
	@echo "⏳ Creating required schemas..."
	@docker-compose exec -T postgres psql -U finsight -d finsight \
	  -c "CREATE SCHEMA IF NOT EXISTS keycloak; CREATE SCHEMA IF NOT EXISTS finsight; CREATE SCHEMA IF NOT EXISTS audit;" \
	  > /dev/null 2>&1
	@echo "✓  Schemas ready"
	@echo "⏳ Waiting for Redis..."
	@until docker-compose exec -T redis redis-cli -a redis_secret ping > /dev/null 2>&1; do sleep 2; done
	@echo "✓  Redis ready"
	@docker-compose up -d kafka
	@echo "⏳ Waiting for Kafka (this takes ~30s)..."
	@until docker-compose exec -T kafka kafka-topics --bootstrap-server localhost:9092 --list > /dev/null 2>&1; do sleep 3; done
	@echo "✓  Kafka ready"
	@docker-compose up -d kafka-init
	@echo "⏳ Creating Kafka topics..."
	@sleep 5
	@docker-compose up -d keycloak kafka-ui redis-ui
	@echo "⏳ Waiting for Keycloak (this takes ~60s)..."
	@until curl -sf http://localhost:8180/health/ready > /dev/null 2>&1; do sleep 5; done
	@echo "✓  Keycloak ready"
	@echo ""
	@echo "✅ All services running!"
	@echo ""
	@echo "  PostgreSQL  → localhost:5432"
	@echo "  Redis       → localhost:6379"
	@echo "  Kafka       → localhost:29092"
	@echo "  Keycloak    → http://localhost:8180  (admin / admin_secret)"
	@echo "  Kafka UI    → http://localhost:8090"
	@echo "  Redis UI    → http://localhost:8001  (connect: host=redis port=6379 pass=redis_secret)"
	@echo ""
	@echo "  Run 'make token-full' to get a JWT token"

## Stop all infrastructure
infra-down:
	@echo "🛑 Stopping FinSight infrastructure..."
	@docker-compose down
	@echo "✓  All services stopped"

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
	@echo "⚠️  This will DELETE all data (volumes). Are you sure? [y/N]" && read ans && [ $${ans:-N} = y ]
	@docker-compose down -v --remove-orphans
	@echo "✓  All volumes removed. Run 'make infra' to start fresh."

# ── Tokens ────────────────────────────────────────────────────

## Get JWT token with ALL scopes (payment + fraud + banking)
token-full:
	@curl -sf -X POST \
	  http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" \
	  -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-full" \
	  -d "password=agent123" \
	  -d "scope=openid payment:read fraud:read banking:read system:read" \
	  | jq -r '.access_token'

## Get JWT token for payments agent only
token-payments:
	@curl -sf -X POST \
	  http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" \
	  -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-payments" \
	  -d "password=agent123" \
	  -d "scope=openid payment:read system:read" \
	  | jq -r '.access_token'

## Get JWT token for fraud agent only
token-fraud:
	@curl -sf -X POST \
	  http://localhost:8180/realms/finsight/protocol/openid-connect/token \
	  -d "grant_type=password" \
	  -d "client_id=finsight-agent-client" \
	  -d "client_secret=agent-client-secret-change-in-prod" \
	  -d "username=agent-fraud" \
	  -d "password=agent123" \
	  -d "scope=openid fraud:read system:read" \
	  | jq -r '.access_token'

## Decode the full-access token and pretty print claims
decode-token:
	@$(MAKE) -s token-full | cut -d. -f2 | base64 -d 2>/dev/null | jq .

## Check Keycloak health
keycloak-ready:
	@curl -sf http://localhost:8180/health/ready | jq .

# ── App ───────────────────────────────────────────────────────

## Start the Spring Boot app (mock profile)
app:
	@cd finsight-mcp-server && ../mvnw spring-boot:run -Dspring-boot.run.profiles=mock

## Start infra first, then app
app-full: infra app

# ── MCP testing ───────────────────────────────────────────────

## List all registered MCP tools
mcp-list-tools:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result.tools[] | {name, description: .description[:80]}'

## Test explainDecline tool — txn-002 (Zalando declined, insufficient funds)
mcp-test-decline:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"explainDecline","arguments":{"transactionId":"txn-002"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test scoreTransaction tool — txn-005 (high risk, Apple Store US)
mcp-test-fraud:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"scoreTransaction","arguments":{"transactionId":"txn-005"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test listConnectedBanks tool — German banks
mcp-test-banks:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"listConnectedBanks","arguments":{"countryCode":"DE"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test analyzePaymentRoute tool
mcp-test-route:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"analyzePaymentRoute","arguments":{"amount":"250.00","currency":"EUR","merchantId":"merchant-de-001","countryCode":"DE","paymentMethod":"CARD"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Test fetchAccountData tool — Deutsche Bank account
mcp-test-account:
	@TOKEN=$$($(MAKE) -s token-full) && \
	SESSION=$$(curl -sf -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -D - \
	  -d '{"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"finsight-cli","version":"1.0"}}}' \
	  2>/dev/null | grep -i "mcp-session-id" | awk '{print $$2}' | tr -d '\r') && \
	curl -s -X POST http://localhost:8080/mcp \
	  -H "Authorization: Bearer $$TOKEN" \
	  -H "Content-Type: application/json" \
	  -H "Accept: text/event-stream, application/json" \
	  -H "Mcp-Session-Id: $$SESSION" \
	  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"fetchAccountData","arguments":{"accountId":"acc-de-001"}}}' \
	  | grep "^data:" | sed 's/^data://' | jq '.result'

## Run all MCP tests in sequence
mcp-test-all: mcp-list-tools mcp-test-decline mcp-test-fraud mcp-test-banks mcp-test-route mcp-test-account

# ── Help ──────────────────────────────────────────────────────

help:
	@echo ""
	@echo "FinSight MCP — available commands:"
	@echo ""
	@echo "  Infra:"
	@echo "    make infra                Start all infrastructure"
	@echo "    make infra-down           Stop all infrastructure"
	@echo "    make infra-status         Show container health"
	@echo "    make infra-logs           Tail all logs"
	@echo "    make infra-logs-keycloak  Tail Keycloak logs"
	@echo "    make infra-logs-kafka     Tail Kafka logs"
	@echo "    make infra-clean          Wipe all data and volumes"
	@echo ""
	@echo "  UIs:"
	@echo "    Keycloak  → http://localhost:8180  (admin / admin_secret)"
	@echo "    Kafka UI  → http://localhost:8090"
	@echo "    Redis UI  → http://localhost:8001  (connect: host=redis port=6379 pass=redis_secret)"
	@echo ""
	@echo "  Tokens:"
	@echo "    make token-full      JWT with all scopes"
	@echo "    make token-payments  JWT with payment scope only"
	@echo "    make token-fraud     JWT with fraud scope only"
	@echo "    make decode-token    Decode and inspect JWT claims"
	@echo "    make keycloak-ready  Check Keycloak health"
	@echo ""
	@echo "  App:"
	@echo "    make app             Start Spring Boot (mock profile)"
	@echo "    make app-full        Start infra + app"
	@echo ""
	@echo "  MCP Testing (auto session handshake):"
	@echo "    make mcp-list-tools    List all 11 registered tools"
	@echo "    make mcp-test-decline  explainDecline (txn-002 — insufficient funds)"
	@echo "    make mcp-test-fraud    scoreTransaction (txn-005 — high risk)"
	@echo "    make mcp-test-banks    listConnectedBanks (DE)"
	@echo "    make mcp-test-route    analyzePaymentRoute (250 EUR, DE)"
	@echo "    make mcp-test-account  fetchAccountData (acc-de-001 Deutsche Bank)"
	@echo "    make mcp-test-all      Run all 6 tests in sequence"
	@echo ""