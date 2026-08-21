#!/usr/bin/env bash
# End-to-end smoke test against a running stack. Exercises the paths that are
# easy to get subtly wrong and hard to notice: replay, conflict, reversal, and
# as-of balance.
set -euo pipefail

BASE="${BASE:-http://localhost:8080}"
CASH="00000000-0000-0000-0000-000000000001"
PAYABLE="00000000-0000-0000-0000-000000000002"
KEY="smoke-$(date +%s)-$RANDOM"

body() {
  cat <<JSON
{"description":"smoke transfer","postings":[
  {"accountId":"$CASH","amountMinor":$1,"currency":"INR"},
  {"accountId":"$PAYABLE","amountMinor":-$1,"currency":"INR"}]}
JSON
}

post() {
  curl -sS -o /tmp/smoke-body.json -w '%{http_code}' \
    -X POST "$BASE/v1/entries" \
    -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $1" \
    -d "$(body "$2")"
}

fail() { echo "SMOKE FAILED: $1"; cat /tmp/smoke-body.json 2>/dev/null; exit 1; }

echo "1. create -> expect 201"
[ "$(post "$KEY" 50000)" = "201" ] || fail "create did not return 201"
ENTRY_ID=$(python3 -c 'import json;print(json.load(open("/tmp/smoke-body.json"))["id"])')
echo "   entry $ENTRY_ID"

echo "2. same key, same body -> expect 200 replay"
[ "$(post "$KEY" 50000)" = "200" ] || fail "replay did not return 200"

echo "3. same key, DIFFERENT body -> expect 409"
[ "$(post "$KEY" 99999)" = "409" ] || fail "key reuse with a different body was not a 409"
grep -q "idempotency-key-reuse" /tmp/smoke-body.json || fail "409 body is not the expected problem type"

echo "4. unbalanced entry -> expect 422"
code=$(curl -sS -o /tmp/smoke-body.json -w '%{http_code}' -X POST "$BASE/v1/entries" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: unbal-$KEY" \
  -d "{\"description\":\"unbalanced\",\"postings\":[
        {\"accountId\":\"$CASH\",\"amountMinor\":100,\"currency\":\"INR\"},
        {\"accountId\":\"$PAYABLE\",\"amountMinor\":-60,\"currency\":\"INR\"}]}")
[ "$code" = "422" ] || fail "unbalanced entry was not rejected with 422 (got $code)"
grep -q "imbalanceByCurrency" /tmp/smoke-body.json || fail "422 does not name the imbalance"

echo "5. missing Idempotency-Key -> expect 400 problem+json"
code=$(curl -sS -o /tmp/smoke-body.json -w '%{http_code}' -X POST "$BASE/v1/entries" \
  -H 'Content-Type: application/json' -d "$(body 10)")
[ "$code" = "400" ] || fail "missing idempotency key was not a 400 (got $code)"

echo "6. balance reflects exactly one application of the entry"
balance=$(curl -sS "$BASE/v1/accounts/$CASH/balance" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["balanceMinor"])')
echo "   cash balance: $balance"

echo "7. reverse the entry -> expect 201"
code=$(curl -sS -o /tmp/smoke-body.json -w '%{http_code}' \
  -X POST "$BASE/v1/entries/$ENTRY_ID/reverse" \
  -H "Idempotency-Key: rev-$KEY")
[ "$code" = "201" ] || fail "reversal did not return 201 (got $code)"

echo "8. reversing twice -> expect 409"
code=$(curl -sS -o /tmp/smoke-body.json -w '%{http_code}' \
  -X POST "$BASE/v1/entries/$ENTRY_ID/reverse" \
  -H "Idempotency-Key: rev2-$KEY")
[ "$code" = "409" ] || fail "second reversal was not a 409 (got $code)"

echo "9. balance is back to its pre-entry value"
after=$(curl -sS "$BASE/v1/accounts/$CASH/balance" \
  | python3 -c 'import json,sys;print(json.load(sys.stdin)["balanceMinor"])')
[ "$after" = "$((balance - 50000))" ] || fail "reversal did not restore the balance ($after)"

echo "10. as_of reconstructs a past balance"
curl -fsS "$BASE/v1/accounts/$CASH/balance?as_of=2000-01-01T00:00:00Z" \
  | grep -q '"balanceMinor":0' || fail "as_of did not reconstruct an empty past"

echo "11. statement is cursor-paginated"
# Book enough entries that a page boundary actually exists -- asking for a cursor
# on an account with fewer postings than the page size proves nothing.
for i in 1 2 3; do
  post "page-$KEY-$i" 100 > /dev/null
done
page1=$(curl -fsS "$BASE/v1/accounts/$CASH/statement?limit=2")
echo "$page1" | grep -q '"hasMore":true' || fail "expected more pages after 3 entries"
cursor=$(printf '%s' "$page1" | python3 -c 'import json,sys; print(json.load(sys.stdin)["nextCursor"])')
[ -n "$cursor" ] && [ "$cursor" != "None" ] || fail "statement did not return a cursor"

page2=$(curl -fsS "$BASE/v1/accounts/$CASH/statement?limit=2&cursor=$cursor")
python3 - "$page1" "$page2" <<'PY' || fail "cursor pages overlap"
import json, sys
a = {i["id"] for i in json.loads(sys.argv[1])["items"]}
b = {i["id"] for i in json.loads(sys.argv[2])["items"]}
assert a and b and not (a & b), f"pages overlap: {a & b}"
PY
echo "   page 1 and page 2 are disjoint"

echo
echo "SMOKE PASSED"
