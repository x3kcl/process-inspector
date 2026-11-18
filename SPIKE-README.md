# SPIKE #492 S0 — NOT FOR MERGE

This branch (`spike/492-mcp-s0`) is throwaway evidence for the MCP spike S0 of issue #492.
It is never opened as a pull request and never queued. The written result lives on `main` as
`docs/reviews/MCP-SPIKE-S0-2026-11.md` (separate docs-only pull request).

## What is on this branch

| Path | Purpose |
|---|---|
| `backend/pom.xml` | `spring-ai-bom` 2.0.2 import, `spring-ai-starter-mcp-server-webmvc`, `spring-boot-starter-oauth2-resource-server` |
| `backend/src/main/resources/application.yml` | `spring.ai.mcp.server.protocol=STATELESS`, `type=SYNC` |
| `backend/src/main/resources/application-oidc.yml` | resource-server issuer + `audiences` for `/mcp` |
| `backend/src/main/java/io/inspector/spike/mcp/McpSpikeTools.java` | `whoami_engines` (read-only, wraps RbacAuthorizer + ReadScopeGate + EngineRegistry) and the `spike_admin_only` `@PreAuthorize` probe |
| `backend/src/main/java/io/inspector/spike/mcp/McpSpikeSecurityConfig.java` | dedicated `/mcp` filter chain (Basic on dev, bearer JWT + RFC 9728 metadata on oidc), Jwt → OAuth2AuthenticationToken adapter |
| `backend/src/test/java/io/inspector/spike/mcp/*` | the experiments (below) |
| `backend/src/test/resources/keycloak/mcp-spike-realm.json` | Keycloak realm: public PKCE client `mcp-cli`, audience client scope `inspector-mcp` |
| `backend/src/test/resources/spike/mcp-scope-mapping.yml` | group → scope mapping for the oidc tests |
| `spike-evidence/` | captured dependency trees and run logs |

## Re-running each experiment

All commands from `backend/`.

**U1 — Spring AI beside the Jackson 2 bridge**

```bash
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core,tools.jackson.core
mvn dependency:tree -Dincludes=org.springframework.ai,io.modelcontextprotocol.sdk
mvn -o test -Dspotless.check.skip=true            # the whole unit suite with the starter on the classpath
# OpenAPI drift gate: boot the jar against a throwaway Postgres, regenerate, diff
mvn -Dskip.surefire.tests=true package
INSPECTOR_DB_URL=jdbc:postgresql://localhost:<port>/inspector INSPECTOR_DB_PASSWORD=<pw> \
  ENGINE_A_PASSWORD=unused ENGINE_B_PASSWORD=unused SERVER_PORT=<free> java -jar target/*.jar
(cd ../frontend && INSPECTOR_BFF_URL=http://localhost:<free> npm run gen:api && git diff --exit-code src/api/schema.d.ts)
```

**U2 — security context inside a tool, both chains**

```bash
mvn -o test -Dtest='McpSpike*Test' -Dspotless.check.skip=true
```

- `McpSpikeDevChainSpringTest` — dev chain: principal, `@PreAuthorize` honoured/admitted, 401, role floor
- `McpSpikeOidcChainSpringTest` — oidc profile: scoped VIEWER, issuer pinning, role floor, PRM, 401 challenge
- `McpSpikeRealServerThreadTest` — real Tomcat: tool runs on the virtual request thread
- `McpSpikeWithoutDedicatedChainSpringTest` — what happens without a dedicated chain (CSRF 403)
- `McpSpikeArchCoverageTest` — the route arch guards do not see `@McpTool` classes

**U3a — Keycloak audience-bound token for a public PKCE client**

```bash
mvn -o verify -Dskip.surefire.tests=true -Dit.test=McpSpikeKeycloakIT -Dspotless.check.skip=true
# newer Keycloak with RFC 8707 resource indicators (experimental feature):
mvn -o verify -Dskip.surefire.tests=true -Dit.test=McpSpikeKeycloakIT -Dspotless.check.skip=true \
  -Dspike.keycloak.image=quay.io/keycloak/keycloak:26.8.2 -Dspike.keycloak.features=resource-indicators
```

Tokens are never printed; the tests log claim names and non-secret claim values only.
