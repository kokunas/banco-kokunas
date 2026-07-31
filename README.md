# Banco Kokunas

IBM Concert remediation-lifecycle demo: a real Spring Boot banking
application, deliberately shipping known CVEs and one unknown/non-cataloged
vulnerability, with an isofunctional test suite used to prove that automated
remediation doesn't break the app.

| | |
|---|---|
| Role | Mortgage-loan simulation/application, funds transfers, customer search |
| Stack | Spring Boot 2.7.x (Java 17), Thymeleaf, Spring Data JPA, PostgreSQL 16 |
| Exposure | Public (OpenShift Route) |

It exists to demonstrate the full Concert lifecycle on a small application:
**scan -> prioritize -> auto-remediate -> verify -> notify**, using real
[Concert Workflows](concert-workflows/) (not GitHub Actions) for every step
of the automation.

## The vulnerabilities

| Class | Where | Detail |
|---|---|---|
| **Known CVE** | [`pom.xml`](pom.xml) | `log4j-core`/`log4j-api` pinned to `2.14.1` (CVE-2021-44228 "Log4Shell", CVE-2021-45046). Reachable sink: [`AuditLogger`](src/main/java/com/kokunas/bancokokunas/config/AuditLogger.java) logs the `X-Channel` request header via `org.apache.logging.log4j.LogManager` on every `/search` call. |
| **Known CVE** | [`pom.xml`](pom.xml) | `spring-framework.version` pinned to `5.3.17` (CVE-2022-22965 "Spring4Shell"). This is why the app runs on the `spring-boot-starter-parent:2.7.18` line rather than Boot 3.x - Boot 3 requires Spring Framework 6.x (`jakarta.*` namespace), which isn't affected by this CVE, so the vulnerable version genuinely can't coexist with Boot 3. |
| **Unknown / non-CVE** | [`VulnerableSearchRepository`](src/main/java/com/kokunas/bancokokunas/repository/VulnerableSearchRepository.java) | CWE-89 SQL Injection: `searchCustomers(term)` concatenates user input directly into the SQL string. Confirmed locally: `GET /search?query=zzznomatch` returns nothing, `GET /search?query=' OR '1'='1` dumps every customer row. A dependency scanner will **not** catch this - it's not a library version, it's how the app talks to the database. |

## The app

### Banco Kokunas

- **Dashboard** (`/`) - portfolio overview: customers, mortgage volume, transfers.
- **Mortgage simulator** (`/loans/new`) - French-amortization monthly
  payment calculator + 80% loan-to-value eligibility check + application
  submission (`/loans`, `/loans/{id}`).
- **Transfers** (`/transfers`, `/transfers/new`) - IBAN-to-IBAN transfer
  creation and ledger.
- **Customer search** (`/search`) - branch-office quick lookup by name/NIF.
  **This is the SQLi-vulnerable endpoint.**

[`FraudCheckClient`](src/main/java/com/kokunas/bancokokunas/client/FraudCheckClient.java)
optionally calls an external fraud/credit-risk scoring service over HTTP
before approving a transfer or mortgage, with an 800ms timeout and fails
open (approves normally) if that service is unreachable or not deployed - it
is not part of this demo's scan/remediate lifecycle and isn't required to be
running.

## Isofunctional tests

Prove that remediation changes *how* the code does its job, not *what*
it does - the same suite passes both before and after each fix.

[`LoanServiceTest`](src/test/java/com/kokunas/bancokokunas/LoanServiceTest.java)
(3, pure business-logic unit tests) +
[`IsofunctionalWebTests`](src/test/java/com/kokunas/bancokokunas/IsofunctionalWebTests.java)
(8, full MockMvc integration tests: dashboard, mortgage simulation/
application, transfers, customer search). Run: `mvn test`.

`Verify_And_Notify` (part of `Remediate_All`) runs this suite against the
merged fix and only reports success if every test still passes.

## Running locally

```bash
docker compose up --build
```
Then open <http://localhost:8080>. Postgres runs on an `internal: true`
compose network (not published beyond `5432` on the host) - see [k8s/](k8s)
for the production-shaped topology where the database has no external
exposure at all.

Try the SQLi live:
```bash
curl -s "http://localhost:8080/search?query=zzznomatch"          # no results
curl -s -G "http://localhost:8080/search" --data-urlencode "query=' OR '1'='1"  # dumps all customers
```

## Container images & deployment topology

- Source at this repo, image at `ghcr.io/kokunas/banco-kokunas`
  (built by [`Dockerfile`](Dockerfile) + `.github/workflows/build-and-push.yml`
  on every push to `main`). Deployed in the `banco-kokunas` OpenShift
  namespace as `banco-kokunas-app` (public Route, edge TLS) +
  `banco-kokunas-db` (Postgres, `ClusterIP` only) - see [k8s/](k8s).

## The remediation lifecycle

All automation lives in [concert-workflows/](concert-workflows/) as real,
importable Concert Workflow JSON/zip definitions, organized by stage:

```
concert-workflows/
├── discovery/     - find things: scan repos/images, register CMDB-style apps
├── remediation/   - fix things: individual steps + the orchestrator
└── reset-demo/    - clean up between demo runs
```

See that folder's README for the full workflow table, trigger payloads,
and what was verified against the real repo and the real Concert API.
**Live demo shape - one reset, then scan, then remediate:**

0. **Reset** (run before every demo):
   [`Reset_Demo`](concert-workflows/reset-demo/Reset_Demo) - reverts the
   repo's code to its `vulnerable-baseline` git tag, and deletes only
   this demo's applications from Concert (safe on an instance shared with
   other teams - nothing else gets touched).
1. **Scan**: [`Trivy_GitHub_Scan`](concert-workflows/discovery/Trivy_GitHub_Scan) -
   trigger live, in front of the audience, with `application_name:
   banco-kokunas`: Concert connects to GitHub, scans with Trivy, and the
   application appears with real CVEs, already prioritized (this workflow
   also sets business criticality on the application, which Concert factors
   into prioritization alongside raw CVE severity).
2. *(optional)* [`Simulate_CMDB_Applications`](concert-workflows/discovery/Simulate_CMDB_Applications) -
   registers 3 fictional legacy applications (naming WannaCry/Heartbleed/
   Shellshock in their descriptions) to enrich the portfolio view - these
   stay unremediated by design, contrasting with the automated fix below.
3. **Prioritize**: done in the Concert console (Vulnerability dimension /
   Arena view) - no workflow needed.
4. **Remediate + merge + verify + notify, nested**:
   [`Remediate_All`](concert-workflows/remediation/Remediate_All) (log4j +
   Spring4Shell + SQLi, 3 PRs auto-merged in one trigger). Neither Concert's
   block catalog nor IBM's published samples have a native "merge PR" block,
   so it calls the GitHub REST API directly to merge, then runs the
   isofunctional test suite and a fresh Trivy scan against `main`, and
   emails the outcome.
5. Roll the running pods so they pick up the newly-published fixed image:
   ```bash
   oc rollout restart deployment/banco-kokunas-app -n banco-kokunas
   ```

The individual remediation steps
([Maven_Package_Upgrade](concert-workflows/remediation/Maven_Package_Upgrade),
[Spring_Property_Upgrade](concert-workflows/remediation/Spring_Property_Upgrade),
[SQLi_Code_Remediation](concert-workflows/remediation/SQLi_Code_Remediation),
[Verify_And_Notify](concert-workflows/remediation/Verify_And_Notify)) still
exist standalone if you'd rather demo any single stage in isolation.

## Known IBM Concert platform limitations hit while building this

A few things behave unexpectedly on this Concert 3.0.0 install regardless
of how this demo's own workflows are written - e.g. `build_artifacts`
registration always fails, CVE/certificate data never links for an
application with no real reachable backing repository, and a couple of
API schema/documentation mismatches. None of these block the demo, but
they shape some of the workarounds above (like registering the CMDB
apps via `POST /applications` with a descriptive narrative, rather than
trying to attach structured CVE findings to them). See
[`IBM_CONCERT_BUG_REPORT.md`](IBM_CONCERT_BUG_REPORT.md) for the full
writeup (kept as-authored, referencing this repo's former name/layout at
the time each issue was reproduced).

## What's still needed from you to run this live

- A Concert Workflows instance (API Gateway URL, API key, instance ID).
- A fine-grained GitHub token (`contents:write` + `pull_requests:write`)
  for `kokunas/banco-kokunas`.
- An SMTP relay for the notification step (currently disabled pending
  setup - `Verify_And_Notify` prints the notification instead of sending it).
- That's it - the app doesn't need to be pre-registered in Concert;
  `Trivy_GitHub_Scan` creates it by name on first run after a `Reset_Demo`.
