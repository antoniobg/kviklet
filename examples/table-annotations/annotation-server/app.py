#!/usr/bin/env python3
"""
A stand-in for the internal service that knows which tables are cached.

Kviklet syncs its SYNCED annotations from GET /annotations. Everything else here is just a small
UI so you can change that list and watch Kviklet pick the change up.

Deliberately dependency-free (Python standard library only) so the demo needs no build step.
"""

import html
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs

STORE_PATH = os.environ.get("ANNOTATIONS_FILE", "/data/annotations.json")
PORT = int(os.environ.get("PORT", "8000"))

# Written to the store on first boot so the demo has something to show immediately. The patterns
# mirror demo-data.sql: subscriber and entitlement are "cached", billing_* only warns, and
# audit_log is absent on purpose so you can see a write that raises nothing.
SEED = [
    {
        "id": "subscriber-cache",
        "table": "subscriber",
        "connectionSelector": "*",
        "message": "Mirrored into the Redis read cache. Purge it after this runs.",
        "runbookUrl": "https://example.com/runbooks/purge-subscriber-cache",
        "requiresAcknowledgement": True,
    },
    {
        "id": "entitlement-cache",
        "table": "entitlement",
        "connectionSelector": "*",
        "message": "Entitlements are cached per subscriber. Purge the subscriber's key too.",
        "runbookUrl": "https://example.com/runbooks/purge-entitlements",
        "requiresAcknowledgement": True,
    },
    {
        "id": "billing-tables",
        "table": "billing_*",
        "connectionSelector": "*",
        "message": "Billing tables feed the nightly revenue export. Writes here may need a re-run.",
        "runbookUrl": None,
        # Warn only: shown on the page, never blocks execution.
        "requiresAcknowledgement": False,
    },
]

_lock = threading.Lock()


def load():
    with _lock:
        if not os.path.exists(STORE_PATH):
            os.makedirs(os.path.dirname(STORE_PATH), exist_ok=True)
            with open(STORE_PATH, "w") as handle:
                json.dump(SEED, handle, indent=2)
            return list(SEED)
        with open(STORE_PATH) as handle:
            return json.load(handle)


def save(annotations):
    with _lock:
        with open(STORE_PATH, "w") as handle:
            json.dump(annotations, handle, indent=2)


PAGE = """<!doctype html>
<meta charset="utf-8">
<title>Cached tables</title>
<style>
  :root {{ color-scheme: light dark; }}
  body {{ font: 14px/1.5 system-ui, sans-serif; margin: 0; padding: 2rem;
         max-width: 60rem; margin-inline: auto; }}
  h1 {{ font-size: 1.25rem; margin: 0 0 .25rem; }}
  p.lede {{ color: #666; margin-top: 0; }}
  table {{ border-collapse: collapse; width: 100%; margin: 1.5rem 0; }}
  th, td {{ text-align: left; padding: .5rem .6rem; border-bottom: 1px solid #8884; vertical-align: top; }}
  th {{ font-size: .75rem; text-transform: uppercase; letter-spacing: .04em; color: #666; }}
  code {{ font-family: ui-monospace, monospace; }}
  form.inline {{ display: inline; }}
  fieldset {{ border: 1px solid #8884; border-radius: .4rem; padding: 1rem; }}
  legend {{ font-size: .8rem; color: #666; padding-inline: .4rem; }}
  label {{ display: block; margin-bottom: .6rem; }}
  label span {{ display: block; font-size: .75rem; color: #666; }}
  input[type=text] {{ width: 100%; padding: .4rem; border: 1px solid #8886;
                      border-radius: .25rem; background: transparent; color: inherit; }}
  button {{ padding: .35rem .7rem; border-radius: .25rem; border: 1px solid #8886;
            background: transparent; color: inherit; cursor: pointer; }}
  .endpoint {{ background: #8881; padding: .6rem .8rem; border-radius: .3rem; }}
</style>

<h1>Cached tables</h1>
<p class="lede">
  Stands in for the internal service that knows which tables are mirrored elsewhere.
  Kviklet syncs this list every few minutes, or immediately via <em>Sync now</em> in its settings.
</p>

<p class="endpoint">Kviklet syncs from <code>http://annotation-server:8000/annotations</code></p>

<table>
  <tr>
    <th>Table pattern</th><th>Connections</th><th>Message</th><th>Acknowledgement</th><th></th>
  </tr>
  {rows}
</table>

<fieldset>
  <legend>Add a table</legend>
  <form method="post" action="/add">
    <label>Table pattern <span>e.g. <code>subscriber</code> or <code>billing_*</code></span>
      <input type="text" name="table" required placeholder="subscriber"></label>
    <label>Connection selector <span>e.g. <code>*</code> or <code>prod-*</code></span>
      <input type="text" name="connectionSelector" value="*"></label>
    <label>Message <span>What should the person do about it?</span>
      <input type="text" name="message" required
             placeholder="Mirrored into the read cache. Purge it after this runs."></label>
    <label>Runbook URL <span>Optional</span>
      <input type="text" name="runbookUrl" placeholder="https://..."></label>
    <label><input type="checkbox" name="requiresAcknowledgement" checked>
      Require acknowledgement before executing</label>
    <button type="submit">Add</button>
  </form>
</fieldset>
"""

ROW = """<tr>
  <td><code>{table}</code></td>
  <td><code>{selector}</code></td>
  <td>{message}{runbook}</td>
  <td>{ack}</td>
  <td><form class="inline" method="post" action="/delete">
    <input type="hidden" name="id" value="{id}"><button type="submit">Remove</button></form></td>
</tr>"""


def render(annotations):
    if not annotations:
        rows = '<tr><td colspan="5">Nothing is annotated. Kviklet will warn about nothing.</td></tr>'
    else:
        rows = "".join(
            ROW.format(
                table=html.escape(str(a.get("table") or a.get("tablePattern") or "")),
                selector=html.escape(str(a.get("connectionSelector") or "*")),
                message=html.escape(str(a.get("message") or "")),
                runbook=(
                    ' <a href="{0}">runbook</a>'.format(html.escape(a["runbookUrl"]))
                    if a.get("runbookUrl")
                    else ""
                ),
                ack="Required" if a.get("requiresAcknowledgement", True) else "Warn only",
                id=html.escape(str(a.get("id") or "")),
            )
            for a in annotations
        )
    return PAGE.format(rows=rows)


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _respond(self, status, body, content_type):
        payload = body.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def _redirect(self):
        self.send_response(303)
        self.send_header("Location", "/")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def _form(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length).decode("utf-8")
        return {k: v[0] for k, v in parse_qs(raw).items()}

    def do_GET(self):
        if self.path.rstrip("/") == "/annotations":
            # The only endpoint Kviklet actually reads.
            self._respond(200, json.dumps(load(), indent=2), "application/json")
        elif self.path == "/":
            self._respond(200, render(load()), "text/html; charset=utf-8")
        else:
            self._respond(404, "not found", "text/plain; charset=utf-8")

    def do_POST(self):
        form = self._form()
        annotations = load()

        if self.path == "/add":
            table = (form.get("table") or "").strip()
            if table:
                runbook = (form.get("runbookUrl") or "").strip()
                annotations.append(
                    {
                        # Kviklet upserts SYNCED rows by this id, so it has to be stable.
                        "id": "{0}@{1}".format(table, form.get("connectionSelector") or "*"),
                        "table": table,
                        "connectionSelector": (form.get("connectionSelector") or "*").strip() or "*",
                        "message": (form.get("message") or "").strip(),
                        "runbookUrl": runbook or None,
                        "requiresAcknowledgement": "requiresAcknowledgement" in form,
                    }
                )
                save(annotations)
        elif self.path == "/delete":
            target = form.get("id")
            save([a for a in annotations if a.get("id") != target])

        self._redirect()

    def log_message(self, fmt, *args):
        # One line per request, so you can watch Kviklet sync.
        print("annotation-server: " + (fmt % args), flush=True)


if __name__ == "__main__":
    load()
    print("annotation-server listening on {0}".format(PORT), flush=True)
    ThreadingHTTPServer(("", PORT), Handler).serve_forever()
