"""Fetch OSRS Wiki wikitext and infobox_monster bucket rows for boss pages. Scratch tool for the boss-HP research.

usage: uv run python3 wiki.py wikitext PAGE...     -> wt/<PAGE>.txt
       uv run python3 wiki.py bucket PAGE...       -> prints infobox_monster rows (hitpoints etc.)
"""
import json
import os
import sys
import time
import urllib.parse
import urllib.request

API = "https://oldschool.runescape.wiki/api.php"
UA = "Foe-RuneLite-plugin boss-HP research (one-off, run by hand; repo fevra-dev/Foe)"
HERE = os.path.dirname(os.path.abspath(__file__))


def get(params):
    url = API + "?" + urllib.parse.urlencode(params)
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def wikitext(page):
    d = get({"action": "parse", "page": page, "prop": "wikitext", "format": "json", "redirects": 1})
    if "error" in d:
        return None, d["error"].get("info")
    return d["parse"]["wikitext"]["*"], d["parse"]["title"]


def bucket(page):
    q = ('bucket("infobox_monster").select("page_name","version_anchor","id","hitpoints","combat_level")'
         '.where("page_name",%s).limit(500).run()' % json.dumps(page))
    return get({"action": "bucket", "format": "json", "query": q}).get("bucket")


def main():
    mode, pages = sys.argv[1], sys.argv[2:]
    os.makedirs(os.path.join(HERE, "wt"), exist_ok=True)
    for p in pages:
        if mode == "wikitext":
            text, title = wikitext(p)
            if text is None:
                print("MISSING", p, title)
            else:
                fn = os.path.join(HERE, "wt", p.replace("/", "_").replace(" ", "_") + ".txt")
                with open(fn, "w") as f:
                    f.write(text)
                print("OK", p, "->", title, len(text))
        else:
            rows = bucket(p)
            for r in rows or []:
                print(p, "|", r.get("version_anchor", ""), "|", ",".join(r.get("id", [])), "|", r.get("hitpoints"),
                      "|", r.get("combat_level"))
            if not rows:
                print(p, "| NO ROWS")
        time.sleep(0.5)


if __name__ == "__main__":
    main()
