# 02 - Remediate: log4j auto-remediation (known CVE)

Source: unmodified copy of IBM's official sample workflow
[`Maven_Package_Upgrade`](https://github.com/IBM/Concert/tree/main/concert-workflows/Vulnerability/Remediation)
(Apache-2.0, `github.com/IBM/Concert`). Generic - driven entirely by the
`input_json_data.recommendations` payload, no change needed for this repo.

## What it does

1. `getRepodetails` (assign) - reads `owner`/`org`/`base`/`repo` out of
   `input_json_data.recommendations.meta_data`.
2. `FetchPomContents` / `FetchBaseBranchSHA` (`system/Github/Repos/Get
   Content`, `system/Github/Repos/Get Branch`) - reads the current
   `pom.xml` and the SHA of the base branch.
3. A `Function` block decodes the base64 `pom.xml`, then for every entry in
   `input_json_data.recommendations.changes` (`target: "groupId:artifactId"`,
   `new_version`) rewrites the matching `<dependency>`/`<parent>` version.
4. `CreateGitRef` (`system/Github/Git/Create Ref`) - creates a new branch off
   `base`.
5. `CommitUpdatedPomToGit` (`system/Github/Repos/Create Or Update File
   Contents`) - commits the patched `pom.xml` to that branch.
6. `CreatePrWithChanges` (`system/Github/Pulls/Create`) - opens the PR.

## How to import

Concert Workflows console -> Workflows -> Import -> `Maven_Package_Upgrade.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
Needs a **GitHub** integration/credential configured in Concert with write
access to `kokunas/banco-kokunas` (a fine-grained PAT with `contents:write`
and `pull_requests:write` on that single repo is enough).

## Recommendation payload for this demo

`assignee`, `email` and `input_json_data` are already pre-filled as
defaults on the workflow - only `auth` (the stored GitHub connection) or
a raw token needs to be supplied at trigger time.

This is the shape Concert's own vulnerability-prioritization engine
generates when you click "Apply recommended fix" on the CVE-2021-44228 /
CVE-2021-45046 finding for `banco-kokunas` - reproduced here so the workflow can
also be triggered by hand for the demo:

```json
{
  "assignee": "kokunas",
  "email": "kokuna+democoncert@gmail.com",
  "repo_details": null,
  "input_json_data": {
    "recommendations": {
      "meta_data": {
        "org_name": "kokunas",
        "repo_name": "banco-kokunas",
        "base_branch": "main"
      },
      "changes": [
        {
          "target": "org.apache.logging.log4j:log4j-core",
          "previous_version": "2.14.1",
          "new_version": "2.24.3"
        },
        {
          "target": "org.apache.logging.log4j:log4j-api",
          "previous_version": "2.14.1",
          "new_version": "2.24.3"
        }
      ]
    }
  }
}
```

`2.24.3` is used instead of the historic emergency-fix `2.17.1` because it is
the current patched line of the 2.x branch (still Java 8+ compatible, unlike
the 3.x line) and picks up every subsequent log4j-core advisory, not only
Log4Shell.

## Result

Verified locally (Node.js replay of the exact `Function` block against this
repo's real `pom.xml`, then `mvn test`): the workflow rewrites both
`<dependency>` blocks to a literal `<version>2.24.3</version>`, overriding
the `${log4j.version}` property reference rather than editing the property
itself - so `<log4j.version>2.14.1</log4j.version>` is left behind, unused,
in `<properties>`. Harmless (Maven ignores it), but worth knowing before you
demo the diff live: it's a one-line cosmetic follow-up, not a functional
gap. The resulting `pom.xml` is valid and all 11 isofunctional tests pass
against it unchanged.

The PR opened against `kokunas/banco-kokunas` is what the
[Verify_And_Notify](../Verify_And_Notify) workflow validates (isofunctional
test suite + re-scan) before notifying.
