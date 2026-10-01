# 01c - Scan a remote Linux host: Trivy SSH Host Scan

Scans a **live remote Linux host reached over SSH** with Trivy instead of
a git repo or a container image - useful for OS-level CVE detection on a
VM that isn't containerized, rather than the Java application dependency
scanning the rest of this repo's workflows focus on.

**Rebuilt to match IBM's own published workflow patterns** (found in
[`IBM/Concert`'s `concert-workflows/Vulnerability`](https://github.com/IBM/Concert/tree/main/concert-workflows/Vulnerability) -
specifically `RHEL_SCAN_OSCAP_GRYPE`, `Test_Connection_to_RHEL`,
`Fetch_Scan_files_from_AWS_Inspector`):

- `Common/SFTP` (Put/Get File) to transfer the actual scan script and its
  output - `Common/SSH`'s `command` field turned out to only support a
  bare single word in this Concert install (see below), so SFTP carries
  everything that isn't a one-word command.
- `Common/SSH` to invoke the uploaded script by its bare path.
- A plain **JS `function` block** for building the upload payload - real,
  visible JavaScript in Concert's editor, not a hidden Python sandbox
  (confirmed this really is plain JS, not JSONata, directly from IBM's own
  examples: `Object.keys()`, template literals, `.map()`/`.filter()`).
- The **official ingestion block**,
  `system/IBM/Concert v2/Import Data/Upload Files to Concert`.

## Credentials: created under the Workflows app's own settings, not Concert's main Administration menu

Both `ssh_auth` and `concert_auth` are stored credentials, created from
**inside the Workflows app itself** (not Administration -> Integrations ->
Connections, which is a different, unrelated list with no SSH/generic
option). Concretely, for this demo:

- `ssh_auth`: credential type **SSH** (host/port/username/private key of
  the target).
- `concert_auth`: credential type **IBM Concert API Key** - fields:
  `protocol` (`https`), `host` (the Concert console hostname, no
  `https://` prefix), `api key`, `api key type` (`C_API_KEY`),
  `instance id` (`0000-0000-0000-0000`).

Reference each by the name you gave it when creating them.

## A real bug found live: `vm_scan` needs Concert's own CSV shape, not raw Trivy JSON/CycloneDX

The first working version of this rebuild uploaded the patched CycloneDX
scan directly with `data_type: "vm_scan"`. Concert accepted it
(`202`, real CVE Findings showed up under the application), **but Arena
view couldn't distinguish these from application-code CVEs** - no way to
tell OS vs. app findings apart without opening each one individually.

Digging into IBM's own sample data
(`use-case-data-files/*/vulnerabilities/vm_scan/` in the `IBM/Concert`
repo) showed why: `vm_scan` ingestion is scanner-specific, keyed by the
`scanner_name` in the upload metadata - recognized values are
`aws_inspector`, `nessus`, `qualys`, and a **native `concert` CSV format**
(`vm_scan_concert_sample.csv`). `"trivy"` isn't one of them, so the
ingestion fell back to something that stores flat findings without
properly attributing them to a host resource.

Fixed by having `BuildVmScanCsv` convert Trivy's JSON scan into that
native CSV shape instead:
```
CVE,Host IPAddress,Package,Package Version,Package Path,severity,Score,hasFix,Fixed Version,Description,Host Name
```
with `metadata: {"scanner_name": "concert"}` and `fileType: "csv"`.

## A second real bug found live: the CSV alone doesn't generate a native `OS`-type action

The CSV fix above got a real CVE (`CVE-2023-45853` on a deliberately
downgraded `zlib`, confirmed live) accepted and correctly attributed to
the host - but no native `OS`-type auto-remediation action was generated,
even after separately importing and running
[Sync_AWS_Linux_Bulletin](../Sync_AWS_Linux_Bulletin) (which confirmed
`37260` advisories inserted, including this exact CVE).

Root cause, found in IBM's own documentation
(`ibm.com/docs` -> IBM Concert -> "Configuring auto-remediation
workflows"): **"Concert generates remediation actions only when the
operating system name provided in the uploaded SBOM matches Concert's
expected OS identifiers"** - for Amazon Linux, that value must be exactly
`"amazon linux"`. Checked the official `vm_scan` API documentation
("Uploading data via API") directly: **the only documented metadata field
for `vm_scan` is `scanner_name`** - there is no OS field anywhere in the
CSV format or its upload metadata. The OS identifier has to come from a
**separate ConcertDef deploy SBOM** that registers the host as a
`vm`-type runtime resource.

**Corrected 2026-10-01.** The first version of this step was
reverse-engineered from the toolkit's *config* template
(`deploy-sbom-values.yaml.template`: `concert.deployments[].runtime[]`,
`properties.os`, `network.ipv4_addrs`). That is the toolkit's input, not a
ConcertDef document: it fails all of IBM's published schemas (no
`bomFormat`/`specVersion`/`metadata`) and Concert's own validator rejects it
(`POST /core/api/v1/validate_concertdef_sbom` -> `400
sbom_validation_extracting_metadata`), so the upload never registered the OS.

The current shape follows IBM's
[ConcertDef-1.0.2-Schema-deploy.json](https://github.com/IBM/Concert/tree/main/toolkit-enablement/concert-utils/concertdef_schema)
and the [deploy sample](https://github.com/IBM/Concert/tree/main/concert-concertdef-samples)
(`runtime-components`, `ipv4: [{addr}]`), validates against the 1.0.2 and
2.0.0.1 schema files, and Concert's validator answers `200 The SBOM was
validated successfully`:
```json
{
  "bomFormat": "ConcertDef", "specVersion": "1.0.2",
  "metadata": {"timestamp": "...", "type": "deploy", "environment": "<environment_target>",
               "component": {"name": "<app>", "version": "<ver>", "deploy-number": "1"}},
  "runtime-components": [{
    "bom-ref": "vm:<host>", "type": "vm", "name": "<host>", "hostname": "<host>",
    "ipv4": [{"addr": "<host>"}],
    "properties": [{"name": "os", "value": "amazon linux"}, {"name": "version", "value": "2023"}],
    "components": [{"type": "library", "name": "<app>", "version": "<ver>", "purl": "pkg:generic/<app>@<ver>"}]
  }],
  "dependencies": [{"ref": "vm:<host>", "dependsOn": ["library:<app>"]}]
}
```
Why the OS goes in name/value `properties`: Concert's VM auto-remediation
(`ibm-roja-pipeline`, `src/vulnlib/remediations/utils/remediation_vm.py`)
reads each host's resource metadata as `{name: value}` pairs, takes `os_family`
or a normalised `os`, plus `version`, matches the host by IP, and skips hosts
missing either ("missing os name or os version"). The schema lists only
`vm_id` as a documented property name, but it accepts any name/value pair.
The schema also requires at least one `components` entry on a VM, hence the
`library` for the application.

`BuildDeploySbom` builds this JSON directly (`os_name`/`os_version`
inputs, defaulting to `"amazon linux"`/`"2023"`; `target_host` should be the
same IP the `vm_scan` CSV uses, since that is how Concert links the two), and `UploadDeploySbom`
pushes it via the same
`system/IBM/Concert v2/Import Data/Upload Files to Concert` block used
for the CSV, with `data_type: "application_sbom"` (per IBM's docs, this
single `data_type` covers Application, Build, *and* Deploy SBOMs - the
distinction is only in the JSON's own internal shape). Runs **before**
the `vm_scan` CSV upload, so the host is registered with its OS before
CVE data gets attached to it.

## A third real bug found live: `Common/SSH` chokes on `for` loops / `;`-separated commands

After fixing the timeout, the run kept failing with
`bash: line 1: for i in 1 2 3 4 5; do sudo trivy ...: No such file or
directory` - bash's "No such file or directory" (as opposed to "command
not found") is specifically what it prints when it tries to look up a
command name containing a `/` as a literal path, meaning the whole
string was being treated as one filename to execute rather than as shell
code to parse. First suspected this was real newlines getting flattened
to literal `\n` (single-lining the command with `;` looked like it should
fix it) - **it didn't**: the exact same error recurred with a genuinely
single-line command.

Isolated it by running the identical command string directly over SSH
from outside Concert entirely (bypassing the SSH block, same target
host, same key): it worked perfectly, first try, full valid output. That
conclusively proves the command text itself was never the problem - the
bug is in how Concert's `Common/SSH` block specifically invokes
multi-statement commands. `InstallTrivy` (`A || (B | C)` - no `;`, no
`for`) never failed; `RunScan` (`for ... ; do ... ; done`) always did.

**Fixed the `Common/SSH` command itself by chaining with `&&` only**
(`trivy ... && cat ... && sudo rm -f ...`) instead of `for`/`;` - matches
the one operator style (`||`/`|`/subshells `(...)`, now also `&&`)
confirmed to work through this block.

**The retry logic moved out of the shell entirely, into native Concert
control blocks** - the right fix, not just a workaround: a shell-level
`for` loop was never the correct way to retry inside a single `Common/SSH`
call anyway, since `Common/SSH` is meant for one command execution and
Concert's own engine already has loop/branch primitives for exactly this.
Found the proven pattern in IBM's own `Fetch_Scan_files_from_AWS_Inspector`
workflow, which polls a report-generation status with this exact
combination: a `while` block (`condition`, nested `blocks`), an `if`
checking the result, and `system/Common/Sleep` (`seconds` input) for the
delay. `RunScan` is now wrapped:
```
while (!$scan_ok && $scan_attempts < 5):
    RunScan (Common/SSH, && chained)
    scan_ok = RunScan.exitcode === 0
    scan_attempts += 1
    if (!scan_ok && scan_attempts < 5): Sleep 5s
```
Same pattern applied to `Remediate_SSH_Host`'s `PreScan`/`PostScanJson`
(distinct `pre_scan_ok`/`post_scan_ok` tracking variables so the two
retries don't interfere with each other).

**Update: the `&&` fix above turned out to be incomplete.** The exact
same error recurred for the pure `&&`-chained command too - `for`/`;`
were never actually the cause. Root-caused properly below.

## The actual root cause: `Common/SSH` wraps `command` in an extra pair of single quotes

Isolated with a bisection down to the simplest possible case: a
single-word command (`whoami`) succeeded through `Common/SSH` and
returned `ec2-user` correctly. A two-word command (`echo hello`) failed
with `bash: line 1: echo hello: command not found` - note it's the
*entire two-word string* being reported as one not-found command name.
`Common/SSH` appears to always wrap whatever we pass as `command` in one
extra pair of quotes before the remote shell sees it, and bash's own
quoting then swallows every space in a multi-word command into one big
single token, which cannot resolve to any real executable.

This also means `InstallTrivy` was **silently failing this entire time**
- nothing downstream ever checked its result, and the target host
happened to already have Trivy installed from manually testing it
directly over SSH during this same debugging session, which is why its
failure was never visible.

**Two quote-escaping attempts, both failed live.** First guess: double
quotes - reproduced `bash -c "\"echo hello\""` locally, matched the
error exactly, tried escaping with `:" ; cmd ; ":`. Live test: **failed
identically**, this time reporting the entire double-quote-escaped
string (escape characters included) as one not-found command. That
specific new symptom only reproduces locally via a *single*-quote wrap
(`bash -c "'echo hello'"`) - single quotes suppress every special
character except another single quote, so the embedded double quotes
did nothing. Switched to `:' ; cmd ; ':` (single-quote version) and
tried again live: **failed identically again** - the entire
single-quote-escaped string, escape characters included, reported as
one not-found command.

At that point, two different quote-character guesses had each
reproduced the OLD symptom locally but produced a NEW, differently-
escaped failure live, every time - meaning `Common/SSH`'s actual wrapping
mechanism was never actually being defeated by either attempt, and
there was no reliable way to keep guessing at the exact quoting scheme
from outside. Concluded this can't be fixed by re-quoting the command
string at all.

## The actual fix: sidestep `command` entirely for anything beyond a bare word

Bisection had already shown a bare single word (`whoami`, no arguments,
no operators) reliably succeeds through `Common/SSH`. So instead of
trying to smuggle a multi-word script through `command`, the real script
now goes through **`Common/SFTP/SFTP Put File`** (writes the file's exact
bytes directly - `mode: "0755"` makes it executable on upload, no `chmod`
command needed), gets invoked via **`Common/SSH`** using *only its bare
path* as `command` (a genuine single word, e.g. `/tmp/trivy_scan.sh` -
the one case already proven to work), and its output comes back via
**`Common/SFTP/SFTP Get File`** instead of `cat` over SSH stdout. No
`Common/SSH` command in this workflow (or `Remediate_SSH_Host`) is ever
more than one word anymore.

Verified end-to-end against the real target host, not a local
simulation: wrote the script via a heredoc over direct SSH, `chmod 755`,
invoked it by bare path alone, and confirmed a clean exit 0 with the
full valid Trivy JSON output written to the expected file - exactly the
sequence this workflow now performs through native blocks instead.

`InstallTrivy` as its own separate step is gone too - folded into the
same uploaded script (idempotent install-check + scan in one file), so
there's one script, one SSH invocation (wrapped in the retry loop), one
SFTP fetch, instead of two separate `Common/SSH` calls where the first
one's result was never actually checked.

**Still unconfirmed**: whether `Ansible/Playbook`'s multi-line YAML
`playbook` input hits the same class of bug - that's a different code
path from `Common/SSH`, so this fix doesn't automatically cover it. If
the same symptom shows up there, this is the section to revisit.

## Another real bug found live: `Common/SSH`'s default 30s inactivity timeout

The first live run of this CSV version failed at `BuildVmScanCsv` with
`Unexpected end of JSON input` - `$RunScan.result` was empty. Trivy's
`--quiet` scan produces no stdout while it works (downloading its
vulnerability DB, then scanning), and `Common/SSH`'s `timeout` input
defaults to 30 seconds of *inactivity*, not a hard ceiling - a real scan
can easily run past that in silence and get killed mid-way, leaving
nothing in the remote temp file for `cat` to return. Fixed by setting
`timeout: 300` explicitly on `InstallTrivy`/`RunScan`, and
`BuildVmScanCsv` now throws a clear error naming the exit code instead of
a bare `JSON.parse` crash if this happens again.

## What it does

1. **`BuildDeploySbom`** (plain JS `function` block): builds a ConcertDef
   1.0.2 deploy SBOM registering the target as a `vm` runtime component with
   name/value properties `os`/`version` (`$os_name`/`$os_version`,
   defaulting to `"amazon linux"`/`"2023"`), `hostname` and `ipv4`.
2. **`UploadDeploySbom`**
   (`system/IBM/Concert v2/Import Data/Upload Files to Concert`,
   `data_type: "application_sbom"`): registers the runtime *before* any
   CVE data is attached to it.
3. **`UploadScanScript`** (`Common/SFTP/SFTP Put File`): writes a small
   script (`#!/bin/bash` + idempotent trivy install-check + the actual
   `trivy rootfs --format json` scan, output to `/tmp/trivy-scan.json`)
   to `/tmp/trivy_scan.sh` on the target, `mode: "0755"` so it's
   executable immediately - no separate `chmod` needed.
4. **`RetryScan`** (`while` loop, up to 5 attempts / 5s apart): runs
   **`RunScan`** (`Common/SSH`, `command` = just `/tmp/trivy_scan.sh`,
   nothing else - a genuine single word) each iteration, checking
   `exitcode === 0` to decide whether to retry.
5. **`FetchScanResult`** (`Common/SFTP/SFTP Get File` on
   `/tmp/trivy-scan.json`): retrieves the scan output directly - no `cat`
   over SSH stdout involved.
6. **`BuildVmScanCsv`** (plain JS `function` block): parses the JSON,
   keeps only `Class == "os-pkgs"` vulnerabilities (excludes `lang-pkgs`
   findings, e.g. Trivy's own bundled Go dependencies), and emits the
   Concert-native `vm_scan` CSV, one row per CVE, using `$target_host` for
   the Host IPAddress/Host Name columns.
7. **`IngestScan`**
   (`system/IBM/Concert v2/Import Data/Upload Files to Concert`): uploads
   the CSV with `data_type: "vm_scan"`, `scanner_name: "concert"`.

Unlike the repo/image scans, Trivy runs **on the remote host itself**
(there's no "scan a live machine over the network" mode in Trivy) - the
uploaded script just runs it there. The target host ends up with Trivy
installed on it afterwards (left in place, same tradeoff as any
first-run agent install), along with the small script at
`/tmp/trivy_scan.sh` (also left in place - harmless, re-used/overwritten
on the next run, cleared on reboot anyway).

## Inputs

| Input | Required | Notes |
|---|---|---|
| `ssh_auth` | yes | SSH credential name for the target host |
| `concert_auth` | yes | IBM Concert API Key credential name |
| `target_host` | yes | IP/hostname of the target - populates the CSV's Host IPAddress/Host Name columns and the deploy SBOM's runtime hostname |
| `application_name` / `application_version` | no | Registers/matches the application the deploy SBOM's runtime is associated with |
| `environment_target` | no | Environment name the deploy SBOM registers this runtime under (e.g. `production`, `staging`) |
| `os_name` / `os_version` | no | **Must exactly match one of Concert's expected SBOM OS identifiers** for OS-type auto-remediation to generate at all - see Table 1 in "Configuring auto-remediation workflows": `amazon linux` (Amazon Linux), `redhat` (RHEL), `ubuntu`, `sles` (SUSE), `oracle linux`, `windows` |

## Trigger payload

```json
{
  "ssh_auth": "concert-cve-ssh-key",
  "concert_auth": "concert-api-auth",
  "target_host": "35.158.156.105",
  "application_name": "amazon-linux-demo",
  "application_version": "1.0.0",
  "environment_target": "production",
  "os_name": "amazon linux",
  "os_version": "2023"
}
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_SSH_Host_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
**Delete any previously-imported version first.**

## Verified vs. not verified

**Verified end-to-end against the real target host** (not a local
simulation): wrote the exact script content via a heredoc over direct
SSH, `chmod 755`'d it, invoked it by bare path alone, and got a clean
exit 0 with the full valid Trivy JSON output in the expected file -
exactly the sequence `UploadScanScript` -> `RunScan` -> `FetchScanResult`
now performs through native blocks.

**Verified live end-to-end (this exact scan/CSV pipeline, pre-deploy-SBOM version)**:
`UploadScanScript` -> `RetryScan` -> `FetchScanResult` -> `BuildVmScanCsv`
-> `IngestScan` ran successfully through the real Concert instance
(`202 Accepted`, `.csv` record path) and correctly attributed
`CVE-2023-45853` to the host as a distinct resource in Arena view.

**Verified locally in Node**:
- `BuildVmScanCsv`'s JS was actually *executed* against a sample Trivy
  JSON report containing both `os-pkgs` and `lang-pkgs` findings, plus a
  description with embedded commas and quotes - confirmed `lang-pkgs` is
  excluded and the CSV escaping is correct (round-tripped back through
  Python's own `csv` module reader).
- `BuildDeploySbom`'s JS was executed and its output round-tripped
  through `JSON.parse` to confirm well-formed JSON matching the shape
  reverse-engineered from IBM's toolkit sample config.
- Every block's action path and input names were copied directly from
  real, working IBM-published workflows, not guessed from a schema file.

**Not verified - genuinely unconfirmed, and the main open question right now**:
- Whether the corrected deploy SBOM (`BuildDeploySbom`/`UploadDeploySbom`)
  makes Concert generate a native `OS`-type auto-remediation action end to
  end. Verified so far (2026-10-01): the JSON the block produces passes
  IBM's deploy schema (1.0.2 and 2.0.0.1 files) and Concert's
  `validate_concertdef_sbom` (`200`), and the property names match what the
  remediation code reads. Not yet run through a full upload + `vm_scan` on a
  real host. The previous shape never passed validation.
- Whether `Common/SFTP`'s `mode: "0755"` really does what the schema
  says (sets the uploaded file executable) - inferred from the block's
  own field description, not independently tested through Concert.

## Where it lives

Imported 2026-10-01 (with the corrected ConcertDef deploy SBOM) as
`concertuser/User/Trivy_SSH_Host_Scan` on
`concert-concert.apps.itz-r87cnx.pok-lb.techzone.ibm.com`, via
`POST /workflows/api/v1/flows/concertuser/import` (zip with
`Trivy_SSH_Host_Scan.json` at its root; `meta.created`/`lastUpdated`/
`updatedByUsername` added on import). Credentials are run-time inputs
(`ssh_auth`, `concert_auth`), so nothing in the JSON is tied to a user.
For this reservation's RHEL 9 bastion use `target_host` `10.10.10.201`
(the IP Concert's pods reach it on), `os_name` `redhat`, `os_version` `9`.

## Fix 2026-10-01: upload content must be a string

`Upload Files to Concert` was called with `"filename": [$...]` (an array).
The block turns an array into a 1-byte file, so Concert accepted every
upload (`202`) and then failed processing it (`ERR_FILE_PARSING_1`).
Now `"filename": $...` (plain string), as confirmed live with BobShell Scan
(102 exposures processed). See `API_REFERENCE.md`. Applies to `UploadDeploySbom` and `IngestScan`; re-imported on itz-r87cnx.
