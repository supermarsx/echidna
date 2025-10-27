# Echidna Repository Guidelines

These instructions apply to the entire repository unless a more specific `agents.md` file overrides them.

## Documentation
- Prefer Markdown (`.md`) for new documentation.
- Use level-1 headings (`# Heading`) for top-level sections and ensure subsequent headings increase sequentially.
- Keep lines at or below 100 characters when practical to ease review in terminals.
- Link to relevant sections of `spec.md` whenever work is driven by those requirements.

## Implementation Priorities
- Follow the native-first architecture described in `spec.md`; implement AAudio/OpenSL/AudioRecord hooks
  before adding Java fallbacks.
- Preserve the DSP pipeline structure defined in the spec and avoid regressions to latency or preset
  compatibility.
- Changes that alter user-visible behaviour must document the impact on presets, hooks, or the companion
  app.

## Coding Standards
### General
- Match the surrounding style of files you modify; do not introduce a new style within an existing file.
- Use descriptive names, clear invariants, and scoped enums/constexprs instead of macros whenever
  possible.
- Handle errors explicitly and prefer early returns over deeply nested control flow.

### C/C++ Native Modules (`libechidna.so`, `libech_dsp.so`)
- Target modern C++17 with RAII and `std::unique_ptr`/`std::shared_ptr` for ownership clarity.
- Keep hook trampolines small, thread-safe, and guard shared state with lock-free primitives or
  `std::atomic` as required by the spec's low-latency constraints.
- Validate buffer boundaries rigorously and sanitise inputs before handing data to the DSP pipeline.
- Organise platform-specific code under `#ifdef` guards that isolate API/ABI differences per Android
  level.

### Java/Kotlin (LSPosed Shim, Companion App)
- Follow Android Kotlin style when editing Kotlin sources and Google Java style when editing Java code.
- Keep hooks process-aware; respect per-app whitelists defined in the spec and fail closed when a
  process is not explicitly permitted.
- Avoid blocking binder/IPC threads; offload work to background executors when interfacing with the
  control service.

### Scripts and Tooling
- Write shell scripts in POSIX sh unless Bash features are required; include `set -euo pipefail` when
  applicable.
- Prefer Python 3.10+ with type hints and `black`/`ruff` compatible formatting for automation scripts.

## Testing & QA
- Run all relevant unit tests, instrumentation tests, and integration tests for touched components.
- For DSP or hook changes, capture before/after audio metrics or logs that demonstrate latency and
  correctness as outlined in Section 20 of `spec.md`.
- Document test commands and outcomes in commit messages and final reports; if a test cannot be run,
  explain why.

## Security & Compliance
- Honour the spec's safety constraints: do not widen root privileges, relax SELinux policies, or alter
  Magisk module behaviour without explicit justification.
- Ensure presets and configuration files remain backwards compatible and validate any schema changes
  against the documented JSON structure.

## Pull Requests
- Craft small, focused commits with descriptive messages summarising the key change and scope.
- PR summaries must highlight user-visible changes first, followed by internal refactors and
  documentation updates.
- Always mention documentation updates explicitly when they are part of the change.
- Attach log snippets, screenshots, or audio samples when they clarify behaviour or UI changes.
