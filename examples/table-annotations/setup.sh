#!/usr/bin/env bash
#
# Points this Kviklet at the demo database and at the annotation server, so the demo is ready to
# click through. Safe to re-run: it tolerates the connection already existing.
set -euo pipefail

BASE_URL="${KVIKLET_BASE_URL:-http://localhost:8080}"
API="$BASE_URL/api"
EMAIL="${KVIKLET_EMAIL:-admin@admin.com}"
PASSWORD="${KVIKLET_PASSWORD:-admin}"
COOKIES="$(mktemp)"
trap 'rm -f "$COOKIES"' EXIT

# Every Kviklet request carries this header as CSRF protection.
hdr=(-H "Content-Type: application/json" -H "X-Kviklet-Request: true")

echo "Waiting for Kviklet at $API ..."
for _ in $(seq 1 120); do
  code=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$API/login" "${hdr[@]}" \
    -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" || true)
  if [ "$code" = "200" ]; then break; fi
  sleep 2
done
if [ "${code:-}" != "200" ]; then
  echo "Kviklet did not come up (last status: ${code:-none}). Try: docker compose logs kviklet" >&2
  exit 1
fi

curl -s -c "$COOKIES" -X POST "$API/login" "${hdr[@]}" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" -o /dev/null
echo "Logged in as $EMAIL"

# The sync URL is resolved inside the Docker network, not from your laptop.
curl -s -b "$COOKIES" -X PUT "$API/config/" "${hdr[@]}" \
  -d '{"tableAnnotationSyncUrl":"http://annotation-server:8000/annotations"}' -o /dev/null
echo "Set the annotation sync URL"

# numTotalRequired 0 so one person can execute without a second reviewer. The warning and the
# acknowledgement are what this demo is about, not the review flow.
status=$(curl -s -b "$COOKIES" -X POST "$API/connections/" "${hdr[@]}" -w '%{http_code}' -o /dev/null \
  -d '{
        "connectionType": "DATASOURCE",
        "id": "demo-postgres",
        "displayName": "Demo Postgres",
        "description": "Pretend production database for the table annotations demo",
        "databaseName": "demo",
        "username": "postgres",
        "password": "postgres",
        "hostname": "demo-postgres",
        "port": 5432,
        "type": "POSTGRESQL",
        "reviewConfig": { "numTotalRequired": 0 },
        "explainEnabled": true
      }')
case "$status" in
  200|201) echo "Created the 'Demo Postgres' connection" ;;
  409)     echo "Connection 'demo-postgres' already exists, leaving it alone" ;;
  *)       echo "Could not create the connection (HTTP $status)" >&2; exit 1 ;;
esac

# Pull the list now instead of waiting for the next scheduled sync.
curl -s -b "$COOKIES" -X POST "$API/table-annotations/sync" "${hdr[@]}" | sed 's/^/Sync: /'
echo

cat <<EOF

Ready.

  Kviklet            $BASE_URL        (admin@admin.com / admin)
  Cached tables app  http://localhost:8000
  Demo database      localhost:55432  (postgres/postgres, database "demo")

Try this:
  1. New -> Demo Postgres -> Single Query
  2. UPDATE subscriber SET active = false WHERE id = 1;
     A warning appears as you type, and again on the request page.
  3. Run Query. You have to tick the table before it will execute.
  4. UPDATE audit_log SET action = 'nothing' WHERE id = 1;
     No warning: that table is not annotated.
EOF
