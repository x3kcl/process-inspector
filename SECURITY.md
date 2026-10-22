# Security policy

## Supported versions

Security fixes go to the latest minor line only. That line is the newest `v*` release on GitHub Releases. Older minors are not patched in place.

## Reporting a vulnerability

Do not open a public issue for a suspected vulnerability.

Email the maintainer who owns [CODEOWNERS](.github/CODEOWNERS) with:

- what you can reach and what you cannot
- the version or image digest
- a minimal reproduction, if you have one

You should get an acknowledgement within 3 business days, and a decision (fix, accept, or need more information) within 14. A fix ships as a normal pull request once the report is confirmed. You will be credited unless you ask not to be.

## What the supply-chain gate already does

Nightly, and any pull request that changes a dependency or an image, runs Trivy on both shipping images and on the Maven and npm lockfiles, plus `scripts/check-npm-audit-allowlist.mjs`. HIGH and CRITICAL findings with an upstream fix fail the job.

An advisory with no safe in-range fix can sit in `frontend/npm-audit-allowlist.json` or a `.trivyignore.yaml` only with a justification and an expiry date. The allowlist script fails the run when that date passes. See [docs/OPERATIONS.md](docs/OPERATIONS.md) §8.

Dependabot opens weekly grouped updates. It does not merge them.
