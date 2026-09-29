package org.danilopianini.gradle.git.hooks.test

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import org.gradle.testkit.runner.GradleRunner

/**
 * Two concurrent Gradle invocations on the same repository must serialize on the manifest lock
 * and never corrupt hooks nor the manifest.
 */
class ManifestConcurrencyTests : StringSpec({
    "concurrent Gradle invocations must not corrupt hooks nor the manifest" {
        val projectDir = createTempDirectory("concurrent-hooks").toFile()
        try {
            projectDir.resolve("settings.gradle.kts").writeText(
                """
                import org.gradle.kotlin.dsl.gitHooks

                plugins {
                    id("org.danilopianini.gradle-pre-commit-git-hooks")
                }

                gitHooks {
                    file(".git").mkdirs()
                    reversibleInstalls = true
                    when (System.getProperty("hookSet")) {
                        "a" -> {
                            hook("hook-a1") { from("#!/bin/sh") { "echo a1" } }
                            hook("hook-a2") { from("#!/bin/sh") { "echo a2" } }
                        }
                        else -> {
                            hook("hook-b1") { from("#!/bin/sh") { "echo b1" } }
                            hook("hook-b2") { from("#!/bin/sh") { "echo b2" } }
                        }
                    }
                    createHooks()
                }
                """.trimIndent(),
            )
            fun build(hookSet: String): String =
                GradleRunner
                    .create()
                    .withProjectDir(projectDir)
                    .withPluginClasspath()
                    .withArguments(
                        "tasks",
                        "-DhookSet=$hookSet",
                        "--console=plain",
                        "-Dorg.gradle.kotlin.dsl.scriptCompilationAvoidance=false",
                    ).build()
                    .output
            val pool = Executors.newFixedThreadPool(2)
            try {
                val outcomes = listOf("a", "b").map { hookSet ->
                    pool.submit(Callable { hookSet to build(hookSet) })
                }
                outcomes.forEach { result ->
                    val (hookSet, output) = result.get(10, TimeUnit.MINUTES)
                    println("build for hook set $hookSet completed:\n$output")
                }
            } finally {
                pool.shutdownNow()
            }
            val hooksDir = projectDir.resolve(".git/hooks")
            val manifest = hooksDir.resolve(".gradle-git-hooks-manifest.properties")
            manifest.isFile shouldBe true
            val manifestText = manifest.readText()
            listOf("hook-a1", "hook-a2", "hook-b1", "hook-b2").forEach { name ->
                manifestText shouldContain "hook.$name.digest=sha256:"
                val hookFile = hooksDir.resolve(name)
                hookFile.isFile shouldBe true
                hookFile.readText() shouldContain "echo ${name.removePrefix("hook-")}"
            }
        } finally {
            projectDir.deleteRecursively()
        }
    }
})
