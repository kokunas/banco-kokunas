# Reset_All - wipe all vulnerability data

Deja la instancia de Concert sin ningún dato de vulnerabilidades. Unlike
`Reset_Demo` (which only deletes this demo's applications by name), this
is a **blanket wipe of the whole instance**: it also removes other
people's applications and repos if they share the instance. No inputs,
no dry run - running it deletes.

## What it deletes (in this order)

| # | What | API call | Cascades to |
|---|---|---|---|
| 1 | Repo scan connections (ingestion jobs of type `source_control_discovery_job`) | `DELETE /core/api/v1/ingestion_jobs/{id}` | - (otherwise a scheduled re-scan would recreate the apps) |
| 2 | Every application | `DELETE /core/api/v2/applications/{id}` (`202`, async) | its repos, packages, CVEs, exposures and actions |
| - | Wait until no application is active or `delete_pending` (15s x 20 max) | `GET /core/api/v2/applications?filter=status:delete_pending` | |
| 3 | Every source repo still left (orphans) | `DELETE /core/api/v1/source_repos/{id}?is_cascade_delete=true` | packages, exposures, CVE findings |
| 4 | Every build artifact | `DELETE /core/api/v1/build_artifacts/{id}?is_cascade_delete=true` | packages, CVE findings |
| 5 | Every environment | `DELETE /core/api/v1/environments/{id}` | hosts/VMs and their OS packages/CVEs |
| 6 | Every action still left | `POST /ingestion/api/v1/actions/delete` (filter on `actions_identifier` + `target_component_identifier`) | - |
| 7 | Every Evidence store file not yet archived | `POST /ingestion/api/v1/table_of_contents/el/archive?el_path=<path>` (listed with `POST .../el/search` `{"page":1,"page_size":500}`) | - (archived, not deleted: there is no delete; the Evidence store page filters `is_archived = false`, so they disappear from it) |

Each delete runs inside a `try`: a failing one is recorded and the rest
carry on. At the end it lists everything again and returns
`{deleted, failed, left_in_concert}` in `result`; the run fails if any
delete failed.

**Not possible from the workflow: the Event log.** Concert has no API
to delete or archive Event log entries (`table_of_contents/lz` only has
search, summary and filters - `lz/archive` and `DELETE .../lz` are
`404`). It can only be emptied directly in Postgres - unsupported, see
"Event log (manual, database)" in `API_REFERENCE.md`. It refills on its
own: Concert logs scheduled jobs every hour.

**Never touched:** credentials (Concert connections, Workflows
authentications - a scan job's `credentials_id` is left alone when the
job is deleted) and workflows.

## Authentication

Every call goes through the `system/IBM/Concert v2/Concert HTTP Request`
block with the stored Workflows credential **`concertuser/Concert`**
(type `IBM/ConcertAPIKey`) - same one `BobShell Scan` uses. Nothing to
type when running it.

## How to import

Already imported on `itz-r87cnx` as `concertuser/User/Reset_All`
(2026-10-01, overwriting the first version that had `modo`/API key
inputs). Elsewhere: Workflows -> Import -> `Reset_All.zip`.

## Verification status (2026-10-01, itz-r87cnx)

- First real run (19:30 UTC): deleted 2 scan connections and 3
  applications (`banco-kokunas`, `sara-bastida-diaz`, `test-david`),
  `failed: []`, everything else already at 0 by cascade (including the
  orphan `AppMovil` repo). It left 7 Evidence store files behind ->
  step 7 added afterwards (not yet run).

- Every list call in the flow ran live through `Concert HTTP Request` +
  `concertuser/Concert` (read-only probe flow): all `200`, `result` comes
  back as a parsed object.
- The `foreach` -> `try` -> `if POST/DELETE` loop ran live with harmless
  operations: a `POST` succeeded, `DELETE`s on non-existent ids reached
  Concert, landed in `catch` with the error message, and the loop
  carried on.
- The source repo, build artifact and action deletes never had anything
  to act on in that run (everything had already cascaded), so they are
  still unexercised against real data; any failure shows in `result.failed`.
