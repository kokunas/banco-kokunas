# Reset_Demo - repeatability

Run this **before every demo**. Makes two things true again:

1. **The code is vulnerable again.** Reverts `pom.xml` (and
   `VulnerableSearchRepository.java`) on `main` back to the repo's own
   `vulnerable-baseline` git tag
   ([banco-kokunas](https://github.com/kokunas/banco-kokunas/releases/tag/vulnerable-baseline)),
   via direct commits through the GitHub Contents API (no PR - this is
   demo housekeeping, not a reviewed change). Each push automatically
   re-triggers `build-and-push.yml`, republishing the vulnerable image to
   GHCR. Idempotent: if a file already matches the baseline, it's left
   alone (verified locally - reruns log `"already matches ... nothing to
   reset"` instead of creating no-op commits).
2. **Only this demo's applications are gone from Concert - nothing
   else.** Deletes just the applications named in `application_names`
   (default: `banco-kokunas`, `legacy-win-fileserver`,
   `legacy-core-gateway`, `legacy-web-portal`) via Concert's core API, so
   the next scan starts from nothing for this demo. Deliberately **not**
   a blanket wipe of the whole instance - this is safe to run against a
   Concert shared with other teams/demos, since anything not in that
   name list is left completely untouched. Deleting an application
   cascades to its own `source_repos` (confirmed live), so there's no
   separate step needed for those.

## Why not just re-baseline in git and leave Concert alone?

Because the demo's whole point is showing Concert **discover** the CVEs
from scratch (connect to GitHub, scan, see findings appear, prioritize).
If the `banco-kokunas` application/source_repo already exists with old
scan data, that first "watch it get discovered" beat doesn't land the
same way for a repeat audience.

## After running this, redeploy the running pods

Reset_Demo fixes the **source** and the **image** (new vulnerable image
lands in GHCR within ~1-2 minutes), but the **already-running** OpenShift
pods don't restart themselves. Before the next demo, roll it:

```
oc rollout restart deployment/banco-kokunas-app -n banco-kokunas
```

## How to import

Concert Workflows console -> Workflows -> Import -> `Reset_Demo.zip`
(loose `.json` files show up greyed-out/unselectable in Concert's import
picker - it only accepts `.zip`, even for a single-flow bundle with no
subflows, matching how IBM distributes its own single-flow samples like
`trivy-github-scan.zip`).

## Trigger payload for this demo

`repos` and `application_names` are pre-filled with this demo's defaults
and don't need editing. Fill in the two masked/password fields each
run - `gh_api_token` (the `github_pat_banco-kokunas` token) and
`concert_api_key` - the same way every other workflow in this project
takes its GitHub token (a dedicated top-level input in Concert's Run
form), not embedded inside a JSON blob:

```json
{
  "repos": "[{\"gh_repo_url\": \"https://github.com/kokunas/banco-kokunas\", \"baseline_ref\": \"vulnerable-baseline\", \"target_branch\": \"main\", \"reset_files\": [\"pom.xml\", \"src/main/java/com/kokunas/bancokokunas/repository/VulnerableSearchRepository.java\"]}]",
  "gh_api_token": "<github_pat_banco-kokunas token>",
  "application_names": "[\"banco-kokunas\", \"legacy-win-fileserver\", \"legacy-core-gateway\", \"legacy-web-portal\"]",
  "concert_url": "https://concert-concert.apps.itz-4j78fp.pok-lb.techzone.ibm.com",
  "concert_api_key": "<Concert API key>",
  "concert_instance_id": "0000-0000-0000-0000"
}
```

**v6 fix**: earlier versions embedded `gh_api_token` as a field inside
each object in the `repos` JSON string, left blank by default - easy to
forget to hand-edit, and forgetting produced a silent-looking
`Bad credentials` 401 from GitHub with no hint why (confirmed live: a
run with the default, un-edited `repos` value logged an empty
`token: ` and failed the very first file fetch). The token is now its
own dedicated password input (`gh_api_token`) - matching the
`gh_api_token` field on `Trivy_GitHub_Scan` and every other workflow
here, and mistake-proofing this the same way Concert's Run form already
mistake-proofs those (masked field, impossible to miss).

## Verified locally

Ran this exact logic multiple times against the real repo and the real
Concert instance during development:
- **Idempotent run**: the file already matched baseline -> logged
  `already_reset`, no commits created; Concert had 1 application + 1
  source_repo -> both deleted successfully.
- **Real revert run**: pushed a simulated merged fix (log4j bumped to
  2.24.3) directly to `main`, then ran Reset_Demo again -> it correctly
  detected the drift and reverted `pom.xml` via a real authenticated PUT
  to the GitHub Contents API (commit `1cc3ca2`), while leaving the
  already-correct SQLi file untouched.
- **Shared-instance scoping**: confirmed live that deleting an
  application named in `application_names` never touches applications
  with other names (verified with a mix of matching and non-matching
  application names present in the same instance) - only the ones this
  demo owns get removed.
