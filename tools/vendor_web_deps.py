#!/usr/bin/env python3
"""
Bundle the web libraries the game loads from CDNs so it runs fully offline.

The game page (app/src/main/assets/www/index.html, plus the arena game embedded inside it)
loads three.js, the Tailwind runtime and Google Fonts from the internet. This script finds those
URLs, downloads each file into app/src/main/assets/vendor/files/ and writes vendor/manifest.json.
MainActivity answers the page's CDN requests from those files, so the page itself stays
byte-for-byte unchanged while working without a connection.

Run automatically by codemagic.yaml. To run it by hand:  python3 tools/vendor_web_deps.py

If a download fails the app still works (it falls back to the CDN when online); use --strict to make
the build fail instead.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_HTML = ROOT / "app/src/main/assets/www/index.html"
OUT_DIR = ROOT / "app/src/main/assets/vendor"

# A current Chrome-on-Android UA makes Google Fonts answer with woff2 files.
USER_AGENT = (
    "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/126.0.0.0 Mobile Safari/537.36"
)

HOST_TAILWIND = "cdn.tailwindcss.com"
HOSTS = ("cdn.jsdelivr.net", HOST_TAILWIND, "fonts.googleapis.com")
URL_RE = re.compile(r"https?://(?:cdn\.jsdelivr\.net|cdn\.tailwindcss\.com|fonts\.googleapis\.com)[^\s\"'<>)\\]*")

# Always fetched, even if discovery in the HTML finds nothing (e.g. the page was changed).
BASELINE_URLS = [
    "https://cdn.tailwindcss.com/",
    "https://fonts.googleapis.com/css2?family=Cinzel:wght@600;700;800;900&family=Inter:wght@400;500;600&display=swap",
    "https://fonts.googleapis.com/css2?family=Cinzel:wght@600;800&display=swap",
    "https://cdn.jsdelivr.net/npm/three@0.160.0/build/three.module.js",
    "https://cdn.jsdelivr.net/npm/three@0.160.0/examples/jsm/loaders/GLTFLoader.js",
    "https://cdn.jsdelivr.net/npm/three@0.147.0/build/three.min.js",
    "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/loaders/GLTFLoader.js",
    "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/utils/SkeletonUtils.js",
    "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/loaders/FBXLoader.js",
    "https://cdn.jsdelivr.net/npm/three@0.147.0/examples/js/libs/fflate.min.js",
]

FROM_RE = re.compile(r"""\bfrom\s*['"]([^'"]+)['"]""")
BARE_IMPORT_RE = re.compile(r"""\bimport\s*['"]([^'"]+)['"]""")
DYN_IMPORT_RE = re.compile(r"""\bimport\s*\(\s*['"]([^'"]+)['"]\s*\)""")
CSS_URL_RE = re.compile(r"url\(\s*['\"]?([^'\")\s]+)['\"]?\s*\)")


def norm(url: str) -> str:
    """Same normalisation as MainActivity.normalize(): no fragment, host-only URLs get a '/'."""
    p = urllib.parse.urlsplit(url)
    path = p.path or "/"
    return f"{p.scheme}://{p.netloc}{path}" + (f"?{p.query}" if p.query else "")


def keep(url: str) -> bool:
    """Skip directory prefixes such as the import-map entry '.../examples/jsm/' and bare hosts."""
    p = urllib.parse.urlsplit(url)
    if p.netloc == HOST_TAILWIND:
        return True
    return bool(p.path) and not p.path.endswith("/")


def discover(html_path: Path) -> list[str]:
    found: list[str] = []
    try:
        text = html_path.read_text(encoding="utf-8", errors="ignore")
    except OSError as exc:
        print(f"  ! cannot read {html_path}: {exc}")
        return found
    for m in URL_RE.finditer(text):
        url = m.group(0).rstrip(".,;")
        if keep(url):
            found.append(url)
    return found


def fetch(url: str, attempts: int = 3) -> tuple[bytes, str]:
    last: Exception | None = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return resp.read(), resp.headers.get("Content-Type", "")
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            last = exc
            time.sleep(1.5 * (i + 1))
    raise RuntimeError(f"{last}")


def mime_for(url: str, content_type: str) -> str:
    p = urllib.parse.urlsplit(url)
    path = p.path.lower()
    if p.netloc == HOST_TAILWIND or path.endswith((".js", ".mjs")):
        return "text/javascript"
    if path.endswith(".css") or (p.netloc == "fonts.googleapis.com" and "/css" in path):
        return "text/css"
    if path.endswith(".woff2"):
        return "font/woff2"
    if path.endswith(".woff"):
        return "font/woff"
    if path.endswith(".ttf"):
        return "font/ttf"
    return content_type.split(";")[0].strip() or "application/octet-stream"


def ext_for(url: str) -> str:
    suffix = Path(urllib.parse.urlsplit(url).path).suffix.lower()
    return suffix if re.fullmatch(r"\.[a-z0-9]{1,6}", suffix or "") else ".bin"


def module_imports(source: str) -> list[str]:
    refs = FROM_RE.findall(source) + BARE_IMPORT_RE.findall(source) + DYN_IMPORT_RE.findall(source)
    return [r for r in refs if r.startswith(".")]


def run(html: Path, strict: bool) -> int:
    files_dir = OUT_DIR / "files"
    if files_dir.exists():
        shutil.rmtree(files_dir)
    files_dir.mkdir(parents=True, exist_ok=True)

    queue: list[str] = []
    seen: set[str] = set()

    def enqueue(url: str) -> None:
        n = norm(url)
        if n not in seen:
            seen.add(n)
            queue.append(n)

    print(f"Scanning {html} for CDN references ...")
    for u in discover(html) + BASELINE_URLS:
        enqueue(u)

    entries: list[dict] = []
    failures: list[str] = []

    while queue:
        url = queue.pop(0)
        try:
            data, ctype = fetch(url)
        except RuntimeError as exc:
            print(f"  FAIL {url}  ({exc})")
            failures.append(url)
            continue

        mime = mime_for(url, ctype)
        name = hashlib.sha1(url.encode("utf-8")).hexdigest()[:16] + ext_for(url)
        (files_dir / name).write_bytes(data)
        entries.append({"url": url, "file": f"files/{name}", "mime": mime})
        print(f"  ok   {url}  ({len(data):,} bytes)")

        p = urllib.parse.urlsplit(url)
        text = data.decode("utf-8", errors="ignore") if mime in ("text/javascript", "text/css") else ""
        if mime == "text/css":
            for ref in CSS_URL_RE.findall(text):
                if ref.startswith("//"):
                    ref = "https:" + ref
                if ref.startswith("https://"):
                    enqueue(ref)
        elif mime == "text/javascript" and p.netloc == "cdn.jsdelivr.net" and (
            "/examples/jsm/" in p.path or p.path.endswith(".module.js")
        ):
            for ref in module_imports(text):
                enqueue(urllib.parse.urljoin(url, ref))

    entries.sort(key=lambda e: e["url"])
    (OUT_DIR / "manifest.json").write_text(
        json.dumps({"version": 1, "entries": entries}, indent=1), encoding="utf-8"
    )

    print(f"\nBundled {len(entries)} file(s) into {OUT_DIR}")
    if failures:
        print(f"WARNING: {len(failures)} download(s) failed. The app will fetch those from the CDN when online:")
        for u in failures:
            print(f"  - {u}")
        return 1 if strict else 0
    print("All web libraries bundled: the game will work offline.")
    return 0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--html", type=Path, default=DEFAULT_HTML, help="game page to scan for CDN URLs")
    ap.add_argument("--strict", action="store_true", help="exit with an error if any download fails")
    args = ap.parse_args()
    return run(args.html, args.strict)


if __name__ == "__main__":
    sys.exit(main())
