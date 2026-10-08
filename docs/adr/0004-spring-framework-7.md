# ADR 0004 — CVE-2026-47884 and the move to Spring Framework 7

| | |
|---|---|
| **Status** | Accepted: option B (owner, 2026-10-08) |
| **Date** | 2026-10-08 |
| **Driver** | [#120](https://github.com/enrichmeai/penstock/issues/120) slice 3: the last critical finding the v0.5.0 image scan lists in `app/agent.jar` |
| **Owners** | Joseph (decision) |
| **Supersedes** | — |

## Context

The v0.5.0 Trivy report (`trivy-report-0.5.0.txt`, attached to the release) lists
`org.springframework:spring-webmvc` 6.2.19 in `agent.jar` with **CVE-2026-47884, critical**:
"Remote Code Execution via improper path limitation in XsltView…" (the report truncates the title).
The only fixed version it lists is **7.0.9**.

Slices 1 and 2 of #120 (#121, #122) handled every other finding with a fix listed. This one cannot be
handled the same way:

- **No 6.2 fix exists on Maven Central.** 6.2.19 is the newest 6.2.x there, and Spring Boot 3.5.16,
  the newest 3.5.x, manages exactly 6.2.19.
- **7.0.9 needs Spring Boot 4.** Spring Boot 4.1.1's BOM (`spring-boot-dependencies-4.1.1.pom`)
  manages `spring-framework.version` 7.0.9. Overriding Spring Framework alone across a major version
  on Boot 3.5 is assumed unsupported. **Not verified:** Boot's documentation was unreachable from the
  build environment.

## Is it reachable in Penstock?

**No, not as Penstock is built today.** The vulnerable code is `XsltView` (and `XsltViewResolver`) in
`org/springframework/web/servlet/view/xslt/`. The class ships in `spring-webmvc-6.2.19.jar`, so a
scanner finds it, but it runs only when an application renders an XSLT view. Checked at `f42a4de`:

- **No configuration:** no source file names `XsltView`, `XsltViewResolver`, a `ViewResolver` or
  `ModelAndView` (`grep -rn` over `src/`). The only view the application renders is Spring Boot's own
  Whitelabel error page, a static view, not XSLT.
- **No view-rendering controllers:** there is no plain `@Controller`; every endpoint Penstock defines is a
  `@RestController` returning bodies, not views.
- **No templates:** there is no `src/main/resources/templates/`. The web UI is static files under
  `static/`.
- **No template or XSLT engines on the runtime classpath:** Thymeleaf, FreeMarker, Xalan and Saxon
  are all absent (`./gradlew dependencies --configuration runtimeClasspath`).
- **Nothing else wires it in:** no class in `spring-boot-autoconfigure-3.5.16.jar` references
  `XsltView`. Across all 142 runtime-classpath jars, `view/xslt/XsltView` is referenced only inside
  `spring-webmvc-6.2.19.jar` itself.

A regression test (option B) would keep this true as the code changes.

## Options

### A. Move to Spring Boot 4.1 now

This gets Spring Framework 7.0.9 and clears the finding from the scan. It is a major upgrade with
these known costs:

| Area | Today | With Boot 4.1.1 | Penstock code touched |
|---|---|---|---|
| Spring Framework | 6.2.19 | 7.0.9 | all of it, through the starters |
| Spring Security | 6.5.11 | 7.1.1 | 15 files (`SecurityConfig`, OIDC, JWT resource server) |
| Jackson | 2.21.7 (`com.fasterxml.jackson`) | 3.1.5 by default; the BOM still manages a Jackson 2 BOM at **2.21.5, below 2.21.7, the listed fix for CVE-2026-89407, -89425, -91776 and -91777, and below 2.21.6 for CVE-2026-68497**, so the slice-1 override would need to come back as a `jackson-2-bom` override | 34 files; the MCP and ACP SDKs are wired to Jackson 2 (`mcp-json-jackson2`, `acp-json-jackson2`), so Penstock stays on Jackson 2 |
| Hibernate | 6.6.53 | 7.4.5 | `hibernate-community-dialects` pinned at 6.6.56 must move to 7.4.x (SQLite dialect) |
| Flyway | 11.7.2 | 12.4.0 | migrations unchanged, but the Postgres module must match |
| Tomcat | 10.1.60 | 11.0.24 | **11.0.24 is below 11.0.25, the listed fix for CVE-2026-65182**, so the slice-1 override would need to come back as 11.0.25 or later |
| Netty | 4.1.139 | 4.2.17 | WebClient for the providers (18 files) |
| springdoc | 2.9.0 (its parent POM is Boot 3.5.16) | 3.1.1 (its parent POM is Boot 4.1.0) | `build.gradle` only |
| Testcontainers | 1.21.4 | 2.0.5; the artifacts are renamed (`testcontainers-postgresql`, `testcontainers-junit-jupiter`) | `build.gradle` and `JpaSessionStorePostgresIT` |

**Not verified:** the Spring Boot 4 migration guide and Spring Framework 7 release notes. docs.spring.io
was blocked from the build environment, so each row above shows only what the published BOMs and
Maven Central list. Behaviour changes inside those versions still need reading before any code moves.

### B. Stay on Boot 3.5, state that the CVE is not reachable, and guard it

- Add one test that starts the application context and fails if any bean is an `XsltView` or an
  `XsltViewResolver`. It must not fail on view resolvers in general: Boot 3.5.16 registers several by
  default (`InternalResourceViewResolver`, `BeanNameViewResolver`, `ContentNegotiatingViewResolver`)
  and the Whitelabel error view. A test that banned those would fail on day one, or be weakened later.
- Release notes state the finding remains in the scan, why it is not reachable, and the test that
  holds it.
- Plan the Boot 4 move (option A) as its own project, sliced the way #120 was. Under this option it
  is not done under CVE pressure.

The cost: the scan keeps listing one critical until the move happens, and the site's scan record says
so. Spring Boot 3.5's open-source support window also bounds how long this can last.

### C. Commercial Spring support

Commercial Spring support may provide a 6.2.x build with the fix that does not reach Maven Central.
**Not verified:** whether such a build exists for this CVE, and on what terms. It would cost money
and add a private repository to the build. It is listed for completeness; it is the owner's call.

## Recommendation

**B now, then A as a planned project.**

The finding is not reachable in Penstock as built, so there is no exposure to buy down urgently. A
major-version move touches security configuration, persistence, JSON handling and both stdio
protocols at once. Done under pressure, that is where a real regression gets in. The guard test keeps
the reasoning honest: if a future change adds an XSLT view, the build fails before the CVE becomes
reachable.

## Decision

**Option B**, chosen by the owner on 2026-10-08.

- Penstock stays on Spring Boot 3.5.
- A test fails the build if any `XsltView` or `XsltViewResolver` bean appears in the application
  context. That is the next change under #120.
- Release notes say CVE-2026-47884 stays in the image scan, why it is not reachable, and which
  test holds that.
- The move to Spring Boot 4 (option A) becomes its own planned project, sliced like #120.
