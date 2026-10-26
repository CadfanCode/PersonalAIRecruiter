# 0001 Spring Boot 3.5 and trimmed Claude CLI calls

Date: 2026-10-26. Status: accepted.

## Spring Boot line

CLAUDE.md specifies Spring Boot 3.x. start.spring.io now offers only 4.x, so the backend pins
the last 3.x release, 3.5.16. Open source support for the 3.5 line may have ended, so moving to
4.x is a decision for the owner, not something to do silently.

## Testcontainers 1.21.4

Docker 29 raised its minimum API version, and the Testcontainers that Boot 3.5 manages is too
old for it. The pom overrides `testcontainers.version` to 1.21.4.

## Claude CLI flags

Every call adds `--disable-slash-commands --strict-mcp-config --setting-sources project` and runs
in `<data.dir>/claude-work/`. Without them the CLI loads the user's plugins and skills and a
tiny call costs about $0.40 (50k cache tokens). With them it costs about $0.004. See SPEC 5.1.

## Ports

The API defaults to 8080 as specified but reads `SERVER_PORT`. The Vite proxy reads
`VITE_API_TARGET`. Use these when something else already holds 8080.
