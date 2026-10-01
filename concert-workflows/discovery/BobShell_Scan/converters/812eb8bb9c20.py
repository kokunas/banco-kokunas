#!/usr/bin/env python3
"""Converter for the IBM Bob SAST export (BOB_SEC_CONCERT_*.csv, Sep 2026 format).
That export already is the target format - Concert ingests it unchanged - so this only
re-emits it in the canonical column order, fully quoted, with severity upper-cased.
Usage: python3 <this> <input.csv> <output.csv>"""
import csv, sys

HEADER = ["rule id", "rule name", "file location", "severity", "line no.", "status", "issue description",
          "first seen time", "last seen time", "solution", "cwe id", "tool name", "issue type", "score"]

with open(sys.argv[1], newline="", encoding="utf-8-sig") as fi, open(sys.argv[2], "w", newline="", encoding="utf-8") as fo:
    w = csv.writer(fo, quoting=csv.QUOTE_ALL)
    w.writerow(HEADER)
    for r in csv.DictReader(fi):
        r["severity"] = r["severity"].strip().upper()
        w.writerow([r.get(c, "") for c in HEADER])
