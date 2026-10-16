"""Print the HP-relevant sentences of each fetched wiki page (wt/*.txt). Scratch tool.

usage: uv run python3 extract.py [PAGEFILE...]
"""
import glob
import os
import re
import sys

KEYS = re.compile(
    r"health bar|hitpoints bar|health overlay|hp bar|scal(e|es|ed|ing) |party|team size|players in|raid level|"
    r"invocation|heal|invulnerab|immune|shield|phase|transform|timer|minutes|restor|regenerat|"
    r"\bhitpoints\b|\bhealth\b|\bhp\b|enrage|form ",
    re.I)
CUT = re.compile(r"^==+\s*(Drops|Changes|Gallery|Trivia|References|Combat Achievements|Rewards|Music|Update history|"
                 r"Loot|Uniques|Drop table|Cosmetic|Transcript|Pets?)\b", re.I | re.M)


def clean(s):
    s = re.sub(r"\[\[(?:[^|\]]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"\{\{(?:sic|plink|plinkp|Colour\|[^|]*)\|?([^}]*)\}\}", r"\1", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>|<ref[^/]*/>", "", s)
    s = re.sub(r"'''?", "", s)
    return s


def main():
    files = sys.argv[1:] or sorted(glob.glob(os.path.join(os.path.dirname(__file__), "wt", "*.txt")))
    for fn in files:
        text = open(fn).read()
        m = CUT.search(text)
        body = text[: m.start()] if m else text
        # drop infobox templates (lines starting with |)
        lines = [l for l in body.splitlines() if not l.startswith("|") and not l.startswith("{{") and not l.startswith("}}")]
        body = clean("\n".join(lines))
        sents = re.split(r"(?<=[.!?])\s+|\n", body)
        hits = [s.strip() for s in sents if KEYS.search(s) and len(s.strip()) > 25]
        print("=====", os.path.basename(fn)[:-4], "(%d hits)" % len(hits))
        for s in hits[:40]:
            print(" -", s[:400])


if __name__ == "__main__":
    main()
