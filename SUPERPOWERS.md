# Superpowers (vendored)

This project vendors [obra/superpowers](https://github.com/obra/superpowers) as a git
submodule at [`vendor/superpowers/`](./vendor/superpowers). Superpowers is a complete
software-development methodology for coding agents — TDD, systematic debugging,
brainstorming, planning, code review, git worktrees, and more.

## What's included

The submodule ships plugin manifests and a session-start hook for several harnesses:

| Harness | Manifest |
| ------- | -------- |
| Claude Code | `vendor/superpowers/.claude-plugin/plugin.json` |
| Cursor | `vendor/superpowers/.cursor-plugin/plugin.json` |
| Codex | `vendor/superpowers/.codex-plugin/plugin.json` |
| Kimi Code | `vendor/superpowers/.kimi-plugin/plugin.json` |
| OpenCode | `vendor/superpowers/.opencode/` |
| Pi | `vendor/superpowers/.pi/` |

Skills live in [`vendor/superpowers/skills/`](./vendor/superpowers/skills) and cover:
brainstorming, writing-plans, executing-plans, subagent-driven-development,
test-driven-development, systematic-debugging, verification-before-completion,
using-git-worktrees, finishing-a-development-branch, requesting/receiving-code-review,
dispatching-parallel-agents, writing-skills, and using-superpowers.

## How to install in your harness

### Claude Code

From the photoz repo root:

```bash
/plugin marketplace add ./vendor/superpowers
/plugin install superpowers@superpowers-dev
```

Or use the project-local marketplace manifest at
[`.claude-plugin/marketplace.json`](./.claude-plugin/marketplace.json):

```bash
/plugin marketplace add .
/plugin install superpowers@photoz-local
```

Re-run the same two commands to update after `git submodule update --remote`.

### Cursor

```text
/add-plugin superpowers
```

then point Cursor at `vendor/superpowers/.cursor-plugin/plugin.json` when prompted.

### Codex CLI

```bash
/plugins
# search "superpowers", install from local path: ./vendor/superpowers
```

### OpenCode

Tell OpenCode:

```
Fetch and follow instructions from https://raw.githubusercontent.com/obra/superpowers/refs/heads/main/.opencode/INSTALL.md
```

…then point it at `./vendor/superpowers` instead of cloning a fresh copy.

### Pi

```bash
pi install file:./vendor/superpowers
```

## Updating Superpowers

```bash
git submodule update --remote vendor/superpowers
git add vendor/superpowers
git commit -m "chore: bump vendored superpowers"
```

## Disabling Superpowers

If a session doesn't need Superpowers, uninstall the plugin from your harness
(`/plugin uninstall superpowers`) — the vendored copy stays on disk for the next
session that wants it.
