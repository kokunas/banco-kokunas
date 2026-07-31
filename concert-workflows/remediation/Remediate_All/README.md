# Remediate_All - nested, one-click remediation

Master/orchestrator Concert Workflow. Nests the three previously-separate
workflows into a single trigger, so the presenter doesn't click through
them one by one:

1. `./Remediate_All_Helper/Maven_Package_Upgrade` (copy of
   [Maven_Package_Upgrade](../Maven_Package_Upgrade)) - opens PR #1 (log4j fix).
2. `./Remediate_All_Helper/SQLi_Code_Remediation` (copy of
   [SQLi_Code_Remediation](../SQLi_Code_Remediation)) - opens PR #2 (SQLi fix).
3. `merge_prs` (`system/FaaS/Python`) - merges both PRs via the **GitHub
   REST API directly** (`PUT /repos/{owner}/{repo}/pulls/{n}/merge`).
   There is no native "merge PR" block in Concert's published catalog
   (checked against `github.com/IBM/Concert`'s samples) - this calls the
   GitHub API the same way `curl`-based blocks already do elsewhere in
   this demo. Retries with backoff, since GitHub can return 405 for a few
   seconds right after PR creation while mergeability is computed.
4. `./Remediate_All_Helper/Verify_And_Notify` (copy of
   [Verify_And_Notify](../Verify_And_Notify)) - now runs against `main`,
   which contains **both** merged fixes: isofunctional tests + a fresh
   Trivy scan, then the result email.

## Why a zip, not a plain JSON

Concert Workflows resolves `"action": "./Remediate_All_Helper/X"`
references relative to the importing bundle - the three subflows must
travel in the same import as the master, in a `<FlowName>_Helper/`
subfolder next to `<FlowName>.json` (same convention IBM uses for its own
`Remediation_Master.zip`). **Import `Remediate_All.zip`**, not the loose
`Remediate_All.json` in this folder (that copy is here for readability/diff
review only).

## How to import

Concert Workflows console -> Workflows -> Import -> `../Remediate_All.zip`.
Needs the same GitHub credential as the individual remediation workflows,
now also requiring **pull-requests: write** (to merge, not just open), plus
the SMTP credentials from [Verify_And_Notify](../Verify_And_Notify).

## Trigger payload for this demo

**Every field below is already pre-filled as the workflow's default value
except the secrets** (`gh_api_token`, `smtp_password`, and the other SMTP
credentials) - deliberately left blank since they're not something to
commit into a public GitHub repo. On each run you only need to paste
those:

```json
{
  "assignee": "kokunas",
  "email": "kokuna+democoncert@gmail.com",
  "input_json_data": {
    "recommendations": {
      "meta_data": {
        "org_name": "kokunas",
        "repo_name": "banco-kokunas",
        "base_branch": "main"
      },
      "changes": [
        { "target": "org.apache.logging.log4j:log4j-core", "previous_version": "2.14.1", "new_version": "2.24.3" },
        { "target": "org.apache.logging.log4j:log4j-api", "previous_version": "2.14.1", "new_version": "2.24.3" }
      ],
      "finding": {
        "file": "src/main/java/com/kokunas/bancokokunas/repository/VulnerableSearchRepository.java",
        "cwe": "CWE-89",
        "rule_id": "java-sqli-string-concat-jdbctemplate",
        "severity": "HIGH"
      }
    }
  },
  "gh_repo_url": "https://github.com/kokunas/banco-kokunas",
  "gh_api_token": "<GitHub token - contents:write + pull_requests:write>",
  "expected_cve": "CVE-2021-44228",
  "notify_email": "kokuna+democoncert@gmail.com",
  "smtp_host": "<your SMTP host>",
  "smtp_port": 587,
  "smtp_username": "<SMTP username>",
  "smtp_password": "<SMTP password - Concert secret>",
  "smtp_from": "<a verified sender address>"
}
```

## Verified locally before packaging

- Each subflow's own block logic was already independently verified
  against this repo (see [Maven_Package_Upgrade](../Maven_Package_Upgrade) and
  [SQLi_Code_Remediation](../SQLi_Code_Remediation)) - `mvn test` green on both fixes.
- `merge_prs`'s embedded Python passes `py_compile` syntax validation.
- The GitHub "merge a pull request" REST call
  (`PUT /repos/{owner}/{repo}/pulls/{pull_number}/merge`) is a standard,
  stable GitHub API - not re-tested live here to avoid opening/merging a
  throwaway PR against the demo repo, but the same curl-based call pattern
  is exercised end-to-end by [Reset_Demo](../../reset-demo/Reset_Demo), which performed
  real authenticated GitHub Contents API writes successfully during
  development.

## Demo narrative

With this workflow, the full lifecycle is just **two** Concert Workflow
triggers: [`Trivy_GitHub_Scan`](../../discovery/Trivy_GitHub_Scan) (scan), then `Remediate_All`
(remediate both findings, merge, verify, notify - nested end to end,
including the isofunctional tests). Prioritization happens automatically
in the Concert console between the two.
