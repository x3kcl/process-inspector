# Boot 4 migration plan

Spike for #439 (epic #438). Read on 2026-10-22 against `main` at `3f468c5` (Boot **3.5.16**, Java 21). No production code moves in this document. #440 implements it.

## Target

**Spring Boot 4.1.1** (released 2026-08-20). OSS support for the 4.1 line ends **2027-07-31**. Commercial support ends 2028-07-31.

Not 4.0.8. That patch is current for 4.0, but the 4.0 OSS window ends **2026-12-31**, about ten weeks from this spike. 4.1 also deletes APIs that 4.0 only deprecated, so landing on 4.0 and then 4.1 would pay the same break twice. 4.2 was milestone-only on this date (`4.2.0-M2`, 2026-09-24) and is not a target.

Java stays **21** for #440. Boot 4.1 requires Java 17+. Java 25 is #441, after the Boot upgrade is green, because the two bumps fail differently and the virtual-thread `synchronized` rule only changes meaning on 25.

## Compatibility

| Piece | Today | On 4.1.1 | Note |
|---|---|---|---|
| Parent | `spring-boot-starter-parent` 3.5.16 | 4.1.1 | Already the last 3.5 patch, which the Boot 4 guide requires before the jump |
| Web starter | `spring-boot-starter-web` | `spring-boot-starter-webmvc` | Classic name is gone. Tests that need MVC slices use `spring-boot-starter-webmvc-test` |
| Security test | `spring-security-test` | plus `spring-boot-starter-security-test` | `@WithMockUser` stops working from `spring-security-test` alone |
| Test starter | `spring-boot-starter-test` | `spring-boot-starter-test-classic` for the first green build, then the technology test starters | Classic restores the Boot 3 test classpath. Drop it once the suite compiles without it |
| Resilience4j | `resilience4j-spring-boot3` 2.4.0 | `resilience4j-spring-boot4` **2.4.0** | Same version line. 2.4.0 is the release that added the Boot 4 artifact (2026-03-14). Its published POM depends on `spring-boot-autoconfigure` 4.0.0 and Spring 7.0.2; that is compatible with a 4.1 app |
| springdoc | `springdoc-openapi-starter-webmvc-api` 2.8.14 | **3.1.1**, same artifact | 3.1.1's parent is Boot 4.1.0. API-only starter stays; no Swagger UI |
| JSON | Jackson 2 (`com.fasterxml.jackson`), `jackson-bom` pinned to 2.21.7 | **Keep Jackson 2** for #440 | Boot 4.1 renamed the Jackson 2 property to `jackson-2-bom.version` (2.21.5 managed). `jackson-bom.version` is Jackson 3. Pin `jackson-2-bom.version` to 2.21.7 |
| Tomcat | pin `tomcat.version` 10.1.59 | drop the pin | Boot 4 is Tomcat 11 (Servlet 6.1). The 10.1.x pin cannot carry over |
| Postgres driver | pin `postgresql.version` 42.7.13 | drop the pin | Boot 4.1.1 already manages 42.7.13 |
| Flyway | `flyway-core` + `flyway-database-postgresql`, versions from the BOM | unchanged shape | Do not add a version pin |
| Log encoder | `logstash-logback-encoder` 7.4 | stay on 7.4 while Jackson 2 stays | 7.4 is a Jackson 2 encoder |
| WireMock | `wiremock-standalone` 3.13.2 | keep the pin | Not a Boot-managed artifact. Standalone server, not the Spring-Boot integration |
| ArchUnit | 1.5.1 | keep | JUnit 5 rule engine, no Boot API |
| Testcontainers | BOM (1.x artifacts `junit-jupiter`, `postgresql`) | Boot 4.1 manages **2.0.5** | Artifact ids are `testcontainers-junit-jupiter` and `testcontainers-postgresql`. Java packages are unchanged. The Postgres 16 image tag in the tests is independent |
| JaCoCo | 0.8.15 | keep for #440 (Java 21) | #441 must confirm 0.8.15 reads class-file version 69 before the JDK 25 image bump |

## Jackson decision

**#440 stays on Jackson 2.** Add `org.springframework.boot:spring-boot-jackson2` and set `spring.http.converters.preferred-json-mapper=jackson2` so MVC and `RestClient` keep the Jackson 2 mapper. Boot's own JSON auto-config is Jackson 3 (`tools.jackson`). The Jackson 2 module is deprecated and scheduled for removal in Boot 4.3, so it is a bridge, not a home.

Why not Jackson 3 in the same change:

- 40 production files import `com.fasterxml.jackson`. 14 of them use `ObjectMapper`. Tests add another 63 files.
- The engine client has 55 `Map<String, Object>` hits and 25 `@SuppressWarnings("unchecked")` sites. Flowable payloads are untyped JSON. A default-mapper change (date format, null inclusion, module discovery) rewrites `/v3/api-docs` and the parse of engine bodies without a compile error.
- `logstash-logback-encoder` 7.4 does not speak Jackson 3. Moving both in one PR mixes a log-pipeline break with an API-contract break.

Jackson 3 is a later change, before anyone proposes Boot 4.3. It is not part of #440, #441, or #442.

## Removed or renamed APIs this tree actually uses

The Security 6 lambda DSL is already what Security 7 kept. `SecurityConfig` uses `headers(...)`, `csrf(...)`, `authorizeHttpRequests(...)`, `sessionManagement(...)`, `httpBasic(...)`, and `formLogin(withDefaults())`. No `.and()` chain. The one removal in this tree:

| API | Where | Replacement |
|---|---|---|
| `@MockBean` | `SharedViewFailClosedIT` only (one field) | `@MockitoBean`. Four other Spring tests already use `org.springframework.test.context.bean.override.mockito.MockitoBean` |
| `AntPathRequestMatcher` | `BreakGlassThrottleFilter` (one POST `/break-glass` matcher) | `PathPatternRequestMatcher`. Security 7 removed the Ant matcher; CI already warns on this class |
| `spring-boot-starter-web` | `backend/pom.xml` | `spring-boot-starter-webmvc` |
| `resilience4j-spring-boot3` | `backend/pom.xml` | `resilience4j-spring-boot4` |
| Tomcat 10.1 pin | `backend/pom.xml` | delete; Tomcat 11 comes from the BOM |

Not present, so not a migration item: WebFlux, `WebClient`, `RestTemplate` in production (outbound calls are `RestClient`, nine production files), `@SpyBean`, a `@Configuration` class that declares `@MockBean`.

`synchronized` appears only in comments. The locks are already `ReentrantLock`. #441 rewrites the CLAUDE.md sentence; it does not move locks.

## What the spec says after #440

Do not edit these in the spike. #440 changes them in the same PR as the parent bump:

- `CLAUDE.md` stack line and `docs/SPECIFICATION.md` §10: Java 21 / Spring Boot **4.1.x** / `springdoc` 3.1.x. Jackson 2 called out as the bridge, with the 4.3 removal named.
- `docs/ARCHITECTURE.md` §6 error-shape sentence stays (that sentence is about Flowable 7's Boot 3 error JSON, not about this BFF). ADR-001's stack pin, which SPEC §10 points at, gains the Boot 4.1 line.
- `docs/OPERATIONS.md` only if an actuator path or health group name moves. The current probes are `/actuator/health/**`. Confirm they still answer before editing the runbook.
- `frontend/src/api/schema.d.ts` only if `npm run gen:api` diffs. springdoc 3 has changed schema naming before; the drift gate is the check, not a hand edit.

#441 then sets Java 25 in those same pins and changes the CLAUDE.md virtual-thread rule from "banned until the JDK 24 bump" to "not required since Java 25; the `ReentrantLock`s stay".

## Order of work

1. **#440 — Boot 4.1.1 on Java 21.** Parent, starters, `spring-boot-jackson2`, Resilience4j artifact, springdoc 3.1.1, the one `@MockitoBean`, the one request matcher. `mvn test` green, then `ci.yml` on the merge SHA, then a dispatched `nightly.yml` (engine-its, container-its, dlq-scan-cap-p2, fanout-partial-failure-p1, perf-p1) on that same SHA. Acceptance net is the existing IT matrix, not new tests.
2. **#442 — overrides.** After #440 is green, delete `backend/.trivyignore.yaml` entries CVE-2026-47884 and CVE-2026-47890 once the managed `spring-framework` is ≥ 7.0.9 (the OSS fix those entries name). Drop `tomcat.version`. Drop `jackson-bom.version` and `postgresql.version` only when the BOM meets the comment's floor. Re-justify anything that remains, with an expiry. npm overrides are a separate audit (`npm view` plus `npm audit`); they are not caused by the Boot bump.
3. **#441 — Java 25.** Dockerfile, CI `setup-java`, the runner image, `scripts/check-base-image-currency.sh`. Confirm JaCoCo against class-file 69. Check `MaxRAMPercentage` against OPERATIONS §6 memory limits. Then the CLAUDE.md wording.

#440 does not start until this plan is the one being followed. A red nightly on the Boot 4 SHA blocks the merge; a green `ci.yml` alone does not meet #440.

## What the 4.1.1 build also had to rename

Measured while compiling #440. The table above is the plan; these are the breaks it did not name:

- Auto-config exclusions moved: `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration`, `org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration`, `org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration`. The docker-free profiles exclude those three.
- `DefaultErrorAttributes` is `org.springframework.boot.webmvc.error.DefaultErrorAttributes`.
- `TestRestTemplate` is `org.springframework.boot.resttestclient.TestRestTemplate` and is opt-in via `@AutoConfigureTestRestTemplate`. `@AutoConfigureObservability` is `@AutoConfigureMetrics`. `@AutoConfigureMockMvc` is `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`.
- `spring-boot-starter-test-classic` stays. Its classic-modules POM depends on every technology test jar and then excludes all of their transitives, so `TestRestTemplate` cannot see `RestTemplateBuilder`. The suite also depends on `spring-boot-starter-webmvc-test` and, because the published Maven POM of `spring-boot-resttestclient` omits it, `spring-boot-restclient` (test scope).
- `hibernate.hbm2ddl.extra_physical_table_types` still exists on Hibernate 7.4.5. `/actuator/health` still answers. The postgres pin is gone: the 4.1.1 BOM already manages 42.7.13.
- Flyway auto-config is `spring-boot-flyway`. `spring-boot-starter-data-jpa` does not depend on it, and without it `ddl-auto=validate` runs against an empty database. The module is a direct dependency next to `flyway-core`.
- Spring 7's `HttpStatus.resolve(422)` is `UNPROCESSABLE_CONTENT`. HTTP round-trip assertions use that constant. Code that still throws `UNPROCESSABLE_ENTITY` is the same 422 on the wire.
- `RestClient.builder()` detects Jackson 3. `EngineSeed` registers `MappingJackson2HttpMessageConverter` because those calls read Jackson 2 `JsonNode`. The BFF's own `RestClient`s stayed on the Boot `preferred-json-mapper=jackson2` path.
