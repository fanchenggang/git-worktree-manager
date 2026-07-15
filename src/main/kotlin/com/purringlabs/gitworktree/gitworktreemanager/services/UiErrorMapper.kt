package com.purringlabs.gitworktree.gitworktreemanager.services

import com.purringlabs.gitworktree.gitworktreemanager.MyMessageBundle
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.NoRepositoryException
import com.purringlabs.gitworktree.gitworktreemanager.exceptions.WorktreeOperationException

/**
 * Maps throwables to a user-friendly message + actionable steps, while preserving exact technical details.
 */
object UiErrorMapper {

    data class UiError(
        val title: String,
        val summary: String,
        val actions: List<String> = emptyList(),
        val details: String? = null,
        val copyText: String? = null
    )

    fun map(throwable: Throwable, operation: String? = null): UiError {
        val root = rootCause(throwable)
        val msg = (root.message ?: throwable.message ?: MyMessageBundle.message("error.unknown")).trim()

        // Special-case: process start failure due to missing working directory
        if (isMissingWorkingDirectory(msg)) {
            val wd = extractMissingWorkingDirectory(msg)
            val details = buildDetails(
                operation = operation,
                command = null,
                workingDirectory = wd,
                exitCode = null,
                errorOutput = msg
            )

            return UiError(
                title = MyMessageBundle.message("error.missingProjectFolder.title"),
                summary = MyMessageBundle.message("error.missingProjectFolder.summary"),
                actions = listOf(
                    MyMessageBundle.message("error.missingProjectFolder.action1"),
                    MyMessageBundle.message("error.missingProjectFolder.action2"),
                    MyMessageBundle.message("error.missingProjectFolder.action3")
                ),
                details = details,
                copyText = buildCopyText(details)
            )
        }

        if (isLockedWorktreeDelete(msg)) {
            val details = buildDetails(operation = operation, errorOutput = msg)
            return UiError(
                title = MyMessageBundle.message("error.lockedWorktree.title"),
                summary = MyMessageBundle.message("error.lockedWorktree.summary"),
                actions = listOf(
                    MyMessageBundle.message("error.lockedWorktree.action1"),
                    MyMessageBundle.message("error.lockedWorktree.action2"),
                    MyMessageBundle.message("error.lockedWorktree.action3")
                ),
                details = details,
                copyText = buildCopyText(details)
            )
        }

        if (isPrunableWorktreeDelete(msg)) {
            val details = buildDetails(operation = operation, errorOutput = msg)
            return UiError(
                title = MyMessageBundle.message("error.prunableWorktree.title"),
                summary = MyMessageBundle.message("error.prunableWorktree.summary"),
                actions = listOf(
                    MyMessageBundle.message("error.prunableWorktree.action1"),
                    MyMessageBundle.message("error.prunableWorktree.action2"),
                    MyMessageBundle.message("error.prunableWorktree.action3")
                ),
                details = details,
                copyText = buildCopyText(details)
            )
        }

        return when (throwable) {
            is NoRepositoryException -> {
                val details = buildDetails(operation = operation, errorOutput = msg)
                UiError(
                    title = MyMessageBundle.message("error.noRepository.title"),
                    summary = MyMessageBundle.message("error.noRepository.summary"),
                    actions = listOf(
                        MyMessageBundle.message("error.noRepository.action1"),
                        MyMessageBundle.message("error.noRepository.action2"),
                        MyMessageBundle.message("error.noRepository.action3")
                    ),
                    details = details,
                    copyText = buildCopyText(details)
                )
            }

            is WorktreeOperationException -> {
                val errorOut = throwable.gitErrorOutput ?: msg

                // Special-case: broken worktree metadata (.git missing in the worktree directory)
                // Git will refuse to remove it and suggests a validation failure.
                val lower = errorOut.lowercase()
                if (lower.contains("validation failed") && lower.contains("cannot remove working tree") && lower.contains(".git") && lower.contains("does not exist")) {
                    val details = buildDetails(
                        operation = operation,
                        command = throwable.gitCommand,
                        workingDirectory = null,
                        exitCode = throwable.gitExitCode,
                        errorOutput = errorOut
                    )

                    return UiError(
                        title = MyMessageBundle.message("error.metadataMissing.title"),
                        summary = MyMessageBundle.message("error.metadataMissing.summary"),
                        actions = listOf(
                            MyMessageBundle.message("error.metadataMissing.action1"),
                            MyMessageBundle.message("error.metadataMissing.action2"),
                            MyMessageBundle.message("error.metadataMissing.action3")
                        ),
                        details = details,
                        copyText = buildCopyText(details)
                    )
                }

                val details = buildDetails(
                    operation = operation,
                    command = throwable.gitCommand,
                    workingDirectory = null,
                    exitCode = throwable.gitExitCode,
                    errorOutput = errorOut
                )

                UiError(
                    title = MyMessageBundle.message("error.gitFailed.title"),
                    summary = MyMessageBundle.message("error.gitFailed.summary"),
                    actions = listOf(
                        MyMessageBundle.message("error.gitFailed.action1"),
                        MyMessageBundle.message("error.gitFailed.action2"),
                        MyMessageBundle.message("error.gitFailed.action3")
                    ),
                    details = details,
                    copyText = buildCopyText(details)
                )
            }

            else -> {
                val details = buildDetails(operation = operation, errorOutput = msg)
                UiError(
                    title = MyMessageBundle.message("error.unexpected.title"),
                    summary = MyMessageBundle.message("error.unexpected.summary"),
                    actions = listOf(
                        MyMessageBundle.message("error.unexpected.action1"),
                        MyMessageBundle.message("error.unexpected.action2")
                    ),
                    details = details,
                    copyText = buildCopyText(details)
                )
            }
        }
    }

    private fun rootCause(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur
    }

    private fun isMissingWorkingDirectory(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("working directory") && (m.contains("does not exist") || m.contains("no such file"))
    }

    private fun isLockedWorktreeDelete(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("cannot delete locked worktree")
    }

    private fun isPrunableWorktreeDelete(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("stale/prunable") || (m.contains("git worktree prune") && m.contains("prunable"))
    }

    private fun extractMissingWorkingDirectory(message: String): String? {
        // Try to extract: working directory '/path' does not exist
        val regex = Regex("working directory ['\"]([^'\"]+)['\"]")
        return regex.find(message)?.groupValues?.getOrNull(1)?.let { sanitizePaths(it) }
    }

    private fun buildDetails(
        operation: String? = null,
        command: String? = null,
        workingDirectory: String? = null,
        exitCode: Int? = null,
        errorOutput: String? = null
    ): String {
        return buildString {
            appendLine(MyMessageBundle.message("error.detailsHeader"))
            if (!operation.isNullOrBlank()) appendLine(MyMessageBundle.message("error.details.operation", operation))
            if (!command.isNullOrBlank()) appendLine(MyMessageBundle.message("error.details.command", sanitizePaths(command)))
            if (!workingDirectory.isNullOrBlank()) {
                appendLine(MyMessageBundle.message("error.details.workingDirectory", sanitizePaths(workingDirectory)))
            }
            if (exitCode != null) appendLine(MyMessageBundle.message("error.details.exitCode", exitCode))
            if (!errorOutput.isNullOrBlank()) {
                appendLine(MyMessageBundle.message("error.details.errorHeader"))
                appendLine(sanitizePaths(errorOutput.trim()))
            }
        }.trim()
    }

    private fun buildCopyText(details: String): String {
        // Provide a stable, pasteable block.
        return details.trim()
    }

    private fun sanitizePaths(input: String): String {
        // Redact user names in common OS home paths (copy text is meant to be shareable).
        return input
            .replace(Regex("/Users/[^/]+/"), "/Users/<user>/")
            .replace(Regex("C:\\\\Users\\\\[^\\\\]+\\\\"), "C:\\Users\\<user>\\")
    }
}
