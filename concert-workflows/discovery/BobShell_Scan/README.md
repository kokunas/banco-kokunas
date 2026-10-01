# BobShell Scan - prueba epr - reformat SAST reports with IBM Bob, import into Concert

Connects to the Linux bastion over SSH, runs **IBM Bob Shell** headless
(`bob run`, see "Bob binary" below) to convert every raw
SAST report in `/home/itzuser/scanner/` into IBM Concert's native SAST CSV,
fetches the CSV and uploads it to Concert as `data_type: "static_code_scan"`.

The conversion script lives **on the bastion** at
`/home/itzuser/scanner/bob_sast_format.sh` (copy in this folder); the workflow
only invokes it by its bare path over `Common/SSH` (which can only run a
single-word command - see [Trivy_SSH_Host_Scan](../Trivy_SSH_Host_Scan)).
Edit the script on the host, not in the workflow.

## Blocks

1. `UploadBobEnv` (SFTP Put, 0600) - writes `~/scanner/.bob.env` with
   `BOB_API_KEY=<bob_api_key input>`. The script reads and deletes it right
   away, and uses it only if it is non-empty (otherwise the bastion's own
   `BOB_API_KEY`). `bob run` refuses to start without a key
   (`Error: Bob API key is required`, confirmed live).
2. `RunBob` (SSH, `timeout: 600`) - runs the script: Bob writes
   `~/scanner/concert/concert-sast.csv`, the script validates the header.
3. `CheckBobResult` / `FetchConcertCsv` (SFTP Get) / `BuildUpload` - checks the
   exit code, re-validates the header, builds the upload metadata.
4. `IngestSast` (`Upload Files to Concert`, `fileType: csv`) - uploads the CSV.
   The content goes in as `"filename": $sast_csv` (a plain string). Until
   2026-10-01 it was `[$sast_csv]`, which the block turns into a 1-byte
   file: every upload was accepted (`202`) and then failed processing with
   `ERR_FILE_PARSING_1`, so nothing ever showed up in Concert. Fixed and
   confirmed live: 102 exposures processed.
5. `ArchiveUploaded` (SSH) runs [archive_uploaded.sh](archive_uploaded.sh)
   once Concert has accepted the upload, and `CheckArchive` checks it.
   `result` carries `script_output` (the script's lines:
   `CONVERTER`/`BOB learning`, `REPO`, `OK <n> findings`), `findings`, the
   upload response and `archive_output`.

Every run uploads: there is no test mode. Simplified 2026-10-01: removed
the `modo` (`prueba`/`subir`) dropdown and the `bob_team_id` input (only
needed for Bob keys of type `general`; set `BOB_TEAM_ID` on the bastion
instead).

Target CSV = **IBM Bob's own SAST export format**, which Concert ingests
unchanged (a raw `BOB_SEC_CONCERT_*.csv` uploads fine as `static_code_scan`,
confirmed by the user 2026-10-01):
```
rule id,rule name,file location,severity,line no.,status,issue description,first seen time,last seen time,solution,cwe id,tool name,issue type,score
```
Value conventions kept as in that export: `file location` = full GitHub
`/blob/` URL, `status` as given (e.g. `CONFIRMED BUG`), dates `MM/DD/YYYY`,
`cwe id` like `CWE-400 / CWE-821`, `issue type` = `SAST`. Concert recognises
the CSV by column names (`ibm-roja-pipeline`
`/app/src/mapperlib/yamls/exposure-mapping.yml`, `static_code_scan.concert`):
every mapped column must be present - `tool name, rule id, rule name, cwe id,
severity, file location, issue description, status, issue type, solution,
score, first seen time` - otherwise the upload is accepted (`202`) but the
`scan_processing_job` fails 3x with exit 1 (`ERR_FILE_PARSING_1`) and nothing
appears in the UI (seen live 2026-10-01, when the converter dropped `rule name`).

An earlier version "normalised" values (path instead of URL, `OPEN`, ISO
dates, `VULNERABILITY`, digits-only CWE). None of that is needed, and the CWE
rule was wrong: `CWE-400 / CWE-821` became `400821`.

## Credentials

Hard-coded by name in the blocks, like the original "BobShell Scan":
`"concertuser/Bastion"` (SSH - host `10.10.10.201`, port `22`, itzuser; that
is the bastion's `eth1` on the cluster network `10.10.10.0/24`, where the API
`10.10.10.249` and apps `10.10.10.250` VIPs live - `10.0.2.2`/`eth0` is the
external NAT side) in UploadBobEnv/RunBob/FetchConcertCsv/ArchiveUploaded, and
`"concertuser/Concert"` (IBM Concert API Key) in IngestSast. On the older
`concert-dataapps` instance the user was `ibmconcert` (and the SSH host
`192.168.252.2`). Exposing them as `authType: "ANY"` input
variables made the validator report one "Authentication reference not found
... used in _start" per referencing block (4), even with valid defaults.

## Inputs

| Input | Required | Notes |
|---|---|---|
| `bob_api_key` | no | IBM Bob API key for headless mode. Only needed when a report has an unknown format; empty = the bastion's `BOB_API_KEY`. |
| `repo_url` / `repo_name` | no | Concert **requires both** for `static_code_scan` (`400 The following fields are required in metadata: repo_url, repo_name`, seen live 2026-10-01). Empty `repo_url` = taken from the report's GitHub `/blob/` URLs; empty `repo_name` = last segment of `repo_url`. No repo at all -> `BuildUpload` fails before calling Concert. |
| `application_name` / `application_version` | no | optional upload metadata, empty by default |

## History on the bastion

```
~/scanner/
  *.csv                         reports waiting to be converted/uploaded
  concert/concert-sast.csv      output of the latest run (overwritten every run)
  concert/last_run              path of the latest run's history folder
  history/<YYYYmmdd-HHMMSS>/    one folder per validated run
    concert-sast.csv            the CSV that run produced
    bob.log                     only if Bob ran
    inputs.txt                  reports it was built from
    inputs/                     those reports, moved here after a successful upload
    UPLOADED                    UTC time of the upload (absent = not uploaded)
```

`bob_sast_format.sh` writes the history folder on every `OK` run; the reports
stay in `~/scanner/` until the upload has worked.
`archive_uploaded.sh` (called by the workflow after `IngestSast` succeeds) moves the reports listed in `last_run` into
`inputs/` and writes `UPLOADED`, so the next run does not upload them again
(with no reports left the next run stops with `exit 4`, as intended).

## Format routing: cached converters, Bob only for new formats

[bob_sast_format.sh](bob_sast_format.sh) routes every `*.csv` in `scanner/` by a
signature of its header (sha1 of the normalized column names, 12 chars):

- **Known format** - `scanner/converters/<signature>.py` exists: run it. No Bob,
  no key, milliseconds, deterministic. Today's IBM Bob export
  (`BOB_SEC_CONCERT_*.csv`) is [converters/812eb8bb9c20.py](converters/812eb8bb9c20.py).
- **Unknown format** (columns renamed/reordered/added) - Bob is asked to *write*
  `converters/<signature>.py` for it; the script runs that converter itself. If
  the merged output validates, the converter is kept, so the next run of that
  format is "known". If it doesn't, the new converter is deleted.
- **Always** - the merged output is validated with Python's `csv` module (exact
  header, same row count as the inputs, non-empty rule id/rule name/file
  location, valid severity, `CWE-<n>[ / CWE-<n>]` CWE, `MM/DD/YYYY` or ISO
  dates) before exit 0; on failure nothing reaches Concert (exit 2).

Bob key: `BOB_API_KEY` (or IBM's documented `BOBSHELL_API_KEY`, plus
`BOB_TEAM_ID` for `general` keys) from `itzuser`'s environment on the
bastion (e.g. `export BOB_API_KEY=...` in `~/.bashrc`), overridden by a
non-empty `bob_api_key` workflow input; the script exports it under both
names. Without a key, an unknown format fails with exit 3 and a clear
message; known formats never need it.

Bob binary: `$BOB_BIN` if set, else `bob` on `PATH` or
`~/.npm-global/bin/bob` (per-user npm install, run as `itzuser`, no sudo),
else `sudo /usr/bin/bob` (root-only install, as on the first bastion; the
output is then chowned back to `itzuser`). To set up a new bastion as
`itzuser`: `npm config set prefix ~/.npm-global`, then
`curl -fsSL https://bob.ibm.com/download/bobshell.sh | bash -s -- --pm npm`
(needs Node >= 22.15, on RHEL 9 `sudo dnf module install -y nodejs:22`), and
copy this script plus `converters/` to `~/scanner/`.

The cached converter for the Bob export ([converters/812eb8bb9c20.py](converters/812eb8bb9c20.py))
is therefore a pass-through: canonical column order, every field quoted,
severity upper-cased - verified on the Android (102) and iOS (60) reports
with zero value differences against the input. Bob is only needed to map
*other* tools' reports into this format.

Tested live on the bastion (2026-09-30): known format -> `OK 102 findings`;
a 10-row copy with every column renamed and reordered -> Bob wrote a new
converter (53 s, cost 0.275) whose output was identical, column by column, to
the reference converter; a deliberately broken converter -> exit 2.

## Bugs found live (2026-09-30)

- **SSH timeout on `api...:40222`**: that NAT port only exists from outside.
  From Concert's pods the bastion is `192.168.252.2:22` - set that in the SSH credential.
- **`All configured authentication methods failed`**: wrong password in the
  credential (sshd logged `password check failed` from the Concert node).
- **`Response timeout` after 600s**: `bob run` waits for EOF on stdin, which
  the SSH session keeps open - Bob only received the prompt when Concert
  closed the channel at the timeout, then finished in 45s. Fixed with
  `< /dev/null`. Bob's output from that run passed the validator
  (`OK 102 findings`).

## Verified vs. not verified

- Verified live: bob is callable via `sudo /usr/bin/bob` from `itzuser`
  (passwordless sudo), the script's missing-key path (`exit 3`). On the
  `itz-r87cnx` bastion (2026-09-30, per-user npm install) the script found
  `~/.npm-global/bin/bob`, ran it without sudo, and a fake key failed with
  `Invalid or expired API key` (exit 1, no converter kept). The
  validator was run on the host against a plain-Python reference conversion
  of the real report (`OK 102 findings`, repo detected) and against a
  broken copy (`CWE-312` left in -> `exit 2`). `BuildUpload` JS executed in
  Node with a quoted, multi-line CSV.
- **Not verified**: the Bob conversion itself (needs a Bob API key), and
  the `static_code_scan` + CSV + `scanner_name: concert` upload (see the
  format notes above - a raw Bob export uploads fine).

## Where it lives

Imported (2026-09-30) as `concertuser/User/BobShell Scan - prueba epr` on
`concert-concert.apps.itz-r87cnx.pok-lb.techzone.ibm.com` (the Concert of the
same reservation as the bastion) via `POST /workflows/api/v1/flows/concertuser/import`
with a zip holding `BobShell Scan - prueba epr.json` at its root. It was first
built on `concert-dataapps.apps.6a9803d15d5e7a7f942bde16...` as `ibmconcert/User/...`.
