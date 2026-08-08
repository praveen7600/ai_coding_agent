# ADR-0004: Auth & User Service

## Status
Accepted

## Context
Every task and sandbox needs a real, unspoofable owner. Milestones 1-2 used
an `X-User-Id` header as a stand-in specifically so the ownership checks in
`TaskService` and the (deliberate) 404-not-403 pattern could be built and
tested before auth existed. This milestone replaces that stand-in.

## Decision 1: Stateless JWT, not server-side sessions

Chosen for the same reason as the `grocery-shop` project: a stateless
backend doesn't need sticky sessions or a shared session store if it's ever
scaled beyond one instance, which matters given the Sandbox Manager's
concurrency design already assumes multiple instances are possible (ADR-0003,
Decision 3).

**Known, accepted gap:** there is no token revocation. A JWT is valid until
it expires (`jwt.expiration-minutes`, default 24h) - logging out or changing
a password does not invalidate an already-issued token early. The standard
fix (short-lived access token + refresh token, or a server-side denylist for
revoked tokens) is out of scope for V1 and is the first thing to add before
this is anything more than a personal project.

## Decision 2: Password hashing via BCrypt, delegated through Spring Security's `AuthenticationManager`

Login doesn't compare password hashes by hand in `AuthService` - it builds a
`UsernamePasswordAuthenticationToken` and hands it to Spring Security's
`AuthenticationManager` (a `DaoAuthenticationProvider` wired to
`CustomUserDetailsService` + `BCryptPasswordEncoder`). This is more
boilerplate than `passwordEncoder.matches(...)` directly, but it means any
future policy - account lockout after N failed attempts, password
expiration - plugs into the same standard machinery instead of being
special-cased in `AuthService`.

## Decision 3: Generic "invalid email or password" on login failure

`BadCredentialsException` is mapped to one message regardless of whether the
email didn't exist or the password was wrong. This is the same instinct as
`TaskService`'s ownership check returning 404 instead of 403 (ADR
established in Milestone 1) - don't let an error response confirm or deny
which part of a credential pair was correct; that's exactly the oracle a
credential-stuffing attack needs.

## Decision 4: userId flows from `@AuthenticationPrincipal`, not a header - and nothing below the controller changed

`TaskController` and `TaskService` were deliberately split in Milestone 1 so
that swapping the caller-identity mechanism would be a controller-only
change. It was: `TaskService` method signatures are untouched, only
`TaskController` swapped `@RequestHeader("X-User-Id")` for
`@AuthenticationPrincipal UserPrincipal`. Worth naming explicitly in an
interview as evidence the earlier layering decision paid off, not just as
something that happened to work out.

`SandboxController` still doesn't check per-task ownership - it requires
*a* valid authenticated user but not that they own the task in question.
That check is deferred to the Agent Orchestrator milestone, which will
already have loaded the task (and therefore its owner) via `TaskService`
before it ever calls `SandboxManager` for it. Adding it to `SandboxController`
now would mean either duplicating that ownership logic or giving the sandbox
package a dependency on the task package it doesn't otherwise need.

## Consequences
- `tasks.user_id` is now a real foreign key to `users.id` (V3 migration),
  not just an unenforced UUID column.
- Every previously-open endpoint except `/api/auth/**` and Swagger now
  requires a valid Bearer token - the Milestone 1/2 manual `curl` examples
  in the README needed updating to register/login first.
- Token revocation and refresh tokens are explicitly deferred, not silently
  missing - see Decision 1.
