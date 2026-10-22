# Contributing

One maintainer. Changes land as pull requests. `main` is protected: the `ci.yml` checks are required, force-push and deletion are blocked, history stays linear. The repository admin can bypass that ruleset in an emergency. The path is in [docs/RUNBOOK.md](docs/RUNBOOK.md) §0. Do not use it for a normal change.

## Dev loop

Read [docs/START-HERE.md](docs/START-HERE.md) once. Then:

1. `scripts/ci-runner.sh ensure` so a runner slot is online.
2. Backend unit tests: `cd backend && mvn -B test`.
3. Frontend: `cd frontend && npm ci && npm test && npm run lint`.
4. The cheap mirror of CI: `scripts/ci-local.sh` (add `--fix` for Spotless and Prettier, `--full` for the unit ladder).
5. Open a pull request. Watch it with `scripts/ci-watch.sh <pr-head-sha>`. Merge only when that SHA is green.

Engine integration tests need the compose harness. Read `.claude/skills/engine-harness` before adding one. Seed over REST.

A behavior change updates the matching section of `docs/SPECIFICATION.md`, `docs/ARCHITECTURE.md`, or `docs/OPERATIONS.md` in the same pull request.

## Secrets

Env refs only (`password-ref`). Never a secret in a config value, a log line, or an API response.

## License

Apache-2.0. See [LICENSE](LICENSE). The bpmn.io watermark stays. The build fails if a frontend source removes it.
