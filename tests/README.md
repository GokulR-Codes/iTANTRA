# tests/

Cross-module test suites that don't belong to a single Gradle module (§38):
unit/integration tests that span multiple modules, plus categories that
need real hardware or a real network and therefore cannot run in every CI
push.

- `unit/` — fast, pure-logic, cross-module tests (most module-local unit
  tests live inside each module's own `src/test/`, per Gate 1's modules —
  this directory is for tests that would otherwise force two modules to
  depend on each other just for a test)
- `integration/` — multi-module wiring tests (e.g. codec -> protocol
  round-trip) that don't need a device
- `hardware/` — requires a real or emulated Android device; not run on
  every push (§39's CI/CD strategy — scheduled, not per-commit)
- `model/` — requires a loaded STT/TTS/LID model; empty until Gate 2+
- `network/` — requires two real transports talking to each other (or
  tools/loss_injection.py standing in for one); loopback-only for now
- `stress/` — long-running / load tests, scheduled not per-commit

**Status: empty except this README.** Gate 1's actual tests live inside
each module (`core/src/test/`, `protocol/src/test/`, `vad/src/test/`,
`codec/src/test/`, `benchmark/src/test/`) because they test one module in
isolation. This directory starts filling in once there is more than one
real (non-stub) implementation to test *across* modules.
