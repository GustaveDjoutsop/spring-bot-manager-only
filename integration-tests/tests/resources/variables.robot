*** Variables ***
${BASE_URL}                 http://localhost:8090
${WIREMOCK_META_URL}        http://localhost:9090
${WIREMOCK_PAYMENT_URL}     http://localhost:9091
${WIREMOCK_MACHINE_URL}     http://localhost:9092
${CONTENT_TYPE}             application/json

# ── Auth0 credentials (dev tenant) ───────────────────────────────────────────
${AUTH0_TOKEN_URL}          https://dev-iuo6si32jobgnmod.eu.auth0.com/oauth/token
${AUTH0_CLIENT_ID}          qrhBuc3lsJfRqsP8xKWAO334DOsseidM
${AUTH0_CLIENT_SECRET}      5_L-EOe5BBn3g2V9egVHWnKJkabkxtqom4kIqxluEx8J0N6VEbFtG7XPdoCZln7G
${AUTH0_AUDIENCE}           https://smartlaundry.api
${AUTH0_SCOPE}              sls-machine-read sls-payment-read

# ── WhatsApp webhook test data ────────────────────────────────────────────────
${BOT_ID}                   laundry
${PHONE_NUMBER_ID}          123456789
${VERIFY_TOKEN}             test-verify-token
${HUB_CHALLENGE}            challenge-abc-123
${CUSTOMER_PHONE}           237650000001
${APP_SECRET}               test-app-secret

# ── Payment test data ─────────────────────────────────────────────────────────
${MACHINE_ID}               washer_01
${PAYMENT_AMOUNT}           ${1000}
${TX_REF}                   ext-ref-mock-001

# ── Machine test data ─────────────────────────────────────────────────────────
${MACHINE_WASHER_1}         washer_01
${MACHINE_WASHER_2}         washer_02