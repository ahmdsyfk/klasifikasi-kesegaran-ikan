"""Klien REST Supabase minimal (stdlib saja) untuk skrip peneliti di laptop.

Kredensial dari variabel lingkungan, JANGAN ditulis ke berkas/commit:
  SUPABASE_URL          https://<ref>.supabase.co
  SUPABASE_SECRET_KEY   kunci secret (sb_secret_...) atau service_role lama; melewati RLS.
                        Nama lama SUPABASE_SERVICE_ROLE_KEY juga diterima.
"""

from __future__ import annotations

import json
import os
import urllib.error
import urllib.parse
import urllib.request


class Supabase:
    def __init__(self, url: str | None = None, key: str | None = None, timeout: int = 300):
        self.url = (url or os.environ.get("SUPABASE_URL", "")).rstrip("/")
        self.key = key or os.environ.get("SUPABASE_SECRET_KEY") or os.environ.get(
            "SUPABASE_SERVICE_ROLE_KEY", ""
        )
        if not self.url or not self.key:
            raise SystemExit("Set SUPABASE_URL dan SUPABASE_SECRET_KEY di environment terlebih dahulu.")
        self.timeout = timeout

    def _headers(self, extra: dict | None = None) -> dict:
        h = {"apikey": self.key}
        if not self.key.startswith("sb_"):  # kunci JWT lama juga dikirim sebagai Bearer
            h["Authorization"] = f"Bearer {self.key}"
        return {**h, **(extra or {})}

    def request(self, method: str, path: str, body: bytes | None = None,
                headers: dict | None = None) -> bytes:
        req = urllib.request.Request(f"{self.url}{path}", data=body, method=method,
                                     headers=self._headers(headers))
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            raise RuntimeError(f"{method} {path} -> {e.code}: {e.read()[:500]!r}") from None

    # ---- PostgREST
    def select(self, table: str, params: dict) -> list[dict]:
        q = urllib.parse.urlencode(params)
        return json.loads(self.request("GET", f"/rest/v1/{table}?{q}"))

    def select_all(self, table: str, params: dict, page: int = 1000) -> list[dict]:
        rows, offset = [], 0
        while True:
            batch = self.select(table, {**params, "limit": page, "offset": offset})
            rows += batch
            if len(batch) < page:
                return rows
            offset += page

    def upsert(self, table: str, rows: list[dict] | dict) -> None:
        self.request("POST", f"/rest/v1/{table}", json.dumps(rows).encode(),
                     {"Content-Type": "application/json",
                      "Prefer": "resolution=merge-duplicates,return=minimal"})

    def update(self, table: str, match: dict, values: dict) -> None:
        q = urllib.parse.urlencode({k: f"eq.{v}" for k, v in match.items()})
        self.request("PATCH", f"/rest/v1/{table}?{q}", json.dumps(values).encode(),
                     {"Content-Type": "application/json", "Prefer": "return=minimal"})

    # ---- Storage
    def download(self, bucket: str, path: str) -> bytes:
        return self.request("GET", f"/storage/v1/object/{bucket}/{urllib.parse.quote(path)}")

    def upload(self, bucket: str, path: str, data: bytes, content_type: str) -> None:
        self.request("POST", f"/storage/v1/object/{bucket}/{urllib.parse.quote(path)}", data,
                     {"Content-Type": content_type, "x-upsert": "true"})
