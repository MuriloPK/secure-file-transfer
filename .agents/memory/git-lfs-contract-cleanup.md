---
name: Git LFS contract cleanup failures
description: Failure semantics for scheduled hosted Git LFS contract cleanup.
---

Scheduled Git LFS contract cleanup is part of the contract result, not best-effort
logging: a failed cleanup publication must fail the run and retain the transfer
UUID and exact cleanup path in a sanitized diagnostic. If a primary contract
stage already failed, preserve that failure as the result and attach the cleanup
failure as supplemental evidence.

**Why:** A warning can leave LFS pointers and transfer directories accumulating
without an actionable signal, while replacing the primary failure makes the
actual contract regression harder to diagnose.

**How to apply:** Treat cleanup failures as fatal in hosted contract tests and
keep provider responses out of the diagnostic; use the transfer identifier and
path for manual recovery.