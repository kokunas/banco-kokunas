# 04 - Verify & Notify

Custom Concert Workflow, authored using the same `system/FaaS/Python` block
style as IBM's official `trivy-github-scan` sample (single orchestration
script: install tooling, do the work, act on the result). Closes the loop
requested for the demo: confirm the app still works after remediation, and
notify by email that everything is OK.

## What it does

One `system/FaaS/Python` block (`verify_and_notify`):

1. Installs `git`, `maven`, `openjdk17`, `curl` (Alpine FaaS sandbox, same
   as the official Trivy sample).
2. Clones the branch to verify (typically `main`, after the log4j and SQLi
   remediation PRs from [Maven_Package_Upgrade](../Maven_Package_Upgrade) and
   [SQLi_Code_Remediation](../SQLi_Code_Remediation) are merged).
3. Runs `mvn -q -B test` - the **isofunctional test suite**
   (`LoanServiceTest` + `IsofunctionalWebTests`, 11 tests). This is the
   check that the mortgage simulator, transfers and customer search still
   behave correctly after both fixes.
4. Re-installs Trivy and re-scans the checked-out branch, checking whether
   `expected_cve` (default `CVE-2021-44228`) still shows up in the results.
5. Emails `notify_email` via SMTP (STARTTLS) with a pass/fail summary of
   both checks.
6. Exits non-zero (fails the workflow run, visible in Concert) if either
   check failed, so a bad remediation never silently reports success.

**Verified locally**: the embedded Python passes `py_compile` syntax
validation, and every individual step (`mvn test` against both the
vulnerable and the remediated `pom.xml`/`VulnerableSearchRepository.java`,
and a local Trivy scan) was run and confirmed working against this repo
during development (see [Maven_Package_Upgrade](../Maven_Package_Upgrade) and
[SQLi_Code_Remediation](../SQLi_Code_Remediation) for the exact `mvn test` output). What's *not*
independently verified here is the live `smtplib`/SMTP send or the Alpine
`apk` toolchain install, since those only run inside Concert's actual FaaS
sandbox - test this workflow with a real SMTP relay before relying on it
for a live demo.

## How to import

Concert Workflows console -> Workflows -> Import -> `Verify_And_Notify.zip`
(the picker greys out loose `.json` files - always import the `.zip`).

## Trigger payload for this demo

`input_json_data` and `notify_email` are already pre-filled as defaults on
the workflow - only the SMTP credentials and `gh_api_token` need to be
supplied at trigger time.

```json
{
  "input_json_data": {
    "repo_url": "https://github.com/kokunas/banco-kokunas",
    "branch": "main"
  },
  "expected_cve": "CVE-2021-44228",
  "notify_email": "kokuna+democoncert@gmail.com",
  "smtp_host": "<your SMTP host>",
  "smtp_port": 587,
  "smtp_username": "<SMTP username>",
  "smtp_password": "<SMTP password - store as a Concert secret/credential, never inline>",
  "smtp_from": "<a verified sender address for your SMTP provider>"
}
```

`smtp_password` (and `gh_api_token`) are declared with
`"subType": "password"` / `"notLogged": true` in the workflow variables,
same convention the official samples use for `gh_api_token` and
`concert_api_key` - store the real value in Concert's credential store, not
in the trigger payload literally.

## Performance note

`mvn test` downloads the full Maven dependency tree (~100-150MB) on a cold
sandbox, which can be slow inside a serverless FaaS environment. For a
snappier live demo, consider pointing this workflow at a FaaS worker image
with a pre-warmed `.m2` cache, or run `mvn test -o` against a repo mirror -
not required for correctness, just for demo pacing.
