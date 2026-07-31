# Concert 3.0 API - confirmed reference

Everything below was verified live against this instance
(`https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com`,
`InstanceId: 0000-0000-0000-0000`) during development/cleanup of the
workflows in this folder. Written down so it doesn't have to be
re-discovered by trial and error next time.

## Auth (every core/ingestion API call needs both headers)

```
Authorization: C_API_KEY <the API key EXACTLY as issued - it is already
                          a base64 blob (e.g. base64("concertuser:<uuid>")).
                          Do NOT base64-decode it first - decoding it and
                          sending the decoded "user:uuid" string fails
                          with "illegal base64 data at input byte 8"
                          (Concert's middleware base64-decodes the header
                          value itself; feed it the encoded form as-is).
InstanceId: <instance id, e.g. 0000-0000-0000-0000>
```

Missing `InstanceId` -> `401 instance ID Not Found`. Wrong-shaped
`Authorization` value -> `401 illegal base64 data at input byte N`.

## Core API - `/core/api/v1/...` (base `concert_url`, no extra prefix needed)

| Resource | Verb + path | Notes |
|---|---|---|
| Applications | `GET /core/api/v1/applications` | List. `associations.*` fields in the list response are always `null` - use the resource's own detail endpoint for real associations. |
| | `DELETE /core/api/v1/applications/{id}` | Cascades to that app's own source_repos, packages, exposures (risks/findings) and actions. **Async**: status flips to `delete_pending` first, fully gone ~15-20s later - poll the list again rather than assuming instant. |
| Source repos | `GET /core/api/v1/source_repos` | List. Supports exact-match server-side filter: `?repo_url=<base64-encoded repo_url>` (confirmed: returns only the exact match, not fuzzy/substring). |
| | `GET /core/api/v1/source_repos/{id}` | Detail - includes `associations.applications` (`application_id`/`application_name`), `associations.packages`, `associations.exposures` (the actual risk/finding rule_ids), `associations.package_scans`. This is how you map a repo URL -> its parent application(s). |
| Build artifacts | `GET /core/api/v1/build_artifacts` | List. |
| Environments | `GET /core/api/v1/environments` | List (`development`/`pre-production`/`production` exist by default; `number_of_hosts` shows up on entries that have hosts attached). |
| | `GET /core/api/v1/environments/{id}` | Detail. |
| | `DELETE /core/api/v1/environments/{id}` | **This is how you remove a host/VM and everything scanned on it** (Trivy_SSH_Host_Scan-style data) - deleting the environment cascades to its hosts (`infra-components`) and their software components. There is no working direct `DELETE` on a host or software-component id (see "Dead ends" below) - the environment is the deletable unit. |

## Ingestion API - `/ingestion/api/v1/...`

| Resource | Verb + path | Notes |
|---|---|---|
| Software components (OS packages found by host/VM scans) | `POST /ingestion/api/v1/software_components/search` | Body `{}` lists everything; add `?infra_associations=true&software_relationships=true` for the UI's richer version. Returns `id`, `name`, `version`, `lifecycle_state`, `tags`. No working DELETE on this resource directly - delete the owning environment instead. |
| Infrastructure/host detail | `GET /ingestion/api/v1/infrastructure/details?id=<infra_component_id>` | Returns host/VM metadata (`type`, `sub_type`, `ipv4_address`, `environment_name`, OS metadata). Route exists but only accepts GET (`DELETE` on the same path -> `405`). |
| SBOM/scan upload | `POST /ingestion/api/v1/upload_files` | `multipart/form-data`, used by `Trivy_GitHub_Scan`/`Trivy_Image_Scan`. Metadata dict needs `repo_url`+`scanner_name` (+ optional `application_name`/`application_version`/`criticality`/`data_impact_risk`). |

## Dead ends (confirmed NOT to work - don't re-try these)

- `DELETE /ingestion/api/v1/infrastructure/{id}` and `/infrastructure?id=` -> 404 / 405
- `DELETE /ingestion/api/v1/infrastructures/{id}` (plural) -> 404
- `DELETE /core/api/v1/software_components/{id}` or `/ingestion/api/v1/software_components/{id}` -> 404
- `PATCH .../software_components/{id}` with `{"lifecycle_state":"deleted"}` -> 404
- `GET/DELETE /core/api/v1/resources`, `/core/api/v1/hosts` -> 404 (not real resource names in this version)
- ~~`DELETE /core/api/v1/environments/{id}` on a *default* environment (development/pre-production) hasn't been tested~~ - confirmed live: `204` on all of `development`/`pre-production`/a custom empty one too, not just `production`. No special-casing of the default 3 environments - any environment id deletes cleanly the same way.

## Workflows product (separate app, separate base path)

- Lives at `<concert_url>/workflows/...`, not under `/concert/...` - it's a
  distinct microservice/UI from the Protect/Resilience/Data Apps side.
- Workflow list/import UI: `/workflows/flows?instance_id=<id>`.
- Import only accepts `.zip` - a loose `.json` is greyed out/unselectable
  in the picker (confirmed, matches what every workflow README here
  already says). The hidden `<input type=file>` behind the Import button
  has `accept="application/zip"`.
- A failed import surfaces only a terse
  `Unprocessable workflows: ibmconcert/User/<Name>/<Name>` in the UI, with
  no further detail surfaced there - the underlying validation reason is
  still not confirmed (in progress as of this writing). Next step if this
  comes up again: capture the network response body of the import POST
  request itself (not just the toast), since that's almost certainly
  where the real validation error lives.

## Quick copy-paste cURL commands

Set these once per shell session:

```bash
export CONCERT_URL="https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com"
export CONCERT_API_KEY="Y29uY2VydHVzZXI6Y2MxMDAxYzMtY2ZkYi00N2UxLWE4YzAtYTI2MGEzMTQ5YzE4"  # already base64, use as-is
export CONCERT_INSTANCE_ID="0000-0000-0000-0000"
alias concert_curl='curl --silent --request'
```

Every command below is just `$CONCERT_URL/<path>` with
`--header "Authorization: C_API_KEY $CONCERT_API_KEY"` and
`--header "InstanceId: $CONCERT_INSTANCE_ID"`.

**List applications**
```bash
curl -s "$CONCERT_URL/core/api/v1/applications" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**Delete one application** (cascades to its source_repos/packages/exposures/actions, async ~15-20s)
```bash
curl -s -X DELETE "$CONCERT_URL/core/api/v1/applications/<application_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**List source repos**
```bash
curl -s "$CONCERT_URL/core/api/v1/source_repos" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**Find the source_repo for one exact git URL** (server-side exact match)
```bash
REPO_URL="https://github.com/kokunas/banco-kokunas"
B64=$(printf '%s' "$REPO_URL" | base64)
curl -s "$CONCERT_URL/core/api/v1/source_repos?repo_url=$B64" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**Source repo detail** (which application(s) it's registered under, packages, exposures/risks)
```bash
curl -s "$CONCERT_URL/core/api/v1/source_repos/<source_repo_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**List build artifacts**
```bash
curl -s "$CONCERT_URL/core/api/v1/build_artifacts" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**List environments**
```bash
curl -s "$CONCERT_URL/core/api/v1/environments" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**Delete one environment** (cascades to its hosts/infra-components and their software components - this is the only way to remove host/VM scan data, e.g. from Trivy_SSH_Host_Scan)
```bash
curl -s -X DELETE "$CONCERT_URL/core/api/v1/environments/<environment_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**List all software components (OS packages found on scanned hosts)**
```bash
curl -s -X POST "$CONCERT_URL/ingestion/api/v1/software_components/search" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID" \
  -H "Content-Type: application/json" --data '{}'
```

**Host/infra-component detail**
```bash
curl -s "$CONCERT_URL/ingestion/api/v1/infrastructure/details?id=<infra_component_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

## UI quirks (only matters if driving the browser instead of the API)

- The Concert **login page** is built with web components / Shadow DOM
  (real inputs are `#platform-username`/`#platform-password` nested
  inside shadow roots). This breaks the accessibility-tree tools
  (`read_page`/`find` return an empty tree there) and also breaks plain
  coordinate-based clicks (they land on the wrong shadow element). Fix:
  pierce the shadow DOM from JS (recursive `querySelectorAll` +
  `.shadowRoot`) to find the real input, call `.focus()` on it via JS,
  *then* use the browser tool's real `type` action (works fine once
  actually focused) - and submit via `el.click()` on the real
  `.platform-login-button` in JS rather than relying on a coordinate
  click or Enter key (neither reliably submitted the form here).
- Everywhere else in the app (Workflows list, Protect pages), normal
  `read_page`/`find`/ref-based clicks work fine - the shadow-DOM issue is
  specific to that login screen.
- Hash-based SPA routes (`/concert/#/developer/...`) don't always resolve
  on a fresh full `navigate()` - it can flash the default page first and
  only route correctly a second or two later. Wait and re-screenshot
  before concluding a navigation failed.
