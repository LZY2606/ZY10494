package org.danilopianini.gradle.git.hooks

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Signals that the on-disk state of a hook disagrees with the installation manifest,
 * or that installing/removing a managed block would overwrite user content.
 * The plugin refuses to continue and no file is modified.
 */
class ManagedHookConflictException(message: String) : IllegalStateException(message)

/**
 * Installs and removes Gradle-generated git hooks as *managed segments* delimited by marker lines,
 * tracking every installed segment in a versioned manifest stored next to the hooks.
 *
 * The installer never overwrites content it does not own:
 * user content living outside the managed markers is preserved byte-exactly,
 * and any disagreement between the manifest and the actual files raises a [ManagedHookConflictException].
 *
 * All operations are two-phase: the whole plan is computed and validated first,
 * and only then every file is written atomically (temporary file + move),
 * so a failure cannot leave half-written hooks behind.
 * A lock file serializes concurrent Gradle invocations, both in-process and across processes.
 */
internal class ManagedHookInstaller(
    private val hooksDir: File,
    private val log: (String) -> Unit = ::println,
) {
    private val manifestFile = File(hooksDir, MANIFEST_FILE_NAME)
    private val lockFile = File(hooksDir, "$MANIFEST_FILE_NAME.lock")

    /**
     * Installs (or upgrades) the provided [hooks] as managed segments.
     * With [dryRun], only reports what would be done, writing nothing.
     */
    fun install(hooks: Map<String, String>, dryRun: Boolean) {
        val invalidNames = hooks.keys.filterNot { it.matches(VALID_HOOK_NAME) }
        require(invalidNames.isEmpty()) {
            "Invalid hook names: $invalidNames. " +
                "Hook names must match ${VALID_HOOK_NAME.pattern} to be safely installable as managed segments"
        }
        locked {
            val manifest = HookManifest.load(manifestFile)
            val plans = hooks.map { (name, script) -> planInstall(manifest, name, script) }
            if (dryRun) {
                plans.forEach { log("DRY-RUN: ${it.description}") }
            } else {
                plans.forEach { it.execute() }
                plans.forEach { plan -> manifest.entries[plan.hook] = requireNotNull(plan.entry) }
                manifest.save(manifestFile)
            }
        }
    }

    /**
     * Removes the managed segments of [hookNames] (or every managed hook, if `null`),
     * restoring any pre-existing user content byte-exactly.
     */
    fun remove(hookNames: Set<String>?, dryRun: Boolean) {
        locked {
            val manifest = HookManifest.load(manifestFile)
            val targets = hookNames?.toList() ?: manifest.entries.keys.toList()
            if (targets.isEmpty()) {
                log("No managed hooks found in ${hooksDir.absolutePath}, nothing to remove")
                return@locked
            }
            val unknown = targets - manifest.entries.keys
            require(unknown.isEmpty()) {
                "The hooks $unknown are not managed by the ${GitHooksExtension.NAME} plugin: " +
                    "no entry in ${manifestFile.absolutePath}. Refusing to touch them"
            }
            val plans = targets.map { planRemoval(manifest, it) }
            if (dryRun) {
                plans.forEach { log("DRY-RUN: ${it.description}") }
            } else {
                plans.forEach { it.execute() }
                targets.forEach { manifest.entries.remove(it) }
                manifest.save(manifestFile)
            }
        }
    }

    private fun planInstall(manifest: HookManifest, name: String, script: String): WritePlan {
        val target = File(hooksDir, name)
        val block = managedBlock(script)
        val digest = sha256(block)
        if (Files.isSymbolicLink(target.toPath())) {
            throw ManagedHookConflictException(
                "REFUSING TO INSTALL: the hook '$name' at ${target.absolutePath} is a symbolic link. " +
                    "The ${GitHooksExtension.NAME} plugin never writes through symbolic links",
            )
        }
        val content: String? = target.takeIf { it.exists() }?.let {
            check(it.isFile) {
                "REFUSING TO INSTALL: ${it.absolutePath} exists and is not a regular file"
            }
            it.readText(Charsets.UTF_8)
        }
        val tracked = manifest.entries[name]
        return when {
            tracked != null -> {
                if (content == null) {
                    throw ManagedHookConflictException(
                        "MANAGED HOOK CONFLICT: the manifest ${manifestFile.absolutePath} tracks the hook '$name', " +
                            "but ${target.absolutePath} does not exist. $RECOVERY_HINT",
                    )
                }
                val range = locateManagedBlock(content, tracked, name)
                if (content.substring(range) == block) {
                    WritePlan(name, tracked, "hook '$name' is up-to-date") { }
                } else {
                    val newContent = content.replaceRange(range, block)
                    WritePlan(name, tracked.copy(digest = digest), "upgrade the managed block of hook '$name'") {
                        writeAtomically(target, newContent, executable = false)
                    }
                }
            }
            content == null ->
                WritePlan(name, ManifestEntry(digest, PreviousState.ABSENT, null, separatorNewline = false),
                    "create the hook '$name' as a fully managed file") {
                    writeAtomically(target, block + "\n", executable = true)
                }
            BEGIN_MARKER in content ->
                throw ManagedHookConflictException(
                    "MANAGED HOOK CONFLICT: the hook '$name' at ${target.absolutePath} already contains " +
                        "a managed block marker, but no manifest entry exists in ${manifestFile.absolutePath}. " +
                        "Another tool (or a stale manual edit) owns that block. $RECOVERY_HINT",
                )
            else -> {
                val separator = content.isNotEmpty() && !content.endsWith("\n")
                val newContent = content + (if (separator) "\n" else "") + block + "\n"
                if (!target.canExecute()) {
                    log(
                        "WARNING: the existing hook '$name' at ${target.absolutePath} is not executable. " +
                            "The managed block was appended, but git will not run the hook until it is made executable",
                    )
                }
                WritePlan(
                    name,
                    ManifestEntry(digest, PreviousState.PREEXISTING, sha256(content), separator),
                    "append a managed block to the existing hook '$name' (user content is preserved)",
                ) {
                    writeAtomically(target, newContent, executable = false)
                }
            }
        }
    }

    private fun planRemoval(manifest: HookManifest, name: String): WritePlan {
        val entry = checkNotNull(manifest.entries[name]) {
            "Internal error: removal planned for untracked hook '$name'"
        }
        val target = File(hooksDir, name)
        if (Files.isSymbolicLink(target.toPath())) {
            throw ManagedHookConflictException(
                "REFUSING TO REMOVE: the hook '$name' at ${target.absolutePath} is a symbolic link. " +
                    "The ${GitHooksExtension.NAME} plugin never writes through symbolic links",
            )
        }
        if (!target.exists()) {
            throw ManagedHookConflictException(
                "MANAGED HOOK CONFLICT: the manifest ${manifestFile.absolutePath} tracks the hook '$name', " +
                    "but ${target.absolutePath} does not exist. $RECOVERY_HINT",
            )
        }
        val content = target.readText(Charsets.UTF_8)
        val range = locateManagedBlock(content, entry, name)
        var start = range.first
        var end = range.last + 1
        if (end < content.length && content[end] == '\n') {
            end += 1
        }
        if (entry.separatorNewline && start > 0 && content[start - 1] == '\n') {
            start -= 1
        }
        val remaining = content.removeRange(start, end)
        return if (entry.previousState == PreviousState.ABSENT && remaining.isBlank()) {
            WritePlan(name, null, "delete the fully managed hook '$name'") {
                check(target.delete() || !target.exists()) {
                    "Could not delete ${target.absolutePath}"
                }
            }
        } else {
            WritePlan(name, null, "remove the managed block of hook '$name' (user content is preserved)") {
                writeAtomically(target, remaining, executable = false)
            }
        }
    }

    private fun locateManagedBlock(content: String, entry: ManifestEntry, name: String): IntRange {
        val target = File(hooksDir, name)
        val begin = content.indexOf(BEGIN_MARKER)
        val end = if (begin < 0) -1 else content.indexOf(END_MARKER, startIndex = begin)
        if (begin < 0 || end < 0) {
            throw ManagedHookConflictException(
                "MANAGED HOOK CONFLICT: the manifest tracks the hook '$name', " +
                    "but the managed block markers are missing from ${target.absolutePath}. " +
                    "The file was modified outside the plugin. $RECOVERY_HINT",
            )
        }
        val actualDigest = sha256(content.substring(begin, end + END_MARKER.length))
        if (actualDigest != entry.digest) {
            throw ManagedHookConflictException(
                "MANAGED HOOK CONFLICT: the managed block of '$name' in ${target.absolutePath} " +
                    "does not match the digest recorded in ${manifestFile.absolutePath} " +
                    "(expected ${entry.digest}, found $actualDigest). " +
                    "The managed section was likely edited manually or by another tool. " +
                    "Refusing to overwrite. $RECOVERY_HINT",
            )
        }
        return begin until end + END_MARKER.length
    }

    private fun <T> locked(operation: () -> T): T {
        check(hooksDir.isDirectory || hooksDir.mkdirs()) {
            "Cannot create the hooks directory ${hooksDir.absolutePath}"
        }
        val inProcessLock = IN_PROCESS_LOCKS.getOrPut(hooksDir.absoluteFile.normalize().path) { Any() }
        return synchronized(inProcessLock) {
            FileChannel.open(lockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                channel.lock().use {
                    operation()
                }
            }
        }
    }

    private fun writeAtomically(target: File, content: String, executable: Boolean) {
        val temporary = File.createTempFile(".gradle-git-hooks-", ".tmp", hooksDir)
        try {
            temporary.writeText(content, Charsets.UTF_8)
            if (executable) {
                temporary.setExecutable(true, false)
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.recoverCatching {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }.getOrThrow()
        } finally {
            temporary.delete()
        }
    }

    private enum class PreviousState { ABSENT, PREEXISTING }

    private data class ManifestEntry(
        val digest: String,
        val previousState: PreviousState,
        val previousDigest: String?,
        val separatorNewline: Boolean,
    )

    private class WritePlan(
        val hook: String,
        val entry: ManifestEntry?,
        val description: String,
        val execute: () -> Unit,
    )

    private class HookManifest(val entries: MutableMap<String, ManifestEntry>) {
        fun save(file: File) {
            if (entries.isEmpty()) {
                file.delete()
                return
            }
            val lines = buildList {
                add("$VERSION_KEY=$MANIFEST_VERSION")
                entries.toSortedMap().forEach { (name, entry) ->
                    add("$HOOK_PREFIX$name.digest=${entry.digest}")
                    add("$HOOK_PREFIX$name.previousState=${entry.previousState}")
                    entry.previousDigest?.let { add("$HOOK_PREFIX$name.previousDigest=$it") }
                    add("$HOOK_PREFIX$name.separatorNewline=${entry.separatorNewline}")
                }
            }
            val temporary = File.createTempFile(".manifest-", ".tmp", file.parentFile)
            try {
                temporary.writeText(lines.joinToString(separator = "\n", postfix = "\n"), Charsets.UTF_8)
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }.recoverCatching {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }.getOrThrow()
            } finally {
                temporary.delete()
            }
        }

        companion object {
            fun load(file: File): HookManifest {
                if (!file.isFile) {
                    return HookManifest(mutableMapOf())
                }
                val properties = Properties().apply { file.inputStream().use { load(it) } }
                val version = properties.getProperty(VERSION_KEY)?.toIntOrNull()
                    ?: throw ManagedHookConflictException(
                        "MANIFEST ERROR: ${file.absolutePath} has no readable '$VERSION_KEY' entry. " +
                            "The manifest is corrupted: delete it and reinstall the hooks to start fresh",
                    )
                if (version != MANIFEST_VERSION) {
                    throw ManagedHookConflictException(
                        "MANIFEST ERROR: unsupported manifest version $version in ${file.absolutePath} " +
                            "(this plugin supports version $MANIFEST_VERSION). " +
                            "Delete the manifest and the managed blocks, then reinstall the hooks",
                    )
                }
                val names = properties.stringPropertyNames()
                    .filter { it.startsWith(HOOK_PREFIX) }
                    .map { it.removePrefix(HOOK_PREFIX).substringBeforeLast('.') }
                    .toSet()
                val entries = names.associateWith { name ->
                    fun key(suffix: String) = "$HOOK_PREFIX$name.$suffix"
                    fun required(suffix: String): String = properties.getProperty(key(suffix))
                        ?: throw ManagedHookConflictException(
                            "MANIFEST ERROR: missing '${key(suffix)}' in ${file.absolutePath}. " +
                                "The manifest is corrupted: delete it and reinstall the hooks to start fresh",
                        )
                    val state = runCatching { PreviousState.valueOf(required("previousState")) }.getOrNull()
                        ?: throw ManagedHookConflictException(
                            "MANIFEST ERROR: invalid '${key("previousState")}' in ${file.absolutePath}. " +
                                "The manifest is corrupted: delete it and reinstall the hooks to start fresh",
                        )
                    ManifestEntry(
                        digest = required("digest"),
                        previousState = state,
                        previousDigest = properties.getProperty(key("previousDigest")),
                        separatorNewline = properties.getProperty(key("separatorNewline")).toBoolean(),
                    )
                }
                return HookManifest(entries.toMutableMap())
            }
        }
    }

    internal companion object {
        /**
         * Name of the manifest file, stored inside the resolved hooks directory.
         */
        internal const val MANIFEST_FILE_NAME = ".gradle-git-hooks-manifest.properties"

        /**
         * Line opening a managed block.
         */
        internal const val BEGIN_MARKER =
            "# >>> gradle-pre-commit-git-hooks managed block (do not edit between the markers) >>>"

        /**
         * Line closing a managed block.
         */
        internal const val END_MARKER =
            "# <<< gradle-pre-commit-git-hooks managed block (do not edit between the markers) <<<"

        private const val MANIFEST_VERSION = 1
        private const val VERSION_KEY = "manifest.version"
        private const val HOOK_PREFIX = "hook."
        private val VALID_HOOK_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]*")
        private val IN_PROCESS_LOCKS = ConcurrentHashMap<String, Any>()
        private val RECOVERY_HINT =
            """
            |Recovery options:
            |  1. inspect the hook file and move your changes outside the managed markers;
            |  2. delete the hook file and the manifest to reinstall from scratch;
            |  3. restore the managed block content, then run gitHooks { removeHooks() } to uninstall cleanly.
            """.trimMargin().replace(Regex("\\R"), " ")

        private fun managedBlock(script: String): String =
            "$BEGIN_MARKER\n${script.trim('\n')}\n$END_MARKER"

        private fun sha256(text: String): String =
            "sha256:" + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)),
            )
    }
}
