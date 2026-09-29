import org.danilopianini.gradle.git.hooks.GitHooksExtension
import org.gradle.kotlin.dsl.gitHooks

plugins {
    id("org.danilopianini.gradle-pre-commit-git-hooks")
}

gitHooks {
    file(".git").mkdirs()
    reversibleInstalls = true
    commitMsg { from("#!/bin/sh") { "echo v1" } }
    createHooks()
}

// The user edits the managed block between two Gradle invocations...
val hook = file(".git/hooks/commit-msg")
hook.writeText(hook.readText().replace("echo v1", "echo hand-tampered"))

// ...so the next invocation must refuse to overwrite and fail with a conflict.
GitHooksExtension(rootDir).apply {
    reversibleInstalls = true
    hook("commit-msg") { from("#!/bin/sh") { "echo v2" } }
    createHooks()
}
