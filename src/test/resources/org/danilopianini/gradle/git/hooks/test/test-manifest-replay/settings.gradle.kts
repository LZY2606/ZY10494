import org.danilopianini.gradle.git.hooks.GitHooksExtension
import org.gradle.kotlin.dsl.gitHooks

plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks")
}

/*
 * Replays the whole lifecycle of a reversible installation in a temporary git repository:
 * install -> user edit -> upgrade -> conflicts -> recovery -> uninstall.
 * Every assertion fails the build at the exact step that misbehaves.
 * New GitHooksExtension instances simulate subsequent Gradle invocations,
 * exactly as if the user had edited this script between two builds.
 */

val manifestPath = ".git/hooks/.gradle-git-hooks-manifest.properties"
val beginMarker = "# >>> gradle-pre-commit-git-hooks managed block (do not edit between the markers) >>>"

file(".git").mkdirs()

fun newInvocation(configure: GitHooksExtension.() -> Unit) = GitHooksExtension(rootDir).apply {
    reversibleInstalls = true
    configure()
}

// PHASE 1: dry-run must not write anything
gitHooks {
    reversibleInstalls = true
    preCommit { tasks("ktlintCheck") }
    commitMsg { from("#!/bin/sh") { "true" } }
    createHooks(dryRun = true)
    check(!file(".git/hooks/pre-commit").exists()) { "DRY-RUN VIOLATION: dry-run created the pre-commit hook" }
    check(!file(manifestPath).exists()) { "DRY-RUN VIOLATION: dry-run created the manifest" }
    // PHASE 2: real install
    createHooks()
}

val preCommit = file(".git/hooks/pre-commit")
check(preCommit.isFile) { "INSTALL FAILURE: pre-commit was not created" }
check(preCommit.canExecute()) { "INSTALL FAILURE: pre-commit is not executable" }
check(file(manifestPath).isFile) { "INSTALL FAILURE: the manifest was not created" }
val installedPreCommit = preCommit.readText()
check(beginMarker in installedPreCommit) { "INSTALL FAILURE: no managed block in pre-commit" }
check("./gradlew ktlintCheck" in installedPreCommit) { "INSTALL FAILURE: task invocation missing" }

// PHASE 3: the user prepends hand-written content to the generated hook
val userHeader = "#!/bin/sh\n# hand-written user header, must survive upgrades and uninstall\n"
preCommit.writeText(userHeader + installedPreCommit)

// PHASE 4: upgrade from a new Gradle invocation with a changed script
newInvocation {
    hook("pre-commit") { from("#!/usr/bin/env bash") { "echo upgraded-v2" } }
    createHooks()
}
val upgraded = preCommit.readText()
check(upgraded.startsWith(userHeader)) { "UPGRADE FAILURE: user content was destroyed:\n$upgraded" }
check("echo upgraded-v2" in upgraded) { "UPGRADE FAILURE: new managed content was not installed" }
check("./gradlew ktlintCheck" !in upgraded) { "UPGRADE FAILURE: stale managed content survived the upgrade" }

// PHASE 5: dry-run removal must not write anything
newInvocation { removeHooks(dryRun = true) }
check(preCommit.readText() == upgraded) { "DRY-RUN VIOLATION: dry-run removal modified pre-commit" }
check(file(manifestPath).isFile) { "DRY-RUN VIOLATION: dry-run removal deleted the manifest" }

// PHASE 6: uninstall removes only the managed segments
newInvocation { removeHooks() }
check(preCommit.readText() == userHeader) {
    "UNINSTALL FAILURE: user content was not restored byte-exactly. " +
        "Expected:\n$userHeader\nActual:\n${preCommit.readText()}"
}
check(!file(".git/hooks/commit-msg").exists()) {
    "UNINSTALL FAILURE: the fully-managed commit-msg hook should have been deleted"
}
check(!file(manifestPath).exists()) { "UNINSTALL FAILURE: the manifest should be gone after a full uninstall" }

// PHASE 7: CRLF content without the executable bit is preserved byte-exactly
val crlfContent = "#!/bin/sh\r\necho user-owned\r\n"
val crlfHook = file(".git/hooks/pre-push")
crlfHook.writeBytes(crlfContent.toByteArray(Charsets.UTF_8))
crlfHook.setExecutable(false)
newInvocation {
    hook("pre-push") { from("#!/bin/sh") { "echo managed" } }
    createHooks()
}
check(String(crlfHook.readBytes(), Charsets.UTF_8).startsWith(crlfContent)) {
    "CRLF FAILURE: user content was modified on install"
}
newInvocation { removeHooks() }
check(String(crlfHook.readBytes(), Charsets.UTF_8) == crlfContent) {
    "CRLF FAILURE: CRLF content was not restored byte-exactly"
}

// PHASE 8: user content without a trailing newline is preserved byte-exactly
val noNewlineContent = "#!/bin/sh\necho no-trailing-newline"
val noNewlineHook = file(".git/hooks/pre-rebase")
noNewlineHook.writeText(noNewlineContent)
newInvocation {
    hook("pre-rebase") { from("#!/bin/sh") { "echo managed" } }
    createHooks()
}
newInvocation { removeHooks() }
check(noNewlineHook.readText() == noNewlineContent) {
    "SEPARATOR FAILURE: content without trailing newline was not restored byte-exactly"
}

// PHASE 9: symbolic links are never written through
val symlink = file(".git/hooks/post-commit")
java.nio.file.Files.createSymbolicLink(symlink.toPath(), file("somewhere-else").toPath())
val symlinkFailure = runCatching {
    newInvocation {
        hook("post-commit") { from("#!/bin/sh") { "echo x" } }
        createHooks()
    }
}.exceptionOrNull()
check(symlinkFailure != null && "symbolic link" in symlinkFailure.message.orEmpty()) {
    "SYMLINK FAILURE: expected a symbolic-link refusal, got: $symlinkFailure"
}

// PHASE 10: a stray marker without a manifest entry conflicts, and nothing gets half-written
val strayHook = file(".git/hooks/atomic-b")
strayHook.writeText("#!/bin/sh\n$beginMarker\necho stray\n")
val atomicFailure = runCatching {
    newInvocation {
        hook("atomic-a") { from("#!/bin/sh") { "echo a" } }
        hook("atomic-b") { from("#!/bin/sh") { "echo b" } }
        createHooks()
    }
}.exceptionOrNull()
check(atomicFailure != null && "CONFLICT" in atomicFailure.message.orEmpty()) {
    "CONFLICT FAILURE: expected a stray-marker conflict, got: $atomicFailure"
}
check(!file(".git/hooks/atomic-a").exists()) {
    "ATOMICITY FAILURE: the failed installation left a half-written atomic-a hook"
}
check(!file(manifestPath).exists()) { "ATOMICITY FAILURE: the failed installation left the manifest behind" }

// PHASE 11: tampering with the managed block conflicts; recovery requires a manifest reset
newInvocation {
    hook("commit-msg") { from("#!/bin/sh") { "echo v1" } }
    createHooks()
}
val msgHook = file(".git/hooks/commit-msg")
msgHook.writeText(msgHook.readText().replace("echo v1", "echo tampered"))
val tamperFailure = runCatching {
    newInvocation {
        hook("commit-msg") { from("#!/bin/sh") { "echo v2" } }
        createHooks()
    }
}.exceptionOrNull()
check(tamperFailure != null && "CONFLICT" in tamperFailure.message.orEmpty()) {
    "TAMPER FAILURE: expected a digest conflict, got: $tamperFailure"
}
check("echo tampered" in msgHook.readText()) { "TAMPER FAILURE: the conflicting hook was overwritten" }
val tamperedRemoval = runCatching { newInvocation { removeHooks() } }.exceptionOrNull()
check(tamperedRemoval != null && "CONFLICT" in tamperedRemoval.message.orEmpty()) {
    "TAMPER FAILURE: removal should also refuse a tampered block, got: $tamperedRemoval"
}
check(msgHook.delete()) { "could not delete commit-msg" }
val missingFailure = runCatching {
    newInvocation {
        hook("commit-msg") { from("#!/bin/sh") { "echo v2" } }
        createHooks()
    }
}.exceptionOrNull()
check(missingFailure != null && "CONFLICT" in missingFailure.message.orEmpty()) {
    "RECOVERY FAILURE: expected a missing-file conflict, got: $missingFailure"
}
check(file(manifestPath).delete()) { "could not delete the manifest" }
newInvocation {
    hook("commit-msg") { from("#!/bin/sh") { "echo v2" } }
    createHooks()
}
check("echo v2" in msgHook.readText()) { "RECOVERY FAILURE: reinstall after the manifest reset failed" }

// PHASE 12: core.hooksPath redirects both the hooks and the manifest
file(".git/config").writeText("[core]\n\thooksPath = custom-hooks\n")
newInvocation {
    hook("pre-commit") { from("#!/bin/sh") { "echo custom-dir" } }
    createHooks()
}
check(file("custom-hooks/pre-commit").isFile) { "HOOKSPATH FAILURE: core.hooksPath was not honored" }
check(file("custom-hooks/${manifestPath.substringAfterLast('/')}").isFile) {
    "HOOKSPATH FAILURE: the manifest did not follow core.hooksPath"
}
newInvocation { removeHooks() }
check(!file("custom-hooks/pre-commit").exists()) { "HOOKSPATH FAILURE: removal did not follow core.hooksPath" }
check(file(".git/config").delete()) { "could not clean up .git/config" }
