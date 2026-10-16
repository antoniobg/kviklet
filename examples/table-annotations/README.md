# Table annotations demo

Runs Kviklet locally against a throwaway database, with a small web app standing in for the
internal service that knows which tables are cached.

Four containers:

| Service | What it is |
| --- | --- |
| `kviklet` | Built from this checkout, on <http://localhost:8080> |
| `demo-postgres` | The pretend production database, on `localhost:55432` |
| `annotation-server` | The "which tables are cached" service plus a small UI, on <http://localhost:8000> |
| `kviklet-postgres` | Kviklet's own application state |

## Run it

```bash
cd examples/table-annotations
docker compose up -d --build   # first build takes a few minutes
./setup.sh
```

`setup.sh` logs in, points Kviklet at the annotation server, creates the `Demo Postgres`
connection and triggers a first sync.
It waits for Kviklet to come up, so run it right after `docker compose up`.

Log in at <http://localhost:8080> with `admin@admin.com` / `admin`.

## What to try

The demo schema has tables that are "cached" and tables that are not, and nothing in the SQL says
which is which.
That is the whole point.

**A write to a cached table.**
New -> Demo Postgres -> Single Query:

```sql
UPDATE subscriber SET active = false WHERE id = 1;
```

A warning appears under the editor as you type, and again on the request page where a reviewer
would see it.
Run Query asks you to tick the table before it will execute, and the acknowledgement is recorded
on the request timeline and in the audit log.

**A write that warns but does not block.**
`billing_*` is configured as warn-only:

```sql
UPDATE billing_invoice SET paid = true WHERE id = 2;
```

**A write to a table nobody annotated.** No warning at all:

```sql
UPDATE audit_log SET action = 'nothing' WHERE id = 1;
```

**A read of a cached table.** Also no warning, because a `SELECT` cannot leave a cache stale:

```sql
SELECT * FROM subscriber;
```

**SQL Kviklet cannot parse.** It says so rather than showing nothing, since an unparseable
statement is not a statement known to be safe:

```sql
UPDATE WHERE WHERE ((
```

## Changing the list

Open <http://localhost:8000> and add or remove a table.
Kviklet re-syncs every few minutes; to see it immediately, go to
Settings -> Table Annotations -> Sync now.

Synced rows are read-only in Kviklet, since the next sync would overwrite an edit made there.
You can still add your own on that page: those are marked `Manual` and a sync never touches them.

To prove the list survives the source going away:

```bash
docker compose stop annotation-server
```

The warnings keep working, because Kviklet stores what it last synced rather than holding it in
memory. Settings -> Table Annotations shows the last sync time and the error.

## Pointing it at something real

The endpoint only has to return a JSON array. Unknown fields are ignored, so your own service can
return whatever else it likes:

```json
[
  {
    "id": "subscriber-cache",
    "table": "public.subscriber",
    "connectionSelector": "prod-*",
    "message": "Mirrored into the read cache. Purge it after this runs.",
    "runbookUrl": "https://runbook.example.com/purge-subscriber-cache",
    "requiresAcknowledgement": true
  }
]
```

Only `table` (or `tablePattern`) is required.
`connectionSelector` defaults to `*` and `requiresAcknowledgement` to `true`.
Set the URL, and optionally an `Authorization` header, under Settings -> General -> Table
Annotation Sync.

## Clean up

```bash
docker compose down -v
```
