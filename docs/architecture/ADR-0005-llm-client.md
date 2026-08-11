# ADR-0005: LLM Client — raw REST over Gemini's Interactions API or official SDK

## Status
Accepted (partial — covers the LlmClient port and GeminiClient adapter only;
the orchestrator loop that consumes this is a separate, later decision).

## Context
The Agent Orchestrator needs to call Gemini for reasoning and tool-calling.
Google currently offers three ways to do this:

1. **Interactions API** — newer, unified endpoint with server-side
   conversation state (`previous_interaction_id`) and native SSE streaming.
   Currently in Beta; Google's own docs recommend `generateContent` for
   stable production use as of this writing.
2. **`generateContent`** — the established, stable endpoint. Stateless:
   the full conversation history is sent on every call.
3. **Official `google-genai` SDK** — wraps either of the above.

## Decision
Use `generateContent` directly via Spring's `RestClient`, with the request/
response JSON built and parsed by hand (no typed DTOs, no SDK).

## Rationale
- **Stability over novelty.** This project needs to actually work reliably
  for demos and interviews. Building against a Beta API risks the ground
  shifting mid-project for a feature (server-side state) this project
  doesn't need yet — task history already lives in Postgres via the `Task`
  entity, and the state machine's transitions already give the interview
  story about state management.
- **Interview defensibility over convenience.** The whole point of this
  project is being able to explain what happens end-to-end. If the SDK sent
  the request, "what's actually in the JSON when the model calls a tool"
  becomes a harder question to answer from memory. Hand-building the
  request/response mapping means every field is something I wrote, not
  something a library did for me.
- **No new dependency.** `RestClient` (Spring Boot 3.2+, already in this
  project via 3.3.4) covers this. No SDK, no reactive stack (`WebClient`)
  needed for a synchronous, non-streaming call.
- **Ports and adapters, again.** `LlmClient` is a port; `GeminiClient` is the
  one adapter. Same shape as `ContainerRuntime`/`DockerContainerRuntime`
  (ADR-0003) — the orchestrator loop will depend on `LlmClient` only, so a
  `FakeLlmClient` can drive the loop's unit tests without a network call,
  and swapping providers later means writing one new adapter class.

## Consequences
- Conversation history is sent in full on every call (stateless). For a
  capped-iteration agent loop (max ~5-10 tool calls per task), this is a
  non-issue; it would need revisiting if the loop grew much longer or if
  token cost became a concern — at that point, moving to the Interactions
  API's `previous_interaction_id` (once it's stable) is the natural next
  step, and `LlmClient` as a port makes that a contained change.
- Gemini's `parts` array is a real union type (`text` | `functionCall` |
  `functionResponse`) that doesn't map cleanly onto Java records without
  either per-part-type sealed interfaces (more ceremony than justified) or
  Jackson polymorphic config (more magic than the "raw calls" decision was
  meant to avoid). `GeminiClient` builds/parses this with Jackson's
  `JsonNode`/`ObjectNode` tree API directly instead.
- No retry/backoff logic yet. A transient Gemini 5xx currently surfaces as
  `LlmClientException` straight to the caller. Worth adding once the
  orchestrator loop exists and there's a concrete place to decide "retry
  this step" vs. "fail the task."
