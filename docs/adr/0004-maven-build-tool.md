# ADR-4: Maven, multi-module reactor

**Status:** Accepted (2026-09-10)

**Context:** Maven vs. Gradle for a five-service Java reactor plus two shared
library modules.

**Decision:** Maven. One parent POM (`pom.xml`), modules: `conveyor-contracts`,
`conveyor-common`, and one per service. Java 21 LTS as the target bytecode
release (`maven.compiler.release=21`); Spring Boot 3.5.x.

**Consequences:** A declarative POM is faster for a reviewer to parse than a
build script, and matches Spring's own Maven-first documentation and
`start.spring.io` defaults. Gradle's build-speed advantage doesn't pay for
itself at this module count; would flip if the module count tripled. Maven
is not installed globally on the reference dev machine — the repo commits the
Maven Wrapper (`mvnw`/`mvnw.cmd`) so nobody needs a local Maven install.

**Full reasoning:** `ARCHITECTURE.md` ADR-4.
