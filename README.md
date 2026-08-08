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

**Milestone 1: Backend skeleton** ✅
- [x] Task domain model (entity, JSONB context, Flyway migration)
- [x] REST API: create / get / list / cancel a task
- [x] Centralized task state machine with unit tests
- [x] Global exception handling

**Milestone 2: Sandbox Manager** ✅
- [x] `ContainerRuntime` port + `DockerContainerRuntime` adapter (docker-java)
- [x] `SandboxManager`: reuse-if-running / create-if-not, with a Postgres
      partial-unique-index guard against concurrent double-provisioning
- [x] Resource-limited containers (memory/CPU/pids caps, dropped
      capabilities, no-new-privileges, non-root in-container user)
- [x] Idle-sandbox reaper (`@Scheduled`)
- [x] Custom sandbox image (Java/Maven, Node/npm, Python/pip, git)
- [x] Unit tests against a fake `ContainerRuntime`

**Milestone 3: Auth & User Service** ✅
- [x] User entity + BCrypt password hashing
- [x] JWT issuance/validation (stateless, no revocation yet - see ADR-0004)
- [x] `/api/auth/register`, `/api/auth/login`
- [x] Real Spring Security filter chain (JSON 401/403, not the default
      whitelabel page)
- [x] `TaskController`/`SandboxController` retrofitted to
      `@AuthenticationPrincipal` instead of the `X-User-Id` stand-in header
- [x] `tasks.user_id` promoted to a real foreign key

**Milestone 4 (next): Streaming Service or Agent Orchestrator**
- [ ] Real-time streaming (SSE) for task logs
- [ ] Agent Orchestrator (LLM tool-calling loop)

## Running locally

```bash
docker compose up -d          # starts Postgres on :5432
cd backend
./mvnw spring-boot:run         # starts the API on :8080
```

Swagger UI: `http://localhost:8080/swagger-ui.html`

Every endpoint except `/api/auth/**` and Swagger now requires a Bearer
token, issued via `/api/auth/register` or `/api/auth/login`.

### Example: register, then create a task

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"email":"praveen@example.com","password":"password123","displayName":"Praveen"}' \
  | jq -r .token)

curl -X POST http://localhost:8080/api/tasks \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
        "title": "Add pagination to /orders",
        "description": "Cursor-based pagination on the orders list endpoint",
        "repoUrl": "https://github.com/praveen7600/grocery-shop"
      }'
```

### Sandbox Manager

Build the sandbox image once, locally:

```bash
docker build -t ai-coding-agent-sandbox:latest infra/docker/sandbox
```

The backend needs access to the Docker socket to manage containers. When
running the backend outside Docker (e.g. via `./mvnw spring-boot:run` on
your host), it talks to `unix:///var/run/docker.sock` by default - no extra
setup needed on Linux. Manual test endpoints while the Orchestrator doesn't
exist yet:

```bash
TASK_ID=$(uuidgen)

curl -X POST http://localhost:8080/internal/sandboxes/$TASK_ID/ensure \
  -H "Authorization: Bearer $TOKEN"

curl -X POST http://localhost:8080/internal/sandboxes/$TASK_ID/exec \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"command": ["git", "--version"]}'

curl -X DELETE http://localhost:8080/internal/sandboxes/$TASK_ID \
  -H "Authorization: Bearer $TOKEN"
```

## Architecture decisions

- [ADR-0001: PostgreSQL over MySQL](docs/architecture/ADR-0001-postgresql-vs-mysql.md)
- [ADR-0002: Centralized task state machine](docs/architecture/ADR-0002-task-state-machine.md)
- [ADR-0003: Sandbox Manager container runtime strategy](docs/architecture/ADR-0003-sandbox-container-strategy.md)
- [ADR-0004: Auth & User Service](docs/architecture/ADR-0004-auth-user-service.md)

## Tech stack

- Java 17, Spring Boot 3.3 (Web, Data JPA, Validation, Security, WebSocket)
- PostgreSQL 16, Flyway migrations
- Docker Engine API via docker-java (sandbox execution environment)
