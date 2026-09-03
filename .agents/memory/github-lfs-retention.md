---
name: GitHub LFS retention
description: Provider behavior after Git LFS pointers are removed from a repository.
---

On GitHub, removing an LFS-tracked file or its pointer from repository history does not remove the corresponding LFS object from remote storage or from the Git LFS storage quota. The documented purge path is deleting and recreating the repository; otherwise GitHub Support may help purge an object.

**Why:** A branch cleanup can make the transfer catalog empty while the hosted LFS payload remains billed and retained.

**How to apply:** Treat Git pointer cleanup and provider storage reclamation as separate checks. Keep any repository deletion/recreation procedure restricted to the disposable hosted-contract repository, never production.

Source: https://docs.github.com/en/repositories/working-with-files/managing-large-files/removing-files-from-git-large-file-storage#git-lfs-objects-in-your-repository