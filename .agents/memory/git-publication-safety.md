---
name: Git publication safety
description: Safety rule for Git-backed publication rollbacks and local working tree changes.
---

Git-backed publication must reject a dirty working tree before synchronizing or creating publication files when a failed push may trigger a destructive rollback.

**Why:** A rollback to the previous commit can remove unrelated tracked or untracked local work that was present before the publication.

**How to apply:** Keep the pre-publication check before any generated chunk/manifest changes; preserve the clean-tree assumption for concurrent publication rollback behavior.