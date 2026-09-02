---
name: Object storage availability boundary
description: Durable design rule for object-backed transfer repositories.
---

Object-backed transfers should use separate metadata and blob namespaces, with the
manifest as the availability boundary: chunks may exist before publication, but
listing must expose only successfully readable manifests.

**Why:** Chunk uploads can be interrupted or fail independently. Exposing a
partial transfer would make download and integrity errors look like available
user data.

**How to apply:** Keep credentials in the SDK/provider environment, stream blob
uploads/downloads, paginate metadata listing, and preserve existing service-level
hash/size validation rather than weakening it in the adapter.