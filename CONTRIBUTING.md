# Contributing to OryxOS

OryxOS uses JDK 21, Maven 3.9+, Spring Boot 3.x, and a fixed nine-module reactor. Read
`AGENTS.md` before changing architecture or feature scope.

## Local setup

1. Install JDK 21 and Maven 3.9 or newer.
2. Export provider secrets as environment variables. Use `.env.example` as the list of
   supported names; never commit real values.
3. Optionally install [pre-commit](https://pre-commit.com/) and run `pre-commit install`.

## Required checks

Format Java sources before committing:

```bash
mvn spotless:apply
```

Run the complete merge gate:

```bash
mvn clean verify
```

The `verify` lifecycle runs tests, Google Java Format validation, Google Checkstyle,
Alibaba P3C rules, SpotBugs with Find Security Bugs, and OWASP Dependency-Check. Set
`NVD_API_KEY` in CI and local environments to avoid NVD rate limits. For a fast local
iteration that deliberately omits only the CVE database scan, use:

```bash
mvn clean verify -Ddependency-check.skip=true
```

Do not use `System.out` for application logging. Use SLF4J; Picocli command output should
use the command's configured writer.

## REST API contract

Controllers under `/api/v1` return successful results with `ApiResponse.success(...)`.
The stable JSON shape is `code`, `message`, `data`, and an ISO-8601 `timestamp`.
Application failures must use `OryxException` plus an `ErrorCode`; the global exception
handler returns exactly `errorCode`, `message`, and `timestamp` with the HTTP status owned
by that error code. Do not expose unexpected exception details to clients.

The response records support both Java record accessors (`code()`, `message()`) and JavaBean
accessors (`getCode()`, `getMessage()`) for framework and caller compatibility. Keep both
sets serialization-safe: adding a getter must not add a second JSON property.

## Runtime verification

Start the Spring runtime for local Actuator and OpenAPI checks:

```bash
mvn -pl oryxos-boot -am spring-boot:run \
  -Dspring-boot.run.main-class=com.oryxos.OryxOsApplication
```

The default development endpoints are:

- Health: `http://localhost:8080/actuator/health`
- Metrics: `http://localhost:8080/actuator/prometheus`
- OpenAPI: `http://localhost:8080/v3/api-docs`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

Production uses the `prod` Spring profile and emits JSON logs. Request logs can be
correlated through the `traceId` MDC field and `X-Trace-Id` response header.
