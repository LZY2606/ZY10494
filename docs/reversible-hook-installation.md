# Reversible hook installation (managed segments)

This document describes the design of the reversible, manifest-tracked hook installation
introduced alongside the legacy whole-file installation, the alternatives that were considered,
and the evidence backing each decision.

## Enabling

```kotlin
gitHooks {
    reversibleInstalls = true
    preCommit { tasks("ktlintCheck") }
    createHooks()              // install/upgrade managed segments
    // createHooks(dryRun = true)  // only report what would happen
    // removeHooks()              // remove only the segments managed by this plugin
}
```

When `reversibleInstalls` is `false` (the default), `createHooks()` executes exactly the legacy
code path: same output order, same error classification, same performance characteristics.
The new engine is only entered through the new flag.

## Ownership models: whole file vs. managed segment

**Whole-file ownership (legacy).** The plugin generates the entire hook file.
If the file exists with different content, the plugin warns and either stops or overwrites
(`createHooks(true)`). The plugin cannot tell *which parts* of the file it owns, so it cannot
uninstall itself, and a hand-written hook is either clobbered or left diverging forever.

**Managed-segment ownership (reversible).** The plugin owns only the region between

```
# >>> gradle-pre-commit-git-hooks managed block (do not edit between the markers) >>>
# <<< gradle-pre-commit-git-hooks managed block (do not edit between the markers) <<<
```

Everything outside the markers belongs to the user (or to other tools) and is preserved
byte-exactly — including CRLF line endings, missing trailing newlines, and permission bits.
The responsibility boundary is explicit and machine-checkable:

| Region | Owner | Plugin behavior |
| --- | --- | --- |
| Inside the markers | this plugin | create, upgrade, remove; digest-verified |
| Outside the markers | the user / other tools | never modified, never deleted |
| Fully-managed file (created by the plugin, no user content) | this plugin | deleted on `removeHooks()` |

## The manifest

Every installation is recorded in `.gradle-git-hooks-manifest.properties`, stored inside the
resolved hooks directory. The manifest is versioned (`manifest.version=1`) and records, per hook:

- `digest` — SHA-256 of the exact managed block (markers included) as written;
- `previousState` — `ABSENT` (the plugin created the file) or `PREEXISTING` (the file existed);
- `previousDigest` — SHA-256 of the pre-existing file content (forensics/recovery aid);
- `separatorNewline` — whether the plugin inserted a `\n` to detach its block from user content
  not ending with a newline, so removal can restore the file byte-exactly.

All operations are two-phase: the full plan is computed and validated (digests verified,
conflicts detected) *before* any file is written; each write is atomic (temporary file + move).
A failed installation therefore never leaves half-written hooks or a stale manifest.
Concurrent Gradle invocations serialize on a lock file next to the manifest
(cross-process `FileChannel.lock`, plus an in-process monitor for same-JVM builds).

## Digest-conflict recovery strategies

If the managed block on disk does not match the manifest digest, the block was edited manually
or by another tool. Alternatives considered:

1. **Silently overwrite the managed block.** Simple, but destroys user edits made inside the
   markers — exactly the data-loss scenario this feature exists to prevent. Rejected.
2. **Restore from a plugin-kept backup copy of the block.** Requires a second source of truth
   that can itself go stale or diverge; it also silently discards the user's edits. Rejected.
3. **Refuse with a conflict and document manual recovery (chosen).** The installer throws
   `ManagedHookConflictException` naming the hook, both digests, and the recovery options:
   move user changes outside the markers; or delete the hook file *and* the manifest to
   reinstall from scratch; or restore the block and run `removeHooks()`.
   No automatic action can guess the user's intent, so none is taken.

Evidence: `test-manifest-conflict` (a tampered block fails the build with
`MANAGED HOOK CONFLICT` and is not overwritten) and phases 10–11 of `test-manifest-replay`
(conflict on stray markers, conflict on tampered digests, missing-file conflict, and the
documented recovery via manifest reset).

## Manifest location with multiple worktrees and `core.hooksPath`

Options considered:

1. **Inside the resolved hooks directory (chosen).** The manifest always lives next to the
   hooks it describes, so it follows `core.hooksPath` automatically (parsed from
   `.git/config`, relative to the repository root) and needs no extra configuration.
   Each hooks directory is self-describing.
2. **In the repository root (e.g. `.git-hooks-manifest`).** Shared across worktrees, but it is
   a new untracked file polluting the working copy, and it cannot follow `core.hooksPath`
   setups where hooks live outside the repository entirely. Rejected.
3. **In the git common dir (`.git/`).** Shared by all worktrees, but hooks themselves are *not*
   shared when `core.hooksPath` differs per worktree, so a single manifest would describe
   files it cannot locate. Rejected.

Detached worktrees (a `.git` *file* with a `gitdir:` directive) remain unsupported for hook
creation, exactly as in the legacy path (a warning is emitted, see issue #396): the plugin
refuses to guess which git dir owns the hooks rather than risking a write in the wrong
repository.

## Safety rules (dangerous overwrites are refused)

- **Symbolic links** as hook targets are never written through (install and removal refuse).
- **Stray markers**: a file containing the managed-block markers without a manifest entry
  conflicts — another tool or a stale manual edit owns that block.
- **Digest mismatch** between manifest and file conflicts (see above).
- **Missing file** with a manifest entry conflicts.
- **Missing executable bit** on a pre-existing user file is preserved (the plugin warns that
  git will not run the hook); files created by the plugin are made executable.
- **CRLF / no trailing newline** user content is preserved byte-exactly across install and
  removal.
- **Concurrent invocations** serialize on the manifest lock file.
- **Hook names** must match `[A-Za-z0-9][A-Za-z0-9._-]*`, preventing path traversal outside
  the hooks directory.

## Verification evidence

- `test-manifest-replay` — full lifecycle replay in a temporary git repository:
  dry-run, install, user edit, upgrade, dry-run removal, uninstall, CRLF, missing exec bit,
  missing trailing newline, symlink refusal, stray-marker conflict with atomicity check,
  tamper conflict, recovery, and `core.hooksPath` redirection. Unmanaged content is checked
  byte-exactly; every assertion fails the build at the exact misbehaving step.
- `test-manifest-conflict` — a tampered managed block fails the build with a conflict.
- `ManifestConcurrencyTests` — two concurrent Gradle invocations on the same repository
  leave all hooks and the manifest consistent.
- Existing tests (`test0`, `test-issue-88`, `test-issue-248`, `test-issue-396`) run unchanged
  against the legacy path, proving the default behavior is untouched.
