#!/bin/bash
# Converts every raw SAST report (*.csv) in $SCAN_DIR into IBM Concert's native
# SAST CSV ($SCAN_DIR/concert/concert-sast.csv).
#
# Each input is routed by its header signature:
#   - known format   -> converters/<signature>.py already exists: run it (fast, free, deterministic)
#   - unknown format -> IBM Bob Shell (headless) writes converters/<signature>.py, then we run it;
#                       if the result validates the converter is kept, so next time that format
#                       is "known" and Bob is not called again.
# The merged output is always validated deterministically before exit 0.
#
# Usage: bob_sast_format.sh [scan_dir]   (default /home/itzuser/scanner; the workflow passes nothing)
set -euo pipefail
SCAN_DIR=${1:-/home/itzuser/scanner}
OUT_DIR=$SCAN_DIR/concert
OUT=$OUT_DIR/concert-sast.csv
CONV_DIR=$SCAN_DIR/converters
ENV_FILE=$SCAN_DIR/.bob.env   # BOB_API_KEY=... [BOB_TEAM_ID=...], uploaded by the workflow (0600)
# Target = the IBM Bob SAST export format, which Concert ingests as-is (data_type static_code_scan,
# scanner "concert" in its exposure-mapping.yml; a raw BOB_SEC_CONCERT_*.csv uploads fine).
HEADER='rule id,rule name,file location,severity,line no.,status,issue description,first seen time,last seen time,solution,cwe id,tool name,issue type,score'

# Bob key: from the environment (e.g. ~/.bashrc, as BOB_API_KEY or IBM's documented BOBSHELL_API_KEY)
# unless the workflow uploaded a non-empty one.
# The uploaded file is read and deleted right away, whether or not Bob ends up being needed.
BOB_API_KEY=${BOB_API_KEY:-${BOBSHELL_API_KEY:-}}; BOB_TEAM_ID=${BOB_TEAM_ID:-}
if [ -r "$ENV_FILE" ]; then
  k=$(sed -n 's/^BOB_API_KEY=//p' "$ENV_FILE"); t=$(sed -n 's/^BOB_TEAM_ID=//p' "$ENV_FILE")
  rm -f "$ENV_FILE"
  [ -n "$k" ] && BOB_API_KEY=$k
  [ -n "$t" ] && BOB_TEAM_ID=$t
  unset k t
fi
BOBSHELL_API_KEY=$BOB_API_KEY
export BOB_API_KEY BOBSHELL_API_KEY BOB_TEAM_ID

# Bob binary: a per-user npm install (~/.npm-global/bin/bob) runs as itzuser; a root-only
# install (/usr/bin/bob, as on the first bastion) needs sudo. BOB_BIN overrides both.
SUDO=()
if [ -z "${BOB_BIN:-}" ]; then
  for c in "$(command -v bob 2>/dev/null || true)" "$HOME/.npm-global/bin/bob"; do
    [ -n "$c" ] && [ -x "$c" ] && { BOB_BIN=$c; break; }
  done
fi
if [ -z "${BOB_BIN:-}" ]; then
  BOB_BIN=/usr/bin/bob
  SUDO=(sudo --preserve-env=BOB_API_KEY,BOBSHELL_API_KEY)
fi

shopt -s nullglob
INPUTS=("$SCAN_DIR"/*.csv)
[ ${#INPUTS[@]} -gt 0 ] || { echo "no *.csv SAST reports in $SCAN_DIR"; exit 4; }
mkdir -p "$OUT_DIR" "$CONV_DIR"
rm -f "$OUT" "$OUT_DIR"/part-*.csv "$OUT_DIR"/bob.log "$OUT_DIR"/last_run
NEW=()

signature() {  # normalized header -> short stable id
  python3 - "$1" <<'PY'
import csv, hashlib, sys
with open(sys.argv[1], newline="", encoding="utf-8-sig") as fh:
    header = next(csv.reader(fh), [])
print(hashlib.sha1("|".join(h.strip().lower() for h in header).encode()).hexdigest()[:12])
PY
}

run_converters() {  # run the cached converter for every input; collect the ones without one
  MISSING=()
  local i=0 f sig
  for f in "${INPUTS[@]}"; do
    i=$((i + 1)); sig=$(signature "$f")
    if [ -f "$CONV_DIR/$sig.py" ]; then
      python3 "$CONV_DIR/$sig.py" "$f" "$OUT_DIR/part-$i.csv" \
        || { echo "converter $sig.py failed on $(basename "$f")"; exit 5; }
      echo "CONVERTER $(basename "$f") -> $sig.py"
    else
      MISSING+=("$f")
    fi
  done
}

run_converters

if [ ${#MISSING[@]} -gt 0 ]; then
  [ -n "${BOB_API_KEY:-}" ] || { echo "unknown SAST format in: ${MISSING[*]##*/} - a Bob API key is needed to learn it"; exit 3; }
  TEAM_ARGS=(); [ -n "${BOB_TEAM_ID:-}" ] && TEAM_ARGS=(--team-id "$BOB_TEAM_ID")
  TASKS=""
  for f in "${MISSING[@]}"; do
    NEW+=("$CONV_DIR/$(signature "$f").py")
    TASKS+="- input $f -> write converter ${NEW[-1]}"$'\n'
  done

  PROMPT="Write Python 3 converter scripts (standard library only) that turn SAST scan reports into IBM Concert's native SAST CSV. One converter per input, exactly at these paths:
$TASKS
Read each input file first to understand its columns and values; column names, order and value formats may differ from any previous version. Each converter must run as: python3 <converter.py> <input.csv> <output.csv>, and parse with the csv module (fields may be quoted and span multiple lines).
Output: first line exactly
$HEADER
then one row per input finding, same order, none dropped or merged. This is the format of IBM Bob's own SAST export, which Concert ingests unchanged; reproduce its value conventions. Map each column from the input column with the same meaning:
- rule id: the finding/rule identifier (required).
- rule name: the short title/name of the rule or finding (required - Concert needs this column to recognise the format).
- file location: the file's full GitHub URL (https://<host>/<org>/<repo>/blob/<branch>/<path>) if the input has one, else the path inside the repository.
- severity: uppercase, one of CRITICAL, HIGH, MEDIUM, LOW, INFO (map synonyms, e.g. ERROR->HIGH, WARNING->MEDIUM, NOTE->LOW).
- line no.: start line number.
- status: the input's status text unchanged (e.g. CONFIRMED BUG).
- issue description: the description, Markdown kept as is.
- first seen time / last seen time: MM/DD/YYYY (e.g. 09/23/2026); empty if absent.
- solution: remediation text, empty if absent.
- cwe id: CWE-<number>, several joined with ' / ' (e.g. CWE-400 / CWE-821); empty if unknown.
- tool name: scanner name.
- issue type: SAST.
- score: numeric score, empty if absent.
Quote every field (csv.QUOTE_ALL), UTF-8 without BOM. Test each converter on its input, writing test output only under /tmp. Do not modify or delete any input file or anything else in $SCAN_DIR."

  echo "BOB learning ${#MISSING[@]} new format(s) with ${SUDO[*]:+sudo }$BOB_BIN: ${MISSING[*]##*/}"
  cd "$SCAN_DIR"
  # stdin from /dev/null: otherwise bob waits for EOF on the SSH session's open stdin
  # and only starts when Concert's SSH block times out and closes it.
  "${SUDO[@]}" "$BOB_BIN" run "${TEAM_ARGS[@]}" --trust --accept-license --workspace "$SCAN_DIR" --max-turns 40 --disable-mcp "$PROMPT" < /dev/null > "$OUT_DIR/bob.log" 2>&1 \
    || { echo "bob run failed (see $OUT_DIR/bob.log)"; tail -40 "$OUT_DIR/bob.log"; rm -f "${NEW[@]}"; exit 1; }
  # A root-run bob leaves root-owned files behind.
  [ ${#SUDO[@]} -eq 0 ] || sudo chown -R "$(id -u):$(id -g)" "$OUT_DIR" "$CONV_DIR"
  for c in "${NEW[@]}"; do [ -s "$c" ] || { echo "bob did not write $c"; tail -40 "$OUT_DIR/bob.log"; rm -f "${NEW[@]}"; exit 1; }; done

  run_converters
  [ ${#MISSING[@]} -eq 0 ] || { echo "still no converter for: ${MISSING[*]##*/}"; rm -f "${NEW[@]}"; exit 1; }
fi

# Merge the per-input parts (header once) and validate the result against the inputs.
set +e
python3 - "$OUT" "$HEADER" "${#INPUTS[@]}" "${INPUTS[@]}" "$OUT_DIR"/part-*.csv <<'PY'
import csv, re, sys
out, header, n = sys.argv[1], sys.argv[2].split(","), int(sys.argv[3])
inputs, parts = sys.argv[4:4 + n], sys.argv[4 + n:]
rows, errors = [], []
for p in sorted(parts, key=lambda s: int(re.search(r"part-(\d+)\.csv$", s).group(1))):
    with open(p, newline="", encoding="utf-8-sig") as fh:
        r = csv.reader(fh)
        h = next(r, [])
        if h != header:
            errors.append("%s header %r" % (p.rsplit("/", 1)[-1], h))
        rows.extend(r)
expected, repos = 0, set()
for f in inputs:
    with open(f, newline="", encoding="utf-8-sig") as fh:
        for rec in csv.DictReader(fh):
            expected += 1
            for v in rec.values():
                m = re.match(r"^(https?://[^/]+/[^/]+/[^/]+)/blob/", v or "")
                if m:
                    repos.add(m.group(1))
if len(rows) != expected:
    errors.append("%d rows, expected %d" % (len(rows), expected))
mdy = re.compile(r"^\d{2}/\d{2}/\d{4}$|^\d{4}-\d{2}-\d{2}(T[\d:.]+Z?)?$")
cwe = re.compile(r"^CWE-\d+( / CWE-\d+)*$")
for i, row in enumerate(rows, 2):
    if len(row) != len(header):
        errors.append("row %d: %d columns" % (i, len(row))); continue
    d = dict(zip(header, row))
    for k in ("rule id", "rule name", "file location"):
        if not d[k].strip():
            errors.append("row %d: empty %s" % (i, k))
    if d["severity"] not in {"BLOCKER", "CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO"}:
        errors.append("row %d: severity %r" % (i, d["severity"]))
    if d["cwe id"] and not cwe.match(d["cwe id"]):
        errors.append("row %d: cwe id %r" % (i, d["cwe id"]))
    for k in ("first seen time", "last seen time"):
        if d[k] and not mdy.match(d[k]):
            errors.append("row %d: %s %r" % (i, k, d[k]))
if errors:
    print("INVALID Concert SAST CSV: " + "; ".join(errors[:10]))
    sys.exit(2)
with open(out, "w", newline="", encoding="utf-8") as fo:
    w = csv.writer(fo, quoting=csv.QUOTE_ALL)
    w.writerow(header)
    w.writerows(rows)
if len(repos) == 1:
    print("REPO " + repos.pop())
print("OK %d findings -> %s" % (len(rows), out))
PY
rc=$?
set -e
rm -f "$OUT_DIR"/part-*.csv
if [ $rc -ne 0 ] && [ ${#NEW[@]} -gt 0 ]; then
  # Don't cache a converter Bob got wrong: the next run asks Bob again.
  rm -f "${NEW[@]}"
  echo "discarded the new converter(s) Bob wrote: output did not validate"
fi
if [ $rc -eq 0 ]; then
  # Keep every validated output: history/<timestamp>/ gets the CSV, Bob's log (if Bob ran)
  # and the list of reports it came from. The reports themselves stay in $SCAN_DIR until
  # archive_uploaded.sh moves them here after a successful upload to Concert.
  HIST_DIR=$SCAN_DIR/history/$(date +%Y%m%d-%H%M%S)
  mkdir -p "$HIST_DIR"
  cp -p "$OUT" "$HIST_DIR/"
  [ -f "$OUT_DIR/bob.log" ] && cp -p "$OUT_DIR/bob.log" "$HIST_DIR/"
  printf '%s\n' "${INPUTS[@]##*/}" > "$HIST_DIR/inputs.txt"
  echo "$HIST_DIR" > "$OUT_DIR/last_run"
  echo "HISTORY $HIST_DIR"
fi
exit $rc
