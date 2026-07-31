# 01 - Scan: Trivy GitHub Scan

Source: adapted from IBM's official sample workflow
[`trivy-github-scan`](https://github.com/IBM/Concert/blob/main/concert-workflows-samples/trivy-github-scan.zip)
(Apache-2.0, `github.com/IBM/Concert`). **One deliberate change from the
original**: the upload step now also sends `application_name` /
`application_version` in the ingestion metadata. Without this, Concert
auto-creates the scanned app under a `placeholder_app_<base64(user)>` name
instead of `banco-kokunas` - confirmed live against this repo/instance during
development (see "Verified" below). Everything else is unmodified.

## What it does

A `system/FaaS/Python` block:
1. Installs `curl` and `trivy` inside the workflow's sandbox.
2. Runs `trivy repo --scanners vuln --format cyclonedx --output scan.json <gh_repo_url>`.
3. Uploads `scan.json` (a CycloneDX SBOM + vulnerability report) to Concert's
   ingestion API (`POST /ingestion/api/v1/upload_files`, `data_type=code_scan`).

This is the same scanner Concert uses for its own default code scanning, so
this workflow reproduces exactly what "Concert scans the repo with Trivy by
default" looks like, just made explicit and re-runnable on demand.

## How to import

Follow [Importing/exporting Concert workflows](https://www.ibm.com/docs/en/rapid-network-auto/1.1.x?topic=workflows-importing-exporting):
Concert Workflows console -> Workflows -> Import -> select `Trivy_GitHub_Scan.zip`
(the picker greys out loose `.json` files - always import the `.zip`).

## How to run it for this demo

Every field except `concert_api_key` is already pre-filled as the
workflow's default (`gh_repo_url`, `concert_url`, `concert_instance_id`,
`application_name`, `application_version` all point at this demo already -
`concert_api_key` is left blank on purpose, it's a secret). On each run
you only need to paste that one:

```json
{
  "gh_repo_url": "https://github.com/kokunas/banco-kokunas",
  "gh_api_token": "",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "concert_allow_insecure": false,
  "application_name": "banco-kokunas",
  "application_version": "1.0.0"
}
```

Leave `gh_api_token` empty - the repo is public.

## Verified

Ran this exact modified logic (curl/trivy install steps swapped for
already-installed local binaries, everything else identical) against the
real repo and the real Concert instance: the scan completed, uploaded with
`202 Accepted`, and the resulting Concert application was named `banco-kokunas`
directly - no placeholder name, no manual rename needed afterward.

## Expected findings

Scanning `pom.xml` should surface, among others:

| CVE | Package | Installed | Fixed in | Severity |
|-----|---------|-----------|----------|----------|
| CVE-2021-44228 (Log4Shell) | org.apache.logging.log4j:log4j-core | 2.14.1 | 2.17.1+ (2.24.3 used here) | CRITICAL |
| CVE-2021-45046 | org.apache.logging.log4j:log4j-core | 2.14.1 | 2.16.0+ | CRITICAL |

The SQL Injection in `VulnerableSearchRepository` (see
[SQLi_Code_Remediation](../../remediation/SQLi_Code_Remediation)) is **not** a dependency CVE, so
Trivy's SCA scan will not flag it - it represents the "unknown" / non-CVE
class of finding that Concert's code-risk analysis (or a SAST tool) needs to
surface separately, deliberately included in this demo to show both
remediation paths.

Once ingested, open the vulnerability in Concert's **Vulnerability**
dimension or the **Arena view** to see it prioritized against the
`banco-kokunas` application topology (public endpoint reachability, business
criticality, EPSS/exploitability) before triggering remediation.
