# Delete_Repo_Data - scoped cleanup by git repo

Deletes **only** the Concert data that traces back to a single git repo,
identified by its exact `gh_repo_url`. Unlike `Reset_Demo` (which resets
this project's own two demo repos back to baseline), this workflow takes
any repo URL as input and is meant for one-off cleanup of a single
application's data - e.g. when you want to re-scan a repo from scratch,
or remove a mistaken/duplicate registration, without touching anything
else in a shared instance.

## What it does

1. Looks up the `source_repo` in Concert whose `repo_url` matches
   `gh_repo_url` **exactly**, via Concert's own server-side filter
   (`GET /core/api/v1/source_repos?repo_url=<base64 of gh_repo_url>` -
   confirmed live to return only the exact match).
2. Reads that `source_repo`'s own detail to find which application(s) it
   is registered under (`associations.applications`).
3. Deletes only those application(s) via
   `DELETE /core/api/v1/applications/{id}`. Deleting an application
   cascades to its own `source_repos`, packages, exposures (the
   risks/findings) and any remediation actions tied to them - confirmed
   live, same cascade `Reset_Demo` relies on - so this alone removes the
   application, its risks and its actions in one call, no separate
   delete needed for each.

**Deliberately not a blanket wipe.** Any application not linked to
`gh_repo_url` - another team's app, a manually-registered legacy app,
anything else in a shared instance - is never touched. If no
`source_repo` matches (never scanned, or already deleted), it prints
that and exits cleanly - safe to re-run.

## Trigger payload

```json
{
  "gh_repo_url": "https://github.com/kokunas/banco-kokunas",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000"
}
```

`gh_repo_url` must match the `repo_url` Concert stored at ingestion time
**exactly** (no `.git` suffix, no trailing slash) - the lookup is an
exact match, not fuzzy/substring, so a mismatched URL just finds nothing
and exits cleanly rather than deleting the wrong thing.

## How to import

Concert Workflows console -> Workflows -> Import -> `Delete_Repo_Data.zip`
(loose `.json` files are greyed-out/unselectable in Concert's import
picker - it only accepts `.zip`, same as every other workflow in this
project).

## Verified locally

Ran live against the real Concert instance during development:
- Instance had 2 applications (`cajamar`, `kokunas`) and 1 `source_repo`
  (`banco-kokunas`, linked to `kokunas`).
- Ran with `gh_repo_url = "https://github.com/kokunas/banco-kokunas"` ->
  found the 1 matching `source_repo`, resolved it to the `kokunas`
  application, deleted it.
- Confirmed after: `kokunas` and the `banco-kokunas` `source_repo` were
  both gone; `cajamar` (unrelated, no link to this repo) was left
  completely untouched.
