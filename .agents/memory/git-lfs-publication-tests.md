---
name: Git LFS publication tests
description: Constraint for simulating rejected Git LFS pushes in local repository tests.
---

When a test needs to reject a Git LFS push while editing the clone, reject it from the bare remote's `pre-receive` hook rather than replacing the clone's `pre-push` hook.

**Why:** `git lfs install --local` refuses a custom pre-push hook, and the adapter initializes Git LFS as part of the first chunk publication.

**How to apply:** Install the remote hook after creating the bare test repository, write the concurrent edit to the clone using an absolute path, and exit non-zero after the edit.