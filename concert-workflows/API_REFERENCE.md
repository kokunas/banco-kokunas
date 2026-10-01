# Concert 3.0 API - confirmed reference

Everything below was verified live against this instance
(`https://concert-concert.apps.itz-r87cnx.pok-lb.techzone.ibm.com` since
2026-09-29 - previously `itz-4j78fp`, that reservation is gone;
`InstanceId: 0000-0000-0000-0000` on both) during development/cleanup of
the workflows in this folder. Written down so it doesn't have to be
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
Well-formed but unknown/stale key -> `401 unauthorized, incorrect apikey`
(every new TechZone reservation needs a freshly generated key; a key that
was just generated can still be rejected - if so, generate another one).

## Core API - `/core/api/v1/...` (base `concert_url`, no extra prefix needed)

| Resource | Verb + path | Notes |
|---|---|---|
| Applications | `GET /core/api/v1/applications` | List. `associations.*` fields in the list response are always `null` - use the resource's own detail endpoint for real associations. |
| | `GET /core/api/v2/applications?filter=versions:all&page_size=2000` | What the UI uses. Since 2026-10-01 `GET /core/api/v1/applications` answers `400 "All applications can't be retrieved successfully."` on itz-r87cnx (both API key and session) - list with v2. Apps being deleted only show with `filter=status:delete_pending`. |
| | `DELETE /core/api/v2/applications/{id}` | What the UI uses (`202`). |
| | `DELETE /core/api/v1/applications/{id}` | Cascades to that app's own source_repos, packages, exposures (risks/findings) and actions. **Async**: status flips to `delete_pending` first, fully gone ~15-20s later - poll the list again rather than assuming instant. |
| Source repos | `GET /core/api/v1/source_repos` | List. Supports exact-match server-side filter: `?repo_url=<base64-encoded repo_url>` (confirmed: returns only the exact match, not fuzzy/substring). |
| | `GET /core/api/v1/source_repos/{id}` | Detail - includes `associations.applications` (`application_id`/`application_name`), `associations.packages`, `associations.exposures` (the actual risk/finding rule_ids), `associations.package_scans`. This is how you map a repo URL -> its parent application(s). |
| | `DELETE /core/api/v1/source_repos/{id}?is_cascade_delete=true` | From the UI's JS bundle (`deleteRepository`; cascades to packages, exposures and CVE findings), not yet run live. Bulk: `POST /core/api/v2/delete/components` with `component_type: "source_repository"`. Bulk apps: `POST /core/api/v2/delete_applications`. |
| Build artifacts | `GET /core/api/v1/build_artifacts` | List. |
| | `DELETE /core/api/v1/build_artifacts/{id}?is_cascade_delete=true` | From the UI's JS bundle (`deleteBuildArtifact`), not yet run live. Bulk variant: `POST /core/api/v2/delete/components` `{"component_type":"build_artifact","components":[{"id":...}]}`. |
| Environments | `GET /core/api/v1/environments` | List (`development`/`pre-production`/`production` exist by default; `number_of_hosts` shows up on entries that have hosts attached). |
| | `GET /core/api/v1/environments/{id}` | Detail. |
| | `DELETE /core/api/v1/environments/{id}` | **This is how you remove a host/VM and everything scanned on it** (Trivy_SSH_Host_Scan-style data) - deleting the environment cascades to its hosts (`infra-components`) and their software components. There is no working direct `DELETE` on a host or software-component id (see "Dead ends" below) - the environment is the deletable unit. |

## Ingestion API - `/ingestion/api/v1/...`

| Resource | Verb + path | Notes |
|---|---|---|
| Software components (OS packages found by host/VM scans) | `POST /ingestion/api/v1/software_components/search` | Body `{}` lists everything; add `?infra_associations=true&software_relationships=true` for the UI's richer version. Returns `id`, `name`, `version`, `lifecycle_state`, `tags`. No working DELETE on this resource directly - delete the owning environment instead. |
| Infrastructure/host detail | `GET /ingestion/api/v1/infrastructure/details?id=<infra_component_id>` | Returns host/VM metadata (`type`, `sub_type`, `ipv4_address`, `environment_name`, OS metadata). Route exists but only accepts GET (`DELETE` on the same path -> `405`). |
| ConcertDef validation (no ingestion) | `POST /core/api/v1/validate_concertdef_sbom` | `multipart/form-data`, file in field `filename` (what the processing pipeline itself calls). `200 {"status":"The SBOM was validated successfully..."}` or `400` with `errors[].code` (e.g. `sbom_validation_extracting_metadata`). Confirmed 2026-10-01 with session cookies + `InstanceId`. Validate every generated app/build/deploy SBOM here first; schemas and samples: IBM/Concert `toolkit-enablement/concert-utils/concertdef_schema` and `concert-concertdef-samples`. |
| Evidence store (`table_of_contents/el`) | `POST /ingestion/api/v1/table_of_contents/el/search` | Body `{}` or `{"page":1,"page_size":500}` (`pagination: {...}` -> `400` schema error). Returns `toc_records[]` with `el_path`, `data_type`, `is_archived`. The UI page uses `.../el/advanced_search` with `filters` (date range, `is_archived = false`). Confirmed 2026-10-01. |
| | `POST /ingestion/api/v1/table_of_contents/el/archive?el_path=<path>` | Only way to remove a file from the Evidence store (no delete; official block "Archive a Record From the Evidence Locker"). Unknown path -> `400 "Failed to archive the record."`. |
| Event log (`table_of_contents/lz`) | `POST /ingestion/api/v1/table_of_contents/lz/search` | Same body as `el/search`. **No delete/archive**: `POST .../lz/archive` and `DELETE .../lz` -> `404`; the official blocks only search, summarize and list filters. |
| SBOM/scan upload | `POST /ingestion/api/v1/upload_files` | `multipart/form-data`, used by `Trivy_GitHub_Scan`/`Trivy_Image_Scan`. Metadata dict needs `repo_url`+`scanner_name` (+ optional `application_name`/`application_version`/`criticality`/`data_impact_risk`). |

## Job manager API - `/mgmt/v1/...` (repo scans, scheduled jobs)

OpenAPI spec is served at `GET /mgmt/openapi.json` (same auth headers).
The UI's own prefixes live in `<concert_url>/concert/config.js`
(`API_PREFIX_JOBMNGR = '/mgmt/v1'`, etc.).

How an automatic repo scan runs: connecting a repo creates an **ingestion
job** of type `source_control_discovery_job` (core API, below). Running it
submits a job-manager job with the same name, whose single task
`source_code_scan_job` (`module: sourcecode_scan`) runs in a container
(`timeout_seconds: 3600`) that clones the repo and runs the SCA + SAST
scanners - the SAST exposures come back with Semgrep-style rule ids like
`sqli.java.mybatis-unsafe-interpolation`. Uploaded results are then
processed by separate `scan_processing_job` jobs.

| Resource | Verb + path | Notes |
|---|---|---|
| Jobs | `GET /mgmt/v1/job_manager/list_jobs` | Query params: `job_name`, `job_type`, `job_status`, `job_ids`, `page_size`, `next_page` (cursor from previous response). `page_number` is rejected (`422 extra_forbidden`). Returns `results[]` with `job_id`, `job_name`, `job_status` (`accepted`/`completed`/`failed`...), `job_params`, `created_at`, `finished_at`. |
| | `GET /mgmt/v1/job_manager/status/{job_id}` | Job + per-task detail (`task_status`, `container_status`, `launched_at`, `timeout_seconds`). |
| | `PUT /mgmt/v1/job_manager/cancel_job/{job_id}` | **Cancel a job** - this is the only cancel operation Concert exposes (listed in the OpenAPI spec). Not exposed anywhere in the UI. **Not yet tried live** (no scan was running when found) - unconfirmed whether it kills an already-launched scan container or only prevents a queued one from starting. |
| | `PUT /mgmt/v1/job_manager/scheduled/toggle` / `.../scheduled/update` | Enable/disable/update Concert's internal scheduled jobs (body schema `ScheduledTogglePayload` in the spec). |

Core API side of the same thing:

| Resource | Verb + path | Notes |
|---|---|---|
| Ingestion jobs | `GET /core/api/v1/ingestion_jobs` | List; `?filter=status:RUNNING` (the UI also adds `type:source_control_discovery_job`). Each has `type`, `status`, `enabled`, `last_run`, `details.parameters` (org/repo/branch/application). |
| | `GET /core/api/v1/ingestion_jobs/{id}` / `.../{id}/child_jobs` | Detail / child runs. |
| | `PATCH /core/api/v1/ingestion_jobs/{id}` | Edit (e.g. schedule, `enabled`). What the UI uses to change the job - the way to stop *future* runs without deleting anything. |
| | `DELETE /core/api/v1/ingestion_jobs/{id}` | Remove the job (keeps the application/findings already ingested). |
| Actions | `POST /ingestion/api/v1/actions/search` | Body `{"pagination":{"page":1,"page_size":500}}` (+ optional `filters`); response `pagination.total_pages`/`total_records`. Confirmed 2026-10-01. |
| | `POST /ingestion/api/v1/actions/delete` | Body `{"filters":{"logic":"and","conditions":[{"field":"actions_identifier","operator":"=","value":<n>},{"field":"target_component_identifier","operator":"=","value":"<repo url>"}]}}`. From the UI's JS bundle (the UI only offers Delete on `recommendation` actions), not yet run live. |
| | `POST /ingestion/api/v1/job/v2/submit` | "Run now" (body `{job_details:{job_id, job_type, ...}}`). |

### Debugging an upload that is accepted but never shows up (confirmed 2026-10-01)

`upload_files` answers `202` before anything is parsed. Processing runs as a
`scan_processing_job` (job manager, above) whose task is a K8s Job
`task-<task_id prefix>` in namespace `concert`, image `ibm-roja-pipeline`.
On failure the task shows `exit_code: 1`, `error_message: null`, retries 3x,
and the pods are deleted, so their logs are gone (`oc get events -n concert`
still shows `BackoffLimitExceeded`). Session cookies are enough to call
`/mgmt/v1/...` from a logged-in page if you add `InstanceId`.

To see why, read the parser itself: the bastion has `oc` with
`KUBECONFIG=~/kubeconfig` (system:admin), and the long-running
`roja-pipeline-*` pod runs the same image:
`oc exec -n concert <roja-pipeline pod> -- cat /app/src/mapperlib/yamls/exposure-mapping.yml`
(CSV column sets per `data_type`/scanner; a CSV is recognised only if *all*
mapped columns are present, else `ERR_FILE_PARSING_1`) and
`/app/src/exposlib/utils/preprocessing.py`.

## Event log (manual, database) - confirmed 2026-10-01

The Event log has no delete API. Its rows live in Postgres pod
`roja-postgres-appdb-*` (namespace `concert`), database `appdb`, table
**`ibm_roja_app.table_of_contents_lz`** (no foreign keys or triggers on
it; the Evidence store is `table_of_contents_el` in the same schema).
The DB user/password are **not** in env vars or `ROJA_AUTH_*`: they are
`APPDB_USER`/`APPDB_PASSWORD`/`APPDB_DBNAME` in secret
`app-cfg-oob-secret`, mounted in the pod at
`/mnt/infra/app-cfg-oob-secret`. Read them inside the pod so they never
get printed:

```bash
oc exec -i -n concert <roja-postgres-appdb pod> -- sh -c 'd=/mnt/infra/app-cfg-oob-secret; PGPASSWORD="$(cat $d/APPDB_PASSWORD)" psql -v ON_ERROR_STOP=1 -U "$(cat $d/APPDB_USER)" -d "$(cat $d/APPDB_DBNAME)"' <<'SQL'
begin;
create table ibm_roja_app.table_of_contents_lz_backup_<date> as table ibm_roja_app.table_of_contents_lz;
delete from ibm_roja_app.table_of_contents_lz;
commit;
SQL
```

Run 2026-10-01 on itz-r87cnx: 709 rows backed up to
`ibm_roja_app.table_of_contents_lz_backup_20261001` and deleted;
`lz/search` then returned 0. The original files stay in the MinIO LZ
bucket (`lz_path`). Unsupported by IBM.

## Dead ends (confirmed NOT to work - don't re-try these)

- `DELETE /ingestion/api/v1/infrastructure/{id}` and `/infrastructure?id=` -> 404 / 405
- `DELETE /ingestion/api/v1/infrastructures/{id}` (plural) -> 404
- `DELETE /core/api/v1/software_components/{id}` or `/ingestion/api/v1/software_components/{id}` -> 404
- `PATCH .../software_components/{id}` with `{"lifecycle_state":"deleted"}` -> 404
- `GET/DELETE /core/api/v1/resources`, `/core/api/v1/hosts` -> 404 (not real resource names in this version)
- Guessed scan/job routes all 404: `/core/api/v1/{scans,code_scans,jobs,tasks,package_scans,source_repo_scans}`, `/core/api/v1/source_repos/{id}/scans`, `/ingestion/api/v1/{scans,jobs,uploads,files,events,ingestion_jobs}`, `GET /ingestion/api/v1/upload_files`. Use the job manager / `ingestion_jobs` routes above instead. `associations.package_scans` on a source repo is just the list of ingested SBOM files - no run status.
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
  no further detail surfaced there. Root-caused 2026-09-30 (instance
  `6a9803d15d5e7a7f942bde16`):
  - Import is `POST /workflows/api/v1/flows/<user>/import`, multipart with
    `userName`, `folder` (`ibmconcert/User/`), `overwrite` (`ERROR`),
    `dryRun` (`true` first - the UI's preview; creates nothing) and `file`.
    Response is `[{code, path}]` only: `201` ok, `409` already exists,
    `422` invalid flow (no detail), `[]` = no `.json` found in the zip.
  - Each `.json` in the zip is imported **relative to its path in the
    zip** - a `Name/Name.json` folder layout becomes
    `User/Name/Name`. Put `<Name>.json` at the zip root.
  - `422` came from `meta`: with only `{workerGroup, layout}` it fails;
    the same flow with the full stored meta (`created`, `lastUpdated`,
    `updatedByUsername`, `numberOfBlocks`, `version`, ...) passes. Blocks,
    `comment` blocks, boolean variables and empty `blocks` were all fine.
  - Read a stored flow: `GET /workflows/api/v2/flows/<user>?folder=%2FUser%2F&name=<Name>`
    (`name` is required - without it `400`; there is no list-all variant there).
  - On `itz-r87cnx` the Workflows user is **`concertuser`** (folder
    `concertuser/User/`, credentials referenced as `"concertuser/<Name>"`);
    on the older `concert-dataapps` instance it was `ibmconcert`. Import
    confirmed there 2026-09-30 (dry run and real, both `201`) from an
    in-page `fetch` with just the session cookies - no extra headers.
  - Replace an existing flow: same import with `overwrite=OVERWRITE`
    (`ERROR` on an existing flow -> `409`; unknown values like `REPLACE`
    -> HTTP `400` enum error). Confirmed 2026-09-30.
  - `meta.version` must be `5` (the flow-format version, same on every
    stored flow) - a new flow with `version: 1` gets `422`, everything
    else equal. Confirmed 2026-10-01 by bisecting `meta` field by field.
  - `code: 500` in the import response = the flow JSON itself is rejected,
    with no detail (same for a new name). Seen with a locally edited file
    whose `meta` had drifted (`numberOfBlocks: 7` vs the stored `10`,
    hand-bumped `version`/`lastUpdated`); the same logical changes applied
    on top of the flow read back with `GET .../v2/flows/...` (minus `path`,
    `hash` and a null `finally`) imported fine. Safest edit loop: read the
    stored flow, modify it, re-import - and save the read-back copy to the repo.
  - The zip can be built in-page (stored/no-compression zip, CRC32) - no
    need to round-trip a file from disk.
- Calling any Concert API from a flow with a **stored credential**:
  block `system/IBM/Concert v2/Concert HTTP Request`, inputs `authKey`
  (e.g. `"concertuser/Concert"`, type `IBM/ConcertAPIKey`), `method`,
  `path` (`/core/api/v1/...`, no host), `query` (object or query
  string), `headers`, `body` (object). `result` is the parsed JSON body;
  a non-2xx throws (`"400 - <body>"`), so wrap deletes in a `try` block -
  the `catch` reads the error as `$<TryName>.message`. Confirmed live
  2026-10-01. Stored credential values are not readable from a flow
  (`authstorages` returns them as `null`), so a Python FaaS block can't
  reuse them - use this block instead.
- **`Upload Files to Concert` block: pass the file content as a plain
  string, never as an array** - `"filename": $csv`, not `"filename": [$csv]`.
  The block's module (`/usr/src/app/modules/IBM/Concert/request.js` in the
  `rna-core-pliant-worker` pod, namespace `concert-workflows`) does
  `Buffer.from(fd, 'utf-8')` on the `filename` value: an array of strings
  becomes a 1-byte file. Concert still answers `202`, the Event log shows
  `file_size: 0.000001` and the `scan_processing_job` fails with
  `ERR_FILE_PARSING_1` ("The data format is not in required format",
  `preprocessing.py:493`) because there is no header to detect. Confirmed
  live 2026-10-01: a 1-row valid CSV failed as an array and the real
  102-row report processed fine as a string. (Note: the event's
  `file_size` is not a reliable size check - it read `0.000001` for every
  upload through this block.)
- Real processing errors of an upload: the `scan_processing_job` runs as
  pods `task-<id>-*` in namespace `concert`, deleted after 3 failed
  retries. Capture them while they run (loop `oc logs -n concert <pod>`
  every few seconds) and grep for the event id.
- Run a flow without the UI: `POST /workflows/api/v1/trigger/<user>?folder=%2FUser%2F&name=<Name>&worker_group=default&event_source=EDITOR`
  with the inputs as JSON body -> `{"$uuid": ..., "$status": "Q"}`; the
  outcome shows up in `stats/filter` under that `id`. Confirmed 2026-10-01.
- Read a system block's definition (inputs/outputs):
  `GET /workflows/api/v1/flows/system?folder=/IBM/Concert v2/&name=<Block>`
  (the v2 route fails with "Failed to convert nodes to blocks"); browse
  them with `GET /workflows/api/v1/folders/system?recursive=false&folder=/IBM/Concert v2/`.
- Dropdown inputs: a flow variable typed
  `{"type": "string", "enum": ["a", "b"]}` (with `"value": "\"a\""` as the
  default) renders as a select in the Run dialog - confirmed 2026-10-01.
  Prefer it over `boolean`, whose Run-dialog checkbox has a third "unset"
  (`—`) state.
- Run history: `GET /workflows/api/v1/stats/filter?startTime=<ms>&endTime=<ms>&page=0&size=30`
  (what the Logs page calls) - per run `flowUri`, `status`, `success`,
  `result`, `error` (JSON string with `message` and the failing `node`).
  A "Bad Request" shown in the UI that leaves no entry here never started a run.
- Credentials ("authentications"): list with
  `GET /workflows/api/v1/authstorages/<user>?includeShared=true` (`[]` when
  none); field definitions per type from `GET /workflows/api/v1/authschemas/`
  - e.g. SSH: `host, port, username, password, privateKey`; IBM Concert API
  Key: `protocol, host, apiKey, apiKeyType, instanceId`.
- Easiest way to replace an existing flow's content: editor toolbar `</>`
  button -> "Flow YAML / JSON" dialog -> JSON tab -> paste -> dialog Save
  -> top-right Save. Its schema rejects `"finally": null` ("Incorrect
  type. Expected object" - omit the key); F8 in that editor jumps to the
  error. The Workflows UI loads hundreds of integration icons, which can
  saturate Chrome's per-host connection pool and make in-page `fetch()`
  hang - run API calls from a lightweight same-origin URL instead
  (e.g. `/workflows/api/solis/about`).

## Quick copy-paste cURL commands

Set these once per shell session:

```bash
export CONCERT_URL="https://concert-concert.apps.itz-r87cnx.pok-lb.techzone.ibm.com"
export CONCERT_API_KEY="<generated in the Concert UI - already base64, use as-is>"
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

**Repo scan jobs (job manager) and their task status**
```bash
curl -s "$CONCERT_URL/mgmt/v1/job_manager/list_jobs?job_name=source_control_discovery_job" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
curl -s "$CONCERT_URL/mgmt/v1/job_manager/status/<job_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
```

**Cancel a running/queued repo scan** (untested live - see job manager section)
```bash
curl -s -X PUT "$CONCERT_URL/mgmt/v1/job_manager/cancel_job/<job_id>" \
  -H "Authorization: C_API_KEY $CONCERT_API_KEY" -H "InstanceId: $CONCERT_INSTANCE_ID"
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
