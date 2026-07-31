# 01b - Scan image: Trivy Image Scan

Custom Concert Workflow (same `system/FaaS/Python` block schema as the
official `trivy-github-scan` sample) that scans the **built container
image** (`ghcr.io/kokunas/banco-kokunas`) instead of the source repo -
picking up OS-level packages from the base image (`eclipse-temurin:17-jre-jammy`,
Ubuntu 22.04) plus the bundled Java libraries, on top of what
[Trivy_GitHub_Scan](../Trivy_GitHub_Scan) already finds from `pom.xml`.

## Why this exists

The "correct" way to register a container image in Concert is
`POST /core/api/v1/build_artifacts`. **Confirmed live that this endpoint
is unreliable in this installation**: it returns
`errMismatchInURIandName` even for a minimal, schema-valid payload
(`{"name": "java-app-cve", "type": "image"}`), and a follow-up retry with
an even simpler payload timed out instead of erroring - inconsistent
failure modes pointing to a genuine platform bug, not a payload mistake.
This is a second, independent bug from the CycloneDX version issue below,
in the same general area (application-resource ingestion).

Given that, this workflow reuses the same `/ingestion/api/v1/upload_files`
`code_scan` path already proven reliable for [Trivy_GitHub_Scan](../Trivy_GitHub_Scan), just
pointed at the image instead of the repo. Concert creates a second
`source_repo`-shaped entry named after the image
(`ghcr.io/kokunas/banco-kokunas:latest`) associated with the same
`banco-kokunas` application, and its vulnerabilities merge into the
application's CVE list. **Not a true build-artifact entity** in Concert's
data model - but it gets the image's real vulnerability data attached to
the application, which is what matters for the demo. Revisit this once
IBM fixes the `build_artifacts` endpoint.

## Same CycloneDX fix as Trivy_GitHub_Scan

Also applies the `specVersion` 1.6 -> 1.5 rewrite documented in
[Trivy_GitHub_Scan's README](../Trivy_GitHub_Scan/README.md) - confirmed necessary here too
(same `errUnsupportedCycloneDXVersion` failure otherwise).

## How to import

Concert Workflows console -> Workflows -> Import -> `Trivy_Image_Scan.zip`.

## Trigger payload for this demo

Every field is already pre-filled as a default except `concert_api_key`:

```json
{
  "image_ref": "ghcr.io/kokunas/banco-kokunas:latest",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<your Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000",
  "concert_allow_insecure": false,
  "application_name": "banco-kokunas",
  "application_version": "1.0.0"
}
```

## Verified locally

Ran the exact logic (apk/trivy install steps swapped for already-installed
local binaries) against the real `ghcr.io/kokunas/banco-kokunas:latest`
image and the real Concert instance:
- Trivy found **215 components / 88 vulnerabilities** in the image (OS
  packages from Ubuntu 22.04 + bundled JARs) - versus 7 CVEs / 82 packages
  from the source-repo-only scan in [Trivy_GitHub_Scan](../Trivy_GitHub_Scan).
- Upload accepted (`202`), and `banco-kokunas`'s total CVE count went from 7 to
  88 after ingestion, confirming the image's findings attached correctly.
- Image digest was fetched directly from GHCR's registry API for
  reference during testing: confirm it's still reachable/public with
  `curl -H "Authorization: Bearer $(curl -s 'https://ghcr.io/token?service=ghcr.io&scope=repository:kokunas/banco-kokunas:pull' | jq -r .token)" https://ghcr.io/v2/kokunas/banco-kokunas/manifests/latest`
  (needs an `Accept: application/vnd.oci.image.index.v1+json` header since
  it's a multi-arch manifest list).
