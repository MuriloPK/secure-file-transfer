---
name: GitHub tag protection validation
description: Preconditions for operationally validating release-tag rulesets through the GitHub integration.
---

Operational tag-protection checks require the intended GitHub repository to contain the workflow and at least one commit, plus a plan that exposes repository rulesets for private repositories. An empty private repository or a rulesets API response stating that GitHub Pro/public visibility is required cannot provide evidence about tag creation, update, deletion, or Actions gating.

Do not assume that a write-enabled deploy key created by an OAuth App solves missing workflow scope or represents a non-bypass actor. GitHub can retain the OAuth workflow restriction for the key, and write deploy keys can bypass tag rules.

**Why:** GitHub ruleset configuration is hosted outside the repository, while the workflow only validates tag ancestry; treating local documentation or an empty remote as proof would produce a false security confirmation. A bootstrap attempt also showed that an API-created deploy key was rejected for workflow changes yet bypassed tag creation restrictions.

**How to apply:** Identify the actual remote first, verify its workflow and commit history, then inspect the active tag ruleset. If OAuth lacks workflow scope, use a temporary repository-scoped credential through the secrets flow. Exercise non-bypass operations with a GitHub Actions token, and run disposable valid/invalid tag checks.