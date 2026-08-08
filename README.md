# AI Coding Agent

An AI coding agent (Devin-style) - Spring Boot backend, an LLM-driven agent
orchestrator, and per-task Docker sandboxes. Built as a portfolio project;
architectural decisions are logged as ADRs in `docs/architecture/` as they're
made, not written up after the fact.

## Architecture

![architecture](docs/architecture/architecture-v1.png)

- **Client** - Web/mobile, creates tasks, receives real-time updates.
- **Backend (Spring Boot)** - REST API, task lifecycle, auth, streams
  logs/events, manages sandbox containers.
- **Agent Orchestrator** - the reasoning loop: understand task -> plan via
  LLM -> pick a tool -> execute in sandbox -> observe -> repeat until done.
- **Docker Sandbox** - isolated per-task container with git, a shell, a
  filesystem, and language runtimes, so the agent can never touch the host.
- **Data store** - PostgreSQL (tasks/users/metadata), Redis (queues/cache,
  planned), object storage (logs/artifacts, planned).

## Current status

**Milestone 1 (in progress): Backend skeleton**
- [x] Task domain model (entity, JSONB context, Flyway migration)
- [x] REST API: create / get / list / cancel a task
- [x] Centralized task state machine with unit tests
- [x] Global exception handling
- [ ] JWT auth (Auth & User Service)
- [ ] Real-time streaming (SSE) for task logs
- [ ] Sandbox Manager (Docker container lifecycle)
- [ ] Agent Orchestrator (LLM tool-calling loop)

## Running locally

```bash
docker compose up -d          # starts Postgres on :5432
cd backend
./mvnw spring-boot:run         # starts the API on :8080
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

Every request currently requires an `X-User-Id: <uuid>` header in place of a
real JWT principal - see the note in `TaskController` for why, and
`SecurityConfig` for the temporary permissive filter chain.

### Example: create a task

```bash
curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 11111111-1111-1111-1111-111111111111" \
  -d '{
        "title": "Add pagination to /orders",
        "description": "Cursor-based pagination on the orders list endpoint",
        "repoUrl": "https://github.com/praveen7600/grocery-shop"
      }'
```

## Architecture decisions

- [ADR-0001: PostgreSQL over MySQL](docs/architecture/ADR-0001-postgresql-vs-mysql.md)
- [ADR-0002: Centralized task state machine](docs/architecture/ADR-0002-task-state-machine.md)

## Tech stack

- Java 17, Spring Boot 3.3 (Web, Data JPA, Validation, Security, WebSocket)
- PostgreSQL 16, Flyway migrations
- Docker (sandbox execution environment - Milestone 3)
