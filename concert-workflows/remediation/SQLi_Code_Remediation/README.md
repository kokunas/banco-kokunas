# 03 - Remediate: SQL Injection (unknown / non-CVE finding)

Custom Concert Workflow, hand-authored using the **same block schema** as
IBM's official `Maven_Package_Upgrade` sample (`system/Github/Repos/Get
Content`, `system/Github/Git/Create Ref`, `system/Github/Repos/Create Or
Update File Contents`, `system/Github/Pulls/Create`, `assign`, plain JS
`Function` blocks) - there is no stock IBM workflow for this because the
finding is not a dependency CVE, it's an application code weakness
(CWE-89). This is the "unknown / non-catalogued vulnerability" remediation
path requested for the demo, built to fit next to the log4j one.

## What it does

Same shape as [Maven_Package_Upgrade](../Maven_Package_Upgrade), targeting
`src/main/java/com/kokunas/bancokokunas/repository/VulnerableSearchRepository.java`
instead of `pom.xml`:

1. Fetch the file content + base branch SHA.
2. `Function_4` decodes it and replaces the **exact known-vulnerable
   snippet** (string-concatenated SQL in `searchCustomers`) with a
   parameterized-query version (JDBC `?` bind parameters). If the exact
   snippet isn't found (already patched, or the file drifted), the block
   throws instead of silently producing a no-op PR.
3. Create branch, commit, open PR (title: `fix(security): SQL Injection in
   customer search (CWE-89)`).

**Verified locally** (Node.js replay of the exact `Function_4` body against
the real file, then `mvn test`): produces valid Java, compiles, and all 11
isofunctional tests pass unchanged - legitimate customer search (`/search?
query=Maria`) keeps working, while the boolean-bypass payload (`' OR
'1'='1`) that previously dumped every customer row is neutralized.

## How to import

Concert Workflows console -> Workflows -> Import -> `SQLi_Code_Remediation.zip`
(the picker greys out loose `.json` files - always import the `.zip`).
Same GitHub credential/integration as [Maven_Package_Upgrade](../Maven_Package_Upgrade).

## Trigger payload for this demo

`assignee`, `email` and `input_json_data` are already pre-filled as
defaults on the workflow - only `auth` (the stored GitHub connection) or
a raw token needs to be supplied at trigger time.

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
      "finding": {
        "file": "src/main/java/com/kokunas/bancokokunas/repository/VulnerableSearchRepository.java",
        "cwe": "CWE-89",
        "rule_id": "java-sqli-string-concat-jdbctemplate",
        "severity": "HIGH"
      }
    }
  }
}
```

## Why this matters for the demo narrative

Trivy/Concert's default SCA scan (see [Trivy_GitHub_Scan](../../discovery/Trivy_GitHub_Scan)) will find
CVE-2021-44228 because it's a dependency version. It will **not** find this
SQL Injection, because it's not a dependency - it's how the application's
own code talks to the database. Pairing these two remediation workflows is
what shows the "known CVEs and unknown/non-catalogued vulnerabilities" both
being scanned, prioritized (in Concert's Vulnerability dimension / Arena
view) and auto-remediated through the same PR-based mechanism.
