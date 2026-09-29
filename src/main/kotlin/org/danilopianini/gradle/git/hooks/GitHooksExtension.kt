package org.danilopianini.gradle.git.hooks

import java.io.File
import java.io.Serializable
import org.gradle.api.initialization.Settings
import org.gradle.api.logging.Logging

/**
 * DSL entry point, to be applied to [settings].gradle.kts.
 */
open class GitHooksExtension(repoRoot: File) : Serializable {
    private var hooks: Map<String, String> = emptyMap()
    private var pathHasBeenManuallySet = false

    constructor(settings: Settings) : this(settings.rootDir)

    /**
     * Enables reversible, manifest-tracked hook installation.
     *
     * When enabled, [createHooks] installs every generated script as a *managed segment* delimited by
     * marker lines, instead of owning the whole hook file. Content written by users or other tools
     * outside the managed markers is preserved byte-exactly, installations can be previewed with
     * `createHooks(dryRun = true)`, and [removeHooks] uninstalls only the segments managed by this plugin.
     * Every installation is recorded in a versioned manifest stored next to the hooks
     * (see `docs/reversible-hook-installation.md`).
     *
     * Disabled by default: when `false`, [createHooks] keeps its legacy whole-file behavior,
     * with unchanged output, errors, and performance characteristics.
     */
    var reversibleInstalls: Boolean = false

    /**
     * The git repository root. If unset, it will be searched recursively from the project root towards the
     * filesystem root.
     */
    var repoRoot: File = repoRoot
        set(value) {
            require(value.isGitRoot()) {
                "${value.absolutePath} is not a valid git root (it must contain a .git folder)"
            }
            pathHasBeenManuallySet = true
            field = value
        }
        get() =
            field.takeIf { pathHasBeenManuallySet }
                ?: generateSequence(field) { it.parentFile }.find { it.isGitRoot() }
                ?: error(
                    """
                    No git root could be found in ${field.absolutePath} or any of its parent directories.
                    You may want to set it manually with:
                    gitHooks {
                        repoRoot = File("<path>")
                    }
                    """.trimIndent(),
                )

    private val gitDir get() = File(repoRoot, ".git")

    private inline fun <H : ScriptContext> hook(context: H, configuration: H.() -> Unit) {
        require(!hooks.containsKey(context.name)) {
            "it looks like the hook ${context.name} is being defined twice"
        }
        hooks = hooks + (context.name to context.apply(configuration).script)
    }

    /**
     * Defines a new hook with an arbitrary name.
     */
    fun hook(hookName: String, configuration: ScriptContext.() -> Unit) =
        hook(CommonScriptContext(hookName), configuration)

    /**
     * Pre-commit hook.
     */
    fun preCommit(configuration: ScriptContext.() -> Unit) = hook("pre-commit", configuration)

    /**
     * Commit-msg hook.
     */
    fun commitMsg(configuration: CommitMsgScriptContext.() -> Unit): Unit =
        hook(CommitMsgScriptContext(), configuration)

    /**
     * To be called to force the hook creation in case of necessity.
     * If passed `true`, overwrites in case the script is already present and different than expected.
     *
     * When [reversibleInstalls] is enabled, [overwriteExisting] is ignored (managed segments are always
     * upgraded in place, and user content is never overwritten) and [dryRun] can be used to only
     * report what would be done, without writing any file.
     */
    fun createHooks(overwriteExisting: Boolean = false, dryRun: Boolean = false) {
        if (gitDir.isFile && "gitdir:" in gitDir.readText()) {
            // gets a logger from the settings object
            Logging.getLogger(GitHooksExtension::class.java).warn(
                """
                |The git root does not contain a .git folder, but a .git file with a gitdir: directive.
                |This is typically an indication that the git repository is a detached worktree.
                |Hooks generation in detached worktrees is not supported, see:
                |https://github.com/DanySK/gradle-pre-commit-git-hooks/issues/396
                """.trimMargin().replace(Regex("\\R"), "\n"),
            )
        } else if (reversibleInstalls) {
            ManagedHookInstaller(resolveHooksDir()).install(hooks, dryRun)
        } else {
            require(!dryRun) {
                "createHooks(dryRun = true) requires reversibleInstalls = true: " +
                    "the legacy whole-file installation has no dry-run support"
            }
            actuallyCreateHooks(overwriteExisting)
        }
    }

    /**
     * Removes only the hook segments managed by this plugin, restoring any pre-existing user content
     * byte-exactly. Requires [reversibleInstalls] to be enabled.
     * With [dryRun], only reports what would be done, without writing any file.
     */
    fun removeHooks(dryRun: Boolean = false) {
        check(reversibleInstalls) {
            "removeHooks() requires reversibleInstalls = true: " +
                "only manifest-tracked, managed segments can be removed safely"
        }
        ManagedHookInstaller(resolveHooksDir()).remove(hookNames = null, dryRun = dryRun)
    }

    private fun resolveHooksDir(): File {
        val configured = coreHooksPath()
        return when {
            configured.isNullOrBlank() -> File(gitDir, "hooks")
            else -> File(configured).takeIf { it.isAbsolute } ?: File(repoRoot, configured)
        }
    }

    private fun coreHooksPath(): String? {
        val config = File(gitDir, "config")
        if (!config.isFile) {
            return null
        }
        var inCoreSection = false
        config.readLines(Charsets.UTF_8).forEach { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("[") ->
                    inCoreSection = trimmed.removePrefix("[").substringBefore("]").trim().equals("core", true)
                inCoreSection && "=" in trimmed &&
                    trimmed.substringBefore("=").trim().equals("hooksPath", ignoreCase = true) ->
                    return trimmed.substringAfter("=").trim()
                else -> Unit
            }
        }
        return null
    }

    private fun actuallyCreateHooks(overwriteExisting: Boolean) {
        if (gitDir.isFile) {
            error(
                """
                ${gitDir.absolutePath} exists but it is not a directory. It contains:
                ${gitDir.readText()}
                If you are working on a detached worktree, this is likely a regression of issue #396, see:
                https://github.com/DanySK/gradle-pre-commit-git-hooks/issues/396
                """.trimIndent(),
            )
        }
        val hooksFolder = File(gitDir, "hooks")
        if (!hooksFolder.exists()) {
            check(hooksFolder.mkdirs()) {
                "Directory ${hooksFolder.absolutePath} does not exist and can not be created"
            }
        }
        check(hooksFolder.exists()) {
            "${hooksFolder.absolutePath} should have been initialized, but it does not exist"
        }
        check(hooksFolder.isDirectory) {
            "${hooksFolder.absolutePath} has been initialized, but it is a file, not a directory"
        }
        hooks.forEach { (name, script) ->
            val hook = File(hooksFolder, name)
            if (!hook.exists()) {
                require(hook.createNewFile()) { "Cannot create file ${hook.absolutePath}" }
                hook.writeScript(script)
            } else {
                val oldScript = hook.readText()
                if (oldScript != script) {
                    println(
                        """
                        |The hook $name exists, but its content differs from the one generated by the git-hooks plugin.
                        |
                        |Original content:
                        ${oldScript.withMargins()}
                        |
                        |New content:
                        ${script.withMargins()}
                        |
                        |If you want to overwrite this file on every change add true to the createHooks(true) 
                        |method in your settings.gradle.kts
                        """.trimMargin().lines().joinToString(separator = "\n") {
                            "WARNING: $it"
                        },
                    )
                    if (overwriteExisting) {
                        println("WARNING: Overwriting git hook $name")
                        hook.writeScript(script)
                    }
                }
            }
        }
    }

    internal companion object {
        private const val serialVersionUID = 2L

        /**
         * Extension name.
         */
        internal const val NAME: String = "gitHooks"

        private fun String.withMargins() = lines().joinToString(separator = "\n|", prefix = "|")

        private fun File.writeScript(script: String) {
            writeText(script)
            setExecutable(true)
        }

        private fun File.isGitRoot(): Boolean = listFiles()
            ?.any { file -> file.name == ".git" }
            ?: false
    }
}
