# Commit Policy (Conventional Commits)

Format:

<type>(<scope>): <subject>

Types: `build|chore|ci|docs|feat|fix|perf|refactor|revert|style|test`  
Subject ≤ 72 chars, no trailing dot. `WIP` commits are rejected.

Examples:
- `feat(app): add error pages with catalog`
- `fix(integrations): respect Retry-After header`
- `chore(ci): add gitleaks workflow`

Hooks:
- `tools/git-hooks/commit-msg` — enforces Conventional Commits.
