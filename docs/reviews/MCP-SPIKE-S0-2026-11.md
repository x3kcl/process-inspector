# MCP spike S0 — result (issue #492)

> **Status: spike result, no build.** Owner decision 2026-11-18 on D1 of `docs/AGENTIC-ACCESS.md`:
> spike only. This file answers the three S0 unknowns with evidence. No production code reached
> `main`. The throwaway code and tests are on the branch `spike/492-mcp-s0` (head `d50b052b`,
> marked not for merge; `SPIKE-README.md` there says how to re-run each experiment). The build
> decision and D2–D10 are taken from this result.

## Summary

| Unknown | Answer | One line of evidence |
|---|---|---|
| U1 Spring AI MCP server beside the Jackson 2 bridge on Boot 4.1 | **Yes**, with three fixes for a build | Full unit suite 2360/0/0 with the starter on the classpath; OpenAPI drift gate no diff; controllers still on `MappingJackson2HttpMessageConverter`, MCP messages on the starter's own Jackson 3 `mcpServerJsonMapper` |
| U2 Security context inside a tool, both chains | **Yes** | Tool runs on the virtual request thread (`tomcat-handler-0`, `virtual=true`) and sees the principal; scoped VIEWER sees only its engine on the oidc profile; `@PreAuthorize` on a tool method is honoured |
| U3 Per-user, audience-bound token for a non-browser MCP client | **Partly** | Keycloak 25: public client + PKCE S256 + audience client scope → `aud=https://inspector.test/mcp`, bearer accepted by `/mcp`, roles resolve through the SPA's group mapping. RFC 8707 `resource` alone is ignored by Keycloak 25 and rejected unless registered on 26.8.2. Entra ID: documentation only |

## Versions checked (2026-11-18)

| Item | Fact | Source |
|---|---|---|
| Spring AI | **2.0.2** is the latest stable (Maven Central directory dated 2026-10-22). 2.1.0-RC2 (2026-11-05) is a preview. | repo1.maven.org `spring-ai-starter-mcp-server-webmvc` metadata; docs.spring.io/spring-ai/reference (selected version 2.0.2) |
| MCP Java SDK | Spring AI 2.0.2 pulls **`io.modelcontextprotocol.sdk:mcp` 2.1.0** with `mcp-json-jackson3` (the design note said 2.0.0). SDK 2.2.0 is on Maven Central since 2026-11-13. | `dependency:tree` (below); Maven Central metadata |
| Protocol revision served | `initialize` answers `"protocolVersion":"2025-11-25"`. | captured exchange on the booted spike BFF |
| MCP specification | Latest revision **2026-07-28**. Authorization: servers MUST serve RFC 9728 Protected Resource Metadata, MUST validate the token audience, MUST NOT accept or pass through other tokens; clients MUST send the RFC 8707 `resource` parameter in authorization and token requests and use PKCE; client registration by pre-registration, Client ID Metadata Documents (SHOULD) or Dynamic Client Registration (MAY, deprecated). The device authorization grant is not part of the specified flow. | modelcontextprotocol.io/specification/2026-07-28/basic/authorization and …/client-registration |
| Spring Security | 7.1.2 (Boot 4.1.2) ships `OAuth2ProtectedResourceMetadataFilter` (RFC 9728) and a `resource_metadata` parameter on the `WWW-Authenticate` challenge (`BearerTokenAuthenticationEntryPoint`, since 7.1). | spring-security-oauth2-resource-server 7.1.2 sources |
| `spring-ai-community` mcp-security | `org.springaicommunity:mcp-server-security` 0.1.14 (2026-08-06) depends on `mcp-core` 2.0.0 and Spring Security 7.1.0 — behind the SDK Spring AI 2.0.2 ships. Not used; plain Spring Security covered the server side. | Maven Central POM |
| Keycloak | Resource indicators (RFC 8707) and Client ID Metadata Documents are experimental features (`--features=resource-indicators`, `--features=cimd`); the documented workaround is an optional client scope with an Audience mapper. | keycloak.org/securing-apps/mcp-authz-server |

## U1 — Does Spring AI's MCP server work beside the Jackson 2 bridge on Spring Boot 4.1?

**Answer: yes.** The starter does not pull Jackson 3 into the controllers' request path. Three
things need a fix in a build (listed under "what would have to be built").

**Set-up on the spike branch.** `spring-ai-bom` 2.0.2 imported, `spring-ai-starter-mcp-server-webmvc`,
`spring.ai.mcp.server.protocol=STATELESS`, `type=SYNC`; one read-only tool `whoami_engines` that
returns the caller and the engines they may read (wraps `RbacAuthorizer`, `ReadScopeGate`,
`EngineRegistry.allForDisplay()` — the `EnginesController.list` read).

**Evidence.**

- Jackson majors on the classpath — **unchanged by the starter.** Both majors were already present on `main`:

  ```
  # before and after (dependency:tree -Dincludes=com.fasterxml.jackson.core,tools.jackson.core)
  +- org.flywaydb:flyway-core:jar:12.4.0:compile
  |  \- tools.jackson.core:jackson-databind:jar:3.1.7:compile
  \- net.logstash.logback:logstash-logback-encoder:jar:8.1:compile
     \- com.fasterxml.jackson.core:jackson-databind:jar:2.21.7:compile
  ```

  The starter adds the MCP SDK's Jackson 3 binding:

  ```
  \- org.springframework.ai:spring-ai-starter-mcp-server-webmvc:jar:2.0.2:compile
     +- org.springframework.ai:spring-ai-mcp:jar:2.0.2:compile
     |  +- io.modelcontextprotocol.sdk:mcp:jar:2.1.0:compile
     |  |  \- io.modelcontextprotocol.sdk:mcp-json-jackson3:jar:2.1.0:compile
     \- org.springframework.ai:mcp-spring-webmvc:jar:2.0.2:compile
        \- io.modelcontextprotocol.sdk:mcp-core:jar:2.1.0:compile
  ```

- Who serialises what (`McpSpikeJacksonCoexistenceSpringTest`, pass):
  - existing controllers: `org.springframework.http.converter.json.MappingJackson2HttpMessageConverter` (Jackson 2); no Jackson 3 JSON converter in the MVC list;
  - MCP messages: the transport is `WebMvcStatelessServerTransport`, a `RouterFunction` (not a controller), which writes the JSON-RPC response as a pre-serialised string from the Jackson 3 bean `mcpServerJsonMapper`;
  - beans: one Jackson 2 `ObjectMapper` (`jackson2ObjectMapper`), Jackson 3 `JsonMapper`s `[mcpServerJsonMapper, jacksonJsonMapper]`. `mcpServerJsonMapper` is registered `defaultCandidate=false`, so no unqualified injection picks it. **No bean conflict.**
- One converter changed: the YAML converter. `main` has `MappingJackson2YamlHttpMessageConverter`; with the starter the transitive `tools.jackson.dataformat:jackson-dataformat-yaml` 3.1.7 swaps in `JacksonYamlHttpMessageConverter` (Jackson 3). Only `application/yaml` responses are affected (baseline captured on `origin/main` with the same probe).
- The BOM import **downgrades two managed versions**: `swagger-annotations-jakarta` 2.2.55 → 2.2.38 and `antlr4-runtime` 4.13.2 → 4.13.1. The drift gate still showed no diff, but a build must not let a third-party BOM override Boot/springdoc versions.
- 26 new artifacts from the MCP starter (plus 3 for the resource server), among them `reactor-core` 3.8.8, `spring-messaging`, `jtokkit` (a tokenizer), `ST4` + `antlr-runtime` 3.5.3, `jsonschema-generator` 5.0.0, `json-schema-validator` 3.0.6, `snakeyaml-engine`. Full diff in `spike-evidence/list-diff.txt` on the spike branch.
- `mvn -o test` on the spike branch: **2360 tests, 0 failures, 0 errors** (`spike-evidence/full-unit-run-summary.txt`), the dev/test-profile `@SpringBootTest` contexts all boot.
- Dev profile boot: the packaged jar started against a throwaway Postgres in 17.4 s.
- OpenAPI drift gate (`npm run gen:api` against the booted spike BFF, then `git diff --exit-code src/api/schema.d.ts`): **no diff.** `/mcp` does not appear in `/v3/api-docs` (it is a `RouterFunction`).
- Captured exchange on the booted BFF (dev chain, Basic redacted):

  ```
  POST /mcp {"method":"initialize",…}        -> 200 {"result":{"protocolVersion":"2025-11-25",…,"serverInfo":{"name":"process-inspector-spike",…}}}
  POST /mcp tools/call whoami_engines        -> 200 {"result":{"content":[{"type":"text","text":"{\"thread\":\"tomcat-handler-8\",\"virtual\":true,\"principal\":\"viewer\",…,\"engines\":{\"engine-a\":\"VIEWER\",\"engine-b\":\"VIEWER\",\"engine-7\":\"VIEWER\"}}"}],"isError":false}}
  GET  /mcp                                  -> 405
  POST /mcp {not json                        -> 400 {"jsonRpcError":{"code":-32600,"message":"Invalid message format"},"cause":null,"stackTrace":[{…"className":"io.modelcontextprotocol.spec.McpError$Builder"…  (full Java stack trace)
  ```

**The defect the last line shows.** On a malformed body the transport returns the `McpError`
*exception object* as the response body, and the MVC Jackson 2 converter serialises it as a
bean — the full Java stack trace goes to the caller (authenticated callers only, on the spike's
chain). A build must replace the transport's error responses.

**Hand-rolled fallback.** Not needed. If it ever were: a single `@RestController` speaking
JSON-RPC 2.0 for `initialize`, `tools/list`, `tools/call` (stateless, no SSE), schema per tool,
error mapping — about 3–4 dev-days, and it would sit inside the existing route guards.

## U2 — Is the Spring Security context available inside a tool method, on both chains?

**Answer: yes**, on the dev chain and on the `oidc` profile, from a session principal and from a
bearer-token principal.

**Threading.** Spring AI sets `immediateExecution(true)` on the stateless server when the
environment is a servlet environment (`McpServerStatelessAutoConfiguration`), so a SYNC tool runs
on the request thread. On a real embedded Tomcat (`McpSpikeRealServerThreadTest`, pass) the tool
reports `"thread":"tomcat-handler-0","virtual":true` and the principal the filter chain set.
`SecurityContextHolder` is a thread-local, so it propagates by construction. (MockMvc runs on the
JUnit thread and reports `main`; that proves nothing about threading and is not used as evidence.)

**Tests on the spike branch (all pass).**

| Test | Chain | Shows |
|---|---|---|
| `McpSpikeDevChainSpringTest.theToolSeesTheDevPrincipalOnTheRequestThread` | dev (Basic) | principal `viewer`, `UsernamePasswordAuthenticationToken`, VIEWER on every test engine |
| `McpSpikeDevChainSpringTest.preAuthorizeOnAToolMethodRefusesAViewer` / `…AdmitsAnAdmin` | dev | `@PreAuthorize("@rbac.atLeast(authentication,'ADMIN')")` on an `@McpTool` method: VIEWER gets `isError:true` "Access Denied", ADMIN gets the payload |
| `McpSpikeDevChainSpringTest.anUnauthenticatedMcpRequestIsRefusedWith401` | dev | 401 |
| `McpSpikeDevChainSpringTest.aRolelessDevPrincipalGetsTheRoleFloorRefusal` | dev | the `metrics` account (no ladder rung) gets `no-engine-role` |
| `McpSpikeOidcChainSpringTest.aViewerOnEngineASeesEngineAOnlyUnderScopeEnforcement` | oidc, bearer | group `team-a-viewers` → VIEWER on `probe-dev`; `scopeEnforced=true`; engines = `{probe-dev: VIEWER}` only |
| `McpSpikeOidcChainSpringTest.aGlobalAdminGroupSeesEveryEngine` | oidc, bearer | ADMIN on both engines |
| `McpSpikeOidcChainSpringTest.aForeignIssuerResolvesNoGroupsSoNoRole` | oidc, bearer | issuer pinning still bites: foreign `iss` → no groups → `no-engine-role` |
| `McpSpikeOidcChainSpringTest.aNoRoleUserGetsTheRoleFloorRefusal` | oidc, bearer | HTTP 200 with `isError:true` and `no-engine-role` (#761 floor) |
| `McpSpikeOidcChainSpringTest.preAuthorizeOnAToolIsHonouredForABearerViewer` | oidc, bearer | "Access Denied" |
| `McpSpikeOidcChainSpringTest.anSpaOidcSessionPrincipalAlsoResolvesInsideTheTool` | oidc, `oidcLogin()` | the SPA-shaped `OidcUser` principal resolves to `{probe-dev: VIEWER}` |
| `McpSpikeOidcChainSpringTest.anUnauthenticatedMcpRequestGets401WithTheResourceMetadataChallenge` | oidc | `401 WWW-Authenticate: Bearer resource_metadata="http://localhost/.well-known/oauth-protected-resource/mcp"` |
| `McpSpikeWithoutDedicatedChainSpringTest.anAnonymousMcpPostIs403ByCsrfNot401` | dev, no `/mcp` chain | without its own chain an anonymous MCP POST is stopped by the SPA's CSRF filter: 403, no challenge — an MCP client could never start OAuth discovery |
| `McpSpikeArchCoverageTest.theRouteGuardsDoNotSeeMcpTools` | — | `EngineScopedRouteGuardArchTest` and `EngineRoleExpressionGuardArchTest` walk only `@RestController` classes; the tool iterates the registry and both guards pass without seeing it |

The oidc tests run the real `oidc` profile the way `MetricsGrantOidcChainSpringTest` does (static
client registration, no IdP), with the real `RbacAuthorizer`, the real `OidcGroupResolver` and the
real file mapping source (`spike/mcp-scope-mapping.yml`); only the JWT decoder is a stub
(signature and audience validation are U3's job). No new authorisation code: the tool calls
`rbac.atLeast`, `gate.readableEngineIds`, `rbac.hasRoleOn` exactly as the controllers do.

**Why `@PreAuthorize` works, and why that needs a pin.** The `@McpTool` scanner is a
`BeanPostProcessor` that registers the bean it receives in `postProcessAfterInitialization`. It is
unordered, so it runs after the method-security proxy creator and registers the proxy; the
reflective call then goes through the interceptor. That is an ordering property, not a contract.
A build keeps a test like `preAuthorizeOnAToolMethodRefusesAViewer` as a permanent guard so a
library upgrade that captures the raw bean fails loudly instead of silently opening every tool.

**Other findings.**

- `tools/list` is not filtered per caller: a VIEWER is shown `spike_admin_only` and is refused only
  on the call.
- A refusal inside a tool becomes `isError:true` with the exception message, printed twice
  ("Access Denied\nAccess Denied"). `NoEngineRoleAdvice` is a `@RestControllerAdvice` and does not
  see tool exceptions; the tool has to produce the `no-engine-role` reason itself.
- **Principal-shape trap.** `RbacAuthorizer.grantsFor` resolves IdP groups only for an
  `OAuth2AuthenticationToken`; any other `Authentication` has its `ROLE_*` authorities read as
  **global** grants (the dev-ladder branch). Spring's default bearer principal
  (`JwtAuthenticationToken`) carries `SCOPE_*` authorities, so it would resolve to no grants (fail
  closed); a converter that mapped IdP roles to `ROLE_*` would grant them fleet-wide. The spike
  adapts the JWT to an `OAuth2AuthenticationToken` over an `OAuth2User` whose attributes are the
  token claims, with no authorities, so the unchanged `OidcGroupResolver` (issuer pinning,
  overage) and mapping source apply.
- The stateless transport bean built by the autoconfiguration has a **no-op security validator**
  (`ServerTransportSecurityValidator.NOOP`) and an empty transport context. A request with
  `Origin: https://evil.example` was served (200). `Origin`/`Host` validation needs our own
  `WebMvcStatelessServerTransport` bean with `DefaultServerTransportSecurityValidator`.

## U3 — Can the pilot IdP kinds issue a per-user, audience-bound token a non-browser MCP client can present?

**Answer: partly.** Keycloak: yes, by a scope-based audience mapper; RFC 8707 `resource` binding:
no on Keycloak 25, not established on 26.8.2. Entra ID: yes by documentation, with a different
token shape and no evidence from a tenant.

### (a) Keycloak — `McpSpikeKeycloakIT` (Testcontainers, `quay.io/keycloak/keycloak:25.0`, 6/6 pass)

Realm `mcp-spike` (spike branch `keycloak/mcp-spike-realm.json`): a **pre-registered public client
`mcp-cli`** (no secret, standard flow, PKCE S256 enforced, device grant enabled, loopback redirect)
and an optional client scope **`inspector-mcp`** whose Audience mapper adds
`https://inspector.test/mcp` to the access token. The test drives exactly what an MCP client does:
authorization code + PKCE S256 with `resource=https://inspector.test/mcp`, then the token request
with `code_verifier` and `resource`. The BFF runs on a real Tomcat in the `oidc` profile with the
spike's `/mcp` resource-server chain (Boot `spring.security.oauth2.resourceserver.jwt.issuer-uri`
= the pinned issuer, `audiences=https://inspector.test/mcp`).

Captured (tokens redacted, claim values shown):

```
token  alice, scope "openid inspector-mcp" -> iss=…/realms/mcp-spike aud="https://inspector.test/mcp" azp="mcp-cli"
       scope="openid profile inspector-mcp groups" groups=["team-a-viewers"] preferred_username="alice" lifetime_s=300
POST /mcp tools/call whoami_engines  Authorization: Bearer <redacted>
  -> 200 {"thread":"tomcat-handler-0","virtual":true,"principal":"alice","authType":"OAuth2AuthenticationToken",
          "scopeEnforced":true,"engines":{"probe-dev":"VIEWER"}}

token  alice, scope "openid" + resource=https://inspector.test/mcp -> aud=(absent)
POST /mcp tools/list -> 401 WWW-Authenticate: Bearer error="invalid_token",
  error_description="An error occurred while attempting to decode the Jwt: The aud claim is not valid", resource_metadata="…/mcp"

token  bob (group no-such-team), scope "openid inspector-mcp"
POST /mcp tools/call whoami_engines -> 200 {"isError":true, "no-engine-role: signed in, but no access has been granted yet"}

token  minted for the SPA client inspector-bff -> POST /mcp -> 401

no token -> 401 WWW-Authenticate: Bearer resource_metadata="http://localhost:<port>/.well-known/oauth-protected-resource/mcp"
GET /.well-known/oauth-protected-resource/mcp -> 200
  {"resource":"http://localhost:<port>/mcp","bearer_methods_supported":["header"],
   "tls_client_certificate_bound_access_tokens":false,"authorization_servers":["http://localhost:<port>/realms/mcp-spike"],
   "scopes_supported":["openid"]}

AS metadata (Keycloak 25): code_challenge_methods_supported=["plain","S256"], registration_endpoint present (DCR),
  device_authorization_endpoint present, authorization_response_iss_parameter_supported=true,
  client_id_metadata_document_supported absent
device authorization (public client, PKCE) -> 200 {"device_code":"<redacted>","user_code":…,"verification_uri":…}
```

So with Keycloak the roles resolve through the **same** group → role path as the SPA login
(`OidcGroupResolver` with the pinned issuer, then the file mapping). The existing
`OidcKeycloakIT` (SPA oidc chain, 8 tests) also passes with the `/mcp` chain in front of it.

**RFC 8707 on newer Keycloak.** The same IT against `quay.io/keycloak/keycloak:26.8.2` with
`--features=resource-indicators`: the three tests that send `resource=` fail at the login step with
HTTP 400 (the resource is not registered on any client); metadata and the device endpoint pass. The
feature needs the resource URI registered on a resource-server client; the spike did not configure
that. Until then the scope + Audience-mapper route is the one that works, and it means the MCP
client must request the `inspector-mcp` scope — which it does if the server advertises it in the
`WWW-Authenticate` `scope` parameter and in `scopes_supported` (the spike advertised `openid`; a
build advertises the audience scope).

**Which flow the MCP client uses.** The spec's flow is authorization code + PKCE with the
`resource` parameter, the client registered by pre-registration, a Client ID Metadata Document or
(deprecated) Dynamic Client Registration. Device flow is not in it. For the pilot that means one
pre-registered public client per IdP (Keycloak 25 has neither CIMD nor working `resource`; DCR is
available but would let any client register).

### (b) Entra ID — documentation only (no tenant)

- **App registrations.** One registration for the Inspector API: *Expose an API* with an
  Application ID URI and a delegated scope (for example `mcp.read`); manifest
  `requestedAccessTokenVersion: 2`. For a v2 access token the `aud` claim is the API's **client ID
  (GUID)**, not the Application ID URI, and `iss` is `https://login.microsoftonline.com/{tid}/v2.0`
  — so the BFF's `audiences` is the GUID and the issuer is tenant-pinned, as today. One public
  client registration (Mobile and desktop platform, loopback redirect) pre-authorised for the
  scope; clients request `api://<app-id>/mcp.read`.
- **Groups.** `groupMembershipClaims` on the **API** registration (access-token claims belong to the
  resource), preferably "Groups assigned to the application" to stay under the limit. Overage:
  above **200** groups a JWT carries no `groups` claim but `_claim_names`/`_claim_sources`; the
  existing `OverageGroupResolver` / `resolve-overage` path handles that today for the SPA token.
- **Limits.** Entra's v2 endpoint selects the resource by scope, not by the RFC 8707 `resource`
  parameter; an MCP client that sends `resource` to it is a known interoperability problem that
  the Inspector cannot fix server-side (not verified against a tenant). No Dynamic Client
  Registration and no Client ID Metadata Documents are documented for Entra, so every MCP client
  needs the pre-registered client ID configured. Default access-token lifetime is 60–90 minutes.

### What the MCP authorization spec requires of the server, and who provides it

| Requirement | Out of the box | Must be written |
|---|---|---|
| OAuth 2.1 resource server, bearer validation, 401 on invalid/expired | Spring Security resource server + Boot `issuer-uri` | the `/mcp` chain (spike: `McpSpikeSecurityConfig`) |
| Audience validation (RFC 8707 / `aud`) | Boot `spring.security.oauth2.resourceserver.jwt.audiences` | IdP configuration per kind (above) |
| RFC 9728 metadata | Spring Security 7.1 `protectedResourceMetadata()` | override `tls_client_certificate_bound_access_tokens` (defaults to **true**), set `scopes_supported` to the audience scope, canonical resource URL behind the proxy (forwarded headers) |
| `WWW-Authenticate` with `resource_metadata` | Spring Security 7.1 `BearerTokenAuthenticationEntryPoint` | point it at `/.well-known/oauth-protected-resource/mcp` (default omits the `/mcp` suffix, so `resource` would be the bare host); add `scope=` |
| Token → user grants | — | the JWT → `OAuth2AuthenticationToken` adapter, fleet-grant stripping, break-glass refusal, a test pinning "no `ROLE_*` authority on this chain" |
| No token passthrough | nothing forwards it today | keep it that way (engines keep the service account) |
| Dynamic Client Registration | not needed (deprecated in the spec) | — |

Spring AI 2.0.2 itself provides none of the authorization pieces; the separate community module
was not needed.

## What would have to be built (beyond the design note's slices)

1. Own `WebMvcStatelessServerTransport` bean: `DefaultServerTransportSecurityValidator` for
   `Origin`/`Host`, and error responses that never serialise an exception object.
2. Pin the dependency graph: import `spring-ai-bom` without letting it override Boot/springdoc
   managed versions (`swagger-annotations-jakarta`, `antlr4-runtime`), or pin those two; decide
   whether the Jackson 3 YAML converter swap matters (it only touches `application/yaml`).
3. The `/mcp` chain (stateless, no CSRF, Basic on dev, resource server on oidc) with the JWT
   adapter and its "no `ROLE_*` authority" pin test.
4. Per-caller `tools/list` filtering, if D5 keeps per-tool floors that differ.
5. A sibling arch rule over `@McpTool` methods (role expression or `ReadScopeGate`), not an
   exception entry in the controller guards.
6. A permanent `@PreAuthorize`-on-tool test (U2).
7. Refusal mapping inside tools (`no-engine-role`, per-engine refusal), since controller advice does
   not apply; de-duplicate the error text.
8. Per IdP: the audience scope / mapper (Keycloak) or API + public client registrations (Entra),
   documented in RUNBOOK.

## Effort estimate for the read-only build

The design note estimated 15–20 dev-days after the spike. The findings add about 2–3 days to S1
(own transport bean with validator and error mapping, dependency pinning, JWT adapter and pin
tests, arch sibling rule, per-caller listing) and about 1 day of IdP configuration and runbook
work. **Revised: 17–23 dev-days in the same six slices.** S0 is spent.

## Revised recommendations for D2–D10

| # | Recommendation after the spike |
|---|---|
| D2 Hosting | **Unchanged — in-BFF.** The security context, scope gate and method security work inside a tool; a dedicated `/mcp` chain keeps the SPA chains untouched (`OidcKeycloakIT` still green). |
| D3 Protocol + library | **Unchanged — Spring AI 2.0.2 stateless now**, with the U1 fixes (own transport bean, version pinning). Hand-rolling is not needed. Move to the `2026-07-28` MODERN protocol when Spring AI 2.1 is GA (needs SDK ≥ 2.2.0, released 2026-11-13). |
| D4 Agent credential | **Unchanged in direction — IdP-issued, audience-bound, per-user token**, proven on Keycloak via a scope + Audience mapper and a **pre-registered public client**. Add: one pre-registered client per IdP (no DCR, no CIMD on the pilot IdPs); the self-issued ≤ 12 h token stays the fallback for clients that cannot do the IdP flow (for example an Entra client that insists on `resource`). |
| D5 Read surface by tier | **Unchanged**, with a note: `tools/list` does not vary by caller out of the box; with every §1.2 tool at VIEWER that is acceptable (per-call refusal), and per-caller filtering is only needed if floors diverge. |
| D6 Variable exposure default | Unchanged. |
| D7 Read-call audit + actor | **Unchanged.** The client identity is in the token (`azp=mcp-cli` on Keycloak), so `agent_client` can be filled from the token. |
| D8 Engine opt-in | Unchanged. |
| D9 Model providers | Unchanged. |
| D10 Mutation path | Unchanged. |

## What surprised me

- A malformed MCP request body returns a full Java stack trace (the `McpError` exception
  serialised by the MVC converter).
- The autoconfigured transport validates no `Origin` header; DNS-rebinding protection is opt-in.
- Spring Security 7.1's metadata endpoint claims `tls_client_certificate_bound_access_tokens: true`
  by default.
- Importing `spring-ai-bom` silently downgrades two Boot/springdoc-managed artifacts.
- Without its own chain `/mcp` answers 403 (CSRF) to an anonymous client, never 401.
- Both route arch guards pass vacuously over MCP tools.
- `@PreAuthorize` on a tool works only because of bean-post-processor ordering.
- Jackson 3 was already on `main`'s classpath (via Flyway and Boot's own Jackson starter); the
  question was only ever which mapper serves which path.

## What the spike did NOT establish

- An end-to-end run with a real MCP client application (the exchanges were driven by curl and
  `HttpClient` emulating one); the interactive browser hop of the PKCE flow was scripted, not
  clicked.
- Completion of the device flow (the endpoint answered; no user code was approved).
- RFC 8707 binding on Keycloak 26.8.2 with a registered resource URI; Client ID Metadata Documents
  on any IdP.
- Anything against a real Entra ID tenant.
- The `2026-07-28` MODERN protocol (Spring AI 2.1 is a release candidate).
- The canonical resource URL and `Origin` checks behind the real reverse proxy (forwarded headers).
- The nightly `container-its`/engine ITs and `mvn verify` with the engine matrix on the spike branch
  (only the unit suite, the two Keycloak ITs and the drift gate were run).
- Load: the `AGENT` lane, rate limits and audit rows were out of scope for S0.

## Sources

- Spring AI 2.0.2 reference, stateless MCP server: https://docs.spring.io/spring-ai/reference/api/mcp/mcp-stateless-server-boot-starter-docs.html
- Spring AI 2.0.0 GA: https://spring.io/blog/2026/06/12/spring-ai-2-0-0-GA-available-now/
- Maven Central: `org/springframework/ai/spring-ai-starter-mcp-server-webmvc`, `io/modelcontextprotocol/sdk/mcp`, `org/springaicommunity/mcp-server-security`
- MCP authorization 2026-07-28: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization ; client registration: https://modelcontextprotocol.io/specification/2026-07-28/basic/authorization/client-registration
- Keycloak as MCP authorization server: https://www.keycloak.org/securing-apps/mcp-authz-server
- Entra ID access tokens: https://learn.microsoft.com/en-us/entra/identity-platform/access-tokens ; optional claims / groups: https://learn.microsoft.com/en-us/entra/identity-platform/optional-claims-reference
- In-repo: `docs/AGENTIC-ACCESS.md` (§3, §7 S0, §8), `docs/BOOT4-MIGRATION.md`, `SecurityConfig`, `RbacAuthorizer`, `ReadScopeGate`, `NoEngineRoleAdvice`, `MetricsGrantOidcChainSpringTest`, `OidcKeycloakIT`
