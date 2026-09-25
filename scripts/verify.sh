#!/usr/bin/env bash
#
# End-to-end checks against a running notification engine.
#
#   ./scripts/verify.sh                        # against http://localhost:8080
#   BASE_URL=http://host:8080 ./scripts/verify.sh
#
# Exits non-zero on the first failed check, so it is usable as a CI gate.
# Assumes the stack and the application are already up; see the README.

set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
PASS=0
FAIL=0
LAST_BODY="$(mktemp)"
trap 'rm -f "$LAST_BODY"' EXIT

green() { printf '  \033[32m✓\033[0m %s\n' "$1"; PASS=$((PASS + 1)); }
red() {
    printf '  \033[31m✗\033[0m %s\n' "$1"
    printf '      expected: %s\n      actual:   %s\n' "$2" "$3"
    [ -s "$LAST_BODY" ] && printf '      response: %s\n' "$(head -c 300 "$LAST_BODY")"
    FAIL=$((FAIL + 1))
}
check() { # label expected actual
    [ "$2" = "$3" ] && green "$1" || red "$1" "$2" "$3"
}
section() { printf '\n\033[1m%s\033[0m\n' "$1"; }

# curl arguments are assembled in an array. Built as a bare string, the shell would split
# a header like "Idempotency-Key: abc" on its space into two arguments, and the request
# would fail for a reason that has nothing to do with the engine.
curl_args() { # method path [body] [header]
    ARGS=(-sS -X "$1" "$BASE_URL$2" -H 'Content-Type: application/json')
    [ -n "${3:-}" ] && ARGS+=(-d "$3")
    [ -n "${4:-}" ] && ARGS+=(-H "$4")
    return 0
}
api() { # method path [body] [header] -> response body
    curl_args "$@"
    curl "${ARGS[@]}" | tee "$LAST_BODY"
}
status_of() { # method path [body] [header] -> HTTP code
    curl_args "$@"
    # The body is kept so a failed check can show what the server actually said.
    curl -o "$LAST_BODY" -w '%{http_code}' "${ARGS[@]}"
}
field() { python3 -c "import json,sys;print(json.load(sys.stdin).get('$1'))" 2>/dev/null || echo '<unparseable>'; }

# JSON is built with printf rather than inline escapes: nesting escaped double quotes
# inside a command substitution is how this script silently sent unparseable bodies.
send_body()  { printf '{"userId":"%s","channel":"%s","templateCode":"%s","data":{"firstName":"Ada","product":"Acme"}}' "$1" "$2" "$3"; }
prefs_body() { printf '{"channel":"%s","enabled":%s,"destination":"%s"}' "$1" "$2" "$3"; }
quiet_body() { printf '{"channel":"EMAIL","enabled":true,"destination":"q@example.com","timeZone":"UTC","quietHoursStart":%s,"quietHoursEnd":%s}' "$1" "$2"; }

# A fresh user per run, so a repeat run does not trip the previous run's rate-limit window.
RUN="v$(date +%s)"

section "Reachability"
if ! curl -sS -m 5 "$BASE_URL/actuator/health" > /dev/null 2>&1; then
    printf '  \033[31m✗\033[0m cannot reach %s — is the application running?\n' "$BASE_URL"
    exit 1
fi
check "health endpoint responds UP" "UP" "$(curl -sS "$BASE_URL/actuator/health" | field status)"

section "Delivery"
api PUT "/api/v1/users/$RUN-a/preferences" "$(prefs_body EMAIL true ada@example.com)" > /dev/null
SEND_A="$(send_body "$RUN-a" EMAIL welcome)"
SENT=$(api POST /api/v1/notifications "$SEND_A" "Idempotency-Key: $RUN-key")
ID=$(printf '%s' "$SENT" | field id)
check "accepted and queued"        "QUEUED"               "$(printf '%s' "$SENT" | field status)"
check "rendered from the template" "Welcome aboard, Ada!" "$(printf '%s' "$SENT" | field subject)"

sleep 4  # outbox poll -> router -> channel consumer -> provider
check "delivered end to end"  "SENT" "$(api GET "/api/v1/notifications/$ID" | field status)"
check "recorded one attempt"  "1"    "$(api GET "/api/v1/notifications/$ID" | field attempts)"

section "Idempotency (Ignite getAndPutIfAbsent, with the DB constraint behind it)"
REPLAY=$(api POST /api/v1/notifications "$SEND_A" "Idempotency-Key: $RUN-key")
check "replayed key returns the original" "$ID"  "$(printf '%s' "$REPLAY" | field id)"
check "flagged as a duplicate"            "True" "$(printf '%s' "$REPLAY" | field duplicate)"
check "replay answers 200, not 202"       "200"  "$(status_of POST /api/v1/notifications "$SEND_A" "Idempotency-Key: $RUN-key")"

section "Rate limiting (Ignite CAS loop, SMS quota is 5/min)"
api PUT "/api/v1/users/$RUN-b/preferences" "$(prefs_body SMS true +14155550123)" > /dev/null
SEND_B="$(send_body "$RUN-b" SMS welcome)"
over_quota=0
for i in 1 2 3 4 5; do
    code=$(status_of POST /api/v1/notifications "$SEND_B")
    [ "$code" = "202" ] || { red "request $i inside the quota" "202" "$code"; over_quota=1; }
done
[ "$over_quota" -eq 0 ] && green "5 requests inside the quota accepted"
check "6th request throttled" "429" "$(status_of POST /api/v1/notifications "$SEND_B")"

curl_args POST /api/v1/notifications "$SEND_B"
RETRY_AFTER=$(curl -sS -D - -o /dev/null "${ARGS[@]}" | tr -d '\r' | awk 'tolower($1) == "retry-after:" {print $2}')
if [ -n "$RETRY_AFTER" ] && [ "$RETRY_AFTER" -ge 1 ] 2>/dev/null; then
    green "Retry-After present and at least 1s (${RETRY_AFTER}s)"
else
    red "Retry-After present and at least 1s" ">=1" "${RETRY_AFTER:-<missing>}"
fi

section "Preferences"
api PUT "/api/v1/users/$RUN-c/preferences" "$(prefs_body PUSH false device-token-abc123)" > /dev/null
OPTED_OUT=$(api POST /api/v1/notifications "$(send_body "$RUN-c" PUSH welcome)")
check "opt-out suppresses rather than drops" "SUPPRESSED"             "$(printf '%s' "$OPTED_OUT" | field status)"
check "suppression records a reason"         "user opted out of PUSH" "$(printf '%s' "$OPTED_OUT" | field failureReason)"

HOUR=$(date -u +%H | sed 's/^0*//'); HOUR=${HOUR:-0}
api PUT "/api/v1/users/$RUN-d/preferences" "$(quiet_body "$HOUR" "$(( (HOUR + 1) % 24 ))")" > /dev/null
check "quiet hours suppress" "SUPPRESSED" \
    "$(api POST /api/v1/notifications "$(send_body "$RUN-d" EMAIL welcome)" | field status)"

section "Request validation"
MISSING_VAR=$(printf '{"userId":"%s","channel":"EMAIL","templateCode":"welcome","data":{"firstName":"Ada"}}' "$RUN-a")
check "missing template variable -> 422" "422" "$(status_of POST /api/v1/notifications "$MISSING_VAR")"
check "unknown template -> 404"          "404" "$(status_of POST /api/v1/notifications "$(send_body "$RUN-a" EMAIL does-not-exist)")"
check "missing required field -> 400"    "400" "$(status_of POST /api/v1/notifications '{"channel":"EMAIL"}')"
check "unknown channel -> 400"           "400" "$(status_of POST /api/v1/notifications '{"userId":"x","channel":"CARRIER_PIGEON","templateCode":"welcome"}')"
check "unparseable body -> 400"          "400" "$(status_of POST /api/v1/notifications '{not json')"

section "Result"
printf '  %d passed, %d failed\n\n' "$PASS" "$FAIL"
[ "$FAIL" -eq 0 ]
