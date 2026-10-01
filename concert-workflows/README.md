# Concert Workflows - remediation lifecycle for banco-kokunas

These are real **IBM Concert Workflows** (importable `.json`/`.zip` flow
definitions for a Concert Workflows instance), not GitHub Actions.
[discovery/Trivy_GitHub_Scan](discovery/Trivy_GitHub_Scan) and
[remediation/Maven_Package_Upgrade](remediation/Maven_Package_Upgrade) are
unmodified copies of IBM's own published samples from
[`github.com/IBM/Concert`](https://github.com/IBM/Concert) (Apache-2.0).
Everything else here is custom, authored using the exact same block schema
(`system/Github/...`, `system/FaaS/Python`, `assign`, `if`, plain JS
`Function` blocks, nested `./Helper/Subflow` calls) because no stock IBM
workflow covers a non-CVE code finding, a property-based `pom.xml` bump,
PR auto-merge, isofunctional-test-plus-email, CMDB-style application
registration, or scoped demo reset/cleanup.

Every JSON file's block logic that touches a real repo or this Concert
instance's real data was replayed/executed locally against the actual
repo and the actual Concert API before being written here - see each
folder's README for the exact verification performed.

See [API_REFERENCE.md](API_REFERENCE.md) for the confirmed Concert 3.0
core/ingestion API surface (auth header shape, working endpoints, dead
ends already ruled out) - check it before probing the API by hand again.

## Layout

```
concert-workflows/
├── discovery/       - scan/find CVEs, register applications in Concert
├── remediation/     - fix CVEs (individual steps + the orchestrator)
└── reset-demo/      - revert code + clean up Concert between demo runs
```

## discovery/ - find things

| Workflow | What it does |
|---|---|
| [Trivy_GitHub_Scan](discovery/Trivy_GitHub_Scan) | Trivy SCA scan of a GitHub repo, CycloneDX report ingested into Concert - trigger live, in front of the audience, to show Concert connecting to GitHub and detecting CVEs from nothing. Also sets the scanned application's `criticality`/`data_impact_risk` (needed so findings get prioritized instead of defaulting to "Deprioritized"). |
| [Trivy_Image_Scan](discovery/Trivy_Image_Scan) | Trivy scan of the built container image (OS packages + bundled libraries), same `code_scan` ingestion path - workaround for `build_artifacts` registration being broken on this Concert install (see the top-level bug report). |
| [Sync_AWS_Linux_Bulletin](discovery/Sync_AWS_Linux_Bulletin) | **Prerequisite** for `Trivy_SSH_Host_Scan`'s findings to generate a native `OS`-type auto-remediation action - unmodified IBM sample that scrapes the real Amazon Linux Security Advisory pages and populates Concert's `os_advisory_cache`. Run once, no inputs needed. |
| [Trivy_SSH_Host_Scan](discovery/Trivy_SSH_Host_Scan) | Trivy OS-level scan of a live Linux host reached over SSH (script uploaded via SFTP, invoked by its bare path over SSH, output fetched via SFTP - `Common/SSH`'s `command` field cannot run any multi-word command in this Concert install, confirmed live). Uploads the result as Concert's native `vm_scan` CSV via the official `Import Data/Upload Files to Concert` block. |
| [Simulate_CMDB_Applications](discovery/Simulate_CMDB_Applications) | Registers 3 fictional legacy applications directly via the core API (no scan behind them), each with a description naming a famous CVE (WannaCry/Heartbleed/Shellshock) that doesn't fit this demo's real stack. Stay "Manual" by design - contrast with the automated remediation on banco-kokunas. |

## remediation/ - fix things

Individual steps (each opens one PR):

| Workflow | Fixes |
|---|---|
| [Maven_Package_Upgrade](remediation/Maven_Package_Upgrade) | CVE-2021-44228 (Log4Shell) - bumps a literal `<dependency><version>` tag, matched by `groupId:artifactId` |
| [Spring_Property_Upgrade](remediation/Spring_Property_Upgrade) | CVE-2022-22965 (Spring4Shell) - bumps the `spring-framework.version` **property** in `pom.xml` instead (spring-core isn't a direct dependency here) |
| [SQLi_Code_Remediation](remediation/SQLi_Code_Remediation) | CWE-89 (SQL injection, not a CVE - lives in this repo's own code) - rewrites string concatenation to bind parameters |
| [Verify_And_Notify](remediation/Verify_And_Notify) | Runs the isofunctional test suite + a fresh Trivy re-scan against the now-merged `main`, pushes the re-scan to Concert, marks the matching native Concert action `success`/`failed`, and emails the result |
| [Remediate_SSH_Host](remediation/Remediate_SSH_Host) | OS-package CVEs on a live Linux host reached over SSH (no PR involved - there's no git repo behind a scanned host) - detects with Trivy, applies the fix and reboots via an `Ansible` playbook block (matching IBM's own `Apply_Amazon_Linux_Patch` pattern), re-scans to confirm, pushes the result to Concert, and closes out the matching native action via the official Action Insights blocks |

Orchestrator (nests the individual steps above into one trigger):

| Workflow | Nests |
|---|---|
| [Remediate_All](remediation/Remediate_All) | `banco-kokunas`: Maven_Package_Upgrade + Spring_Property_Upgrade + SQLi_Code_Remediation -> merges all 3 PRs via the GitHub REST API -> Verify_And_Notify |

## reset-demo/ - clean up between runs

| Workflow | What it does |
|---|---|
| [Reset_Demo](reset-demo/Reset_Demo) | Reverts `pom.xml` (+ `VulnerableSearchRepository.java`) back to the `vulnerable-baseline` git tag, then deletes **only** the applications named in `application_names` from Concert (default: `banco-kokunas`, the 3 simulated legacy apps) - safe to run against a Concert instance shared with other teams, since anything else is left untouched. |
| [Reset_All](reset-demo/Reset_All) | **Blanket wipe of the whole instance**: deletes every application, source repo (and its repo scan connection), build artifact, environment/host and action - so every CVE and exposure goes too - and archives every Evidence store file (the Event log can't be cleared). Never touches credentials or workflows. No inputs (uses the stored `concertuser/Concert` credential) - running it deletes. |
| [Delete_Repo_Data](reset-demo/Delete_Repo_Data) | Takes any single `gh_repo_url`, finds the Concert application registered from it, and deletes just that application (cascades to its own source_repos/risks/actions) - for one-off cleanup of a single repo's data without touching the rest of a shared instance. |

## Live demo flow

1. **`Reset_Demo`** (reset-demo/) - run first, every time.
2. **`Trivy_GitHub_Scan`** (discovery/) - double-check `application_name` each time - the default won't change itself.
3. *(optional)* **`Simulate_CMDB_Applications`** (discovery/) - enrich the Arena view with legacy portfolio apps.
4. *(Concert UI)* **Prioritize** - open the findings in Concert's Vulnerability dimension / Arena view.
5. **`Remediate_All`** (remediation/) - remediate + merge + verify + notify, one trigger.
6. `oc rollout restart deployment/banco-kokunas-app -n banco-kokunas`, so the running pods pick up the newly-published fixed image.

## Prerequisites to run this for real

1. A Concert Workflows instance (API Gateway URL, API key, instance ID - Concert Administration -> API keys).
2. A GitHub credential/token, a fine-grained PAT scoped to `kokunas/banco-kokunas` (`contents:write` + `pull_requests:write`): `github_pat_banco-kokunas`.
3. An SMTP relay (host/port/username/password + a verified "from" address) for `Verify_And_Notify` (currently disabled pending SMTP setup - prints the notification instead of sending it). Store the password as a Concert credential, not inline.
4. Nothing pre-registered in Concert - `Trivy_GitHub_Scan`'s ingestion auto-creates the application the first time it runs after a `Reset_Demo`.

## Importing

**Always import the `.zip`, never the loose `.json`** - Concert's import
picker greys out/disables raw `.json` files, even for a single-flow bundle
with no subflows (confirmed live). Every workflow here ships both forms:
the loose `.json` for readability/diffing in this repo, and a `.zip` next
to it for import:

| Workflow | Import this |
|---|---|
| Reset_Demo | [`reset-demo/Reset_Demo/Reset_Demo.zip`](reset-demo/Reset_Demo/Reset_Demo.zip) |
| Delete_Repo_Data | [`reset-demo/Delete_Repo_Data/Delete_Repo_Data.zip`](reset-demo/Delete_Repo_Data/Delete_Repo_Data.zip) |
| Trivy_GitHub_Scan | [`discovery/Trivy_GitHub_Scan/Trivy_GitHub_Scan.zip`](discovery/Trivy_GitHub_Scan/Trivy_GitHub_Scan.zip) |
| Trivy_Image_Scan | [`discovery/Trivy_Image_Scan/Trivy_Image_Scan.zip`](discovery/Trivy_Image_Scan/Trivy_Image_Scan.zip) |
| Sync_AWS_Linux_Bulletin | [`discovery/Sync_AWS_Linux_Bulletin/Sync_AWS_Linux_Bulletin.zip`](discovery/Sync_AWS_Linux_Bulletin/Sync_AWS_Linux_Bulletin.zip) |
| Trivy_SSH_Host_Scan | [`discovery/Trivy_SSH_Host_Scan/Trivy_SSH_Host_Scan.zip`](discovery/Trivy_SSH_Host_Scan/Trivy_SSH_Host_Scan.zip) |
| Simulate_CMDB_Applications | [`discovery/Simulate_CMDB_Applications/Simulate_CMDB_Applications.zip`](discovery/Simulate_CMDB_Applications/Simulate_CMDB_Applications.zip) |
| Maven_Package_Upgrade | [`remediation/Maven_Package_Upgrade/Maven_Package_Upgrade.zip`](remediation/Maven_Package_Upgrade/Maven_Package_Upgrade.zip) |
| Spring_Property_Upgrade | [`remediation/Spring_Property_Upgrade/Spring_Property_Upgrade.zip`](remediation/Spring_Property_Upgrade/Spring_Property_Upgrade.zip) |
| SQLi_Code_Remediation | [`remediation/SQLi_Code_Remediation/SQLi_Code_Remediation.zip`](remediation/SQLi_Code_Remediation/SQLi_Code_Remediation.zip) |
| Verify_And_Notify | [`remediation/Verify_And_Notify/Verify_And_Notify.zip`](remediation/Verify_And_Notify/Verify_And_Notify.zip) |
| Remediate_All (+ 3 nested subflows) | [`remediation/Remediate_All/Remediate_All.zip`](remediation/Remediate_All/Remediate_All.zip) |
| Remediate_SSH_Host | [`remediation/Remediate_SSH_Host/Remediate_SSH_Host.zip`](remediation/Remediate_SSH_Host/Remediate_SSH_Host.zip) |

Console -> Workflows -> Import -> select the `.zip`. See
[Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting)
for the general procedure (same one IBM's own sample README points to).
