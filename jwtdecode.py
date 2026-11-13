#!/usr/bin/env python3
"""Decode JWTs locally. Does NOT verify signatures.

Usage:
  jwtdecode.py <token> [<token> ...]
  echo <token> | jwtdecode.py
  pbpaste | jwtdecode.py              # token, "Bearer <token>", or a JSON blob
  jwtdecode.py -f response.json       # JSON file containing tokens
  jwtdecode.py --raw <token>          # JSON only, for piping into jq
"""
import argparse
import base64
import datetime as dt
import json
import re
import sys

TIME_CLAIMS = ("iat", "nbf", "exp", "auth_time")
JWT_RE = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*")


def b64json(part):
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def find_tokens(text):
    """Return [(label, token)] from a bare token, 'Bearer x', or JSON with tokens inside."""
    text = text.strip()
    try:
        data = json.loads(text)
    except ValueError:
        data = None
    found = []

    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, f"{path}.{k}" if path else k)
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, f"{path}[{i}]")
        elif isinstance(node, str) and JWT_RE.fullmatch(node):
            found.append((path or "token", node))

    if data is not None:
        walk(data, "")
    else:
        for i, m in enumerate(JWT_RE.findall(text)):
            found.append((f"token{i + 1}" if i else "token", m))
    return found


def fmt_time(ts, now):
    when = dt.datetime.fromtimestamp(ts, dt.timezone.utc)
    delta = int(ts - now.timestamp())
    rel = f"in {fmt_span(delta)}" if delta >= 0 else f"{fmt_span(-delta)} ago"
    return f"{when.isoformat()}  ({rel})"


def fmt_span(s):
    d, s = divmod(s, 86400)
    h, s = divmod(s, 3600)
    m, s = divmod(s, 60)
    parts = [f"{n}{u}" for n, u in ((d, "d"), (h, "h"), (m, "m"), (s, "s")) if n]
    return "".join(parts) or "0s"


def show(label, token, raw):
    parts = token.split(".")
    try:
        header, payload = b64json(parts[0]), b64json(parts[1])
    except Exception as e:
        print(f"{label}: not a decodable JWT ({e})", file=sys.stderr)
        return False
    if raw:
        print(json.dumps({"header": header, "payload": payload}))
        return True

    now = dt.datetime.now(dt.timezone.utc)
    print(f"===== {label}")
    print("header:")
    print(json.dumps(header, indent=2))
    print("payload:")
    print(json.dumps(payload, indent=2))

    times = [(k, payload[k]) for k in TIME_CLAIMS if isinstance(payload.get(k), (int, float))]
    if times:
        print("times:")
        for k, v in times:
            print(f"  {k:<9} {fmt_time(v, now)}")
    exp = payload.get("exp")
    if isinstance(exp, (int, float)):
        print("status:", "EXPIRED" if exp < now.timestamp() else "valid (not expired)")
    if header.get("alg") == "none":
        print("warning: alg=none, token is unsigned")
    print(f"signature: {len(parts[2]) if len(parts) > 2 else 0} chars, NOT verified")
    print()
    return True


def main():
    ap = argparse.ArgumentParser(description="Decode JWTs locally (no signature verification).")
    ap.add_argument("tokens", nargs="*", help="token(s), or omit to read stdin")
    ap.add_argument("-f", "--file", help="read text/JSON from file")
    ap.add_argument("--raw", action="store_true", help="print compact JSON only")
    args = ap.parse_args()

    if args.file:
        text = open(args.file).read()
    elif args.tokens:
        text = "\n".join(args.tokens)
    elif not sys.stdin.isatty():
        text = sys.stdin.read()
    else:
        ap.error("give a token, pipe one in, or use -f FILE")

    tokens = find_tokens(text)
    if not tokens:
        sys.exit("no JWT found in input")
    ok = [show(label, tok, args.raw) for label, tok in tokens]
    sys.exit(0 if all(ok) else 1)


if __name__ == "__main__":
    main()
