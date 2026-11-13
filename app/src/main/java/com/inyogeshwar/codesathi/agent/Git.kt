package com.inyogeshwar.codesathi.agent

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.MergeResult.MergeStatus
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

/**
 * Phase 2, Step 8 — Git, via JGit (pure Java, no git binary needed).
 *
 * One [GitRepo] wraps one working directory. Every method here is a
 * plain blocking call — the ViewModel runs them on Dispatchers.IO and
 * shows the result / error on screen. Methods throw on failure
 * (not a repo, nothing staged, auth failed, merge conflict, …) instead
 * of returning a half-result; the message is shown as-is.
 *
 * HTTPS only. SSH remotes are not supported: the ssh.apache transport
 * is a separate JGit artifact and is deliberately not added.
 *
 * The credential is never put in a URL: push / pull / clone pass it
 * through [UsernamePasswordCredentialsProvider], and [redactRemoteUrl]
 * strips any userinfo out of a remote URL before it is shown.
 *
 * The commit author is set explicitly (username from the saved Git
 * credential, else "CodeSathi"), so nothing depends on a ~/.gitconfig
 * — on Android there may not be one.
 */

/** Username + token/password for HTTPS remotes (from KeyStore only). */
data class GitCredentials(val username: String, val token: String) {
    fun toProvider() = UsernamePasswordCredentialsProvider(username, token)
}

/** Status lists, split the way the screen shows them. Paths are
 *  repo-relative, with forward slashes. */
data class GitStatusInfo(
    val stagedAdded: List<String>,
    val stagedChanged: List<String>,
    val stagedRemoved: List<String>,
    val unstagedModified: List<String>,
    val unstagedDeleted: List<String>,
    val untracked: List<String>,
    val conflicting: List<String>
) {
    /** Everything already in the index. */
    val staged: List<String> get() = stagedAdded + stagedChanged + stagedRemoved

    /** Tracked files changed in the working tree but not staged. */
    val unstaged: List<String> get() = unstagedModified + unstagedDeleted

    val isClean: Boolean
        get() = staged.isEmpty() && unstaged.isEmpty() &&
            untracked.isEmpty() && conflicting.isEmpty()
}

/** Everything the Git screen shows for one repo. */
data class GitRepoInfo(
    val currentBranch: String?,
    val branches: List<String>,
    /** The origin remote URL, userinfo stripped — safe to show. */
    val remoteUrl: String?,
    val status: GitStatusInfo
)

class GitRepo(private val dir: File) {

    /** True when this folder looks like a Git working tree. */
    fun isRepo(): Boolean = File(dir, ".git").exists()

    /** Branch / branches / remote / status, or null when not a repo. */
    fun load(): GitRepoInfo? {
        if (!isRepo()) return null
        return withGit { git ->
            val status = readStatus(git)
            val branches = git.branchList().call()
                .map { it.name.removePrefix("refs/heads/") }.sorted()
            val remotes = git.remoteList().call()
            val remote = remotes.firstOrNull { it.name == "origin" }
                ?: remotes.firstOrNull()
            val remoteUrl = remote?.getURIs()?.firstOrNull()?.toString()
                ?.let { redactRemoteUrl(it) }
            GitRepoInfo(
                currentBranch = git.repository.branch,
                branches = branches,
                remoteUrl = remoteUrl,
                status = status
            )
        }
    }

    /** Stage everything: new + changed files, and deletions too (the
     *  `add .` call is followed by an explicit rm --cached for each
     *  file deleted from the working tree, so deletions are staged even
     *  if the add call alone would skip them). */
    fun stageAll() {
        requireRepo()
        withGit { git ->
            val missing = readStatus(git).unstagedDeleted
            git.add().addFilepattern(".").call()
            for (path in missing) {
                git.rm().addFilepattern(path).setCached(true).call()
            }
        }
    }

    /** Stage one file. A file deleted from the working tree is staged
     *  with rm --cached instead — `add` cannot stage a missing file. */
    fun stageFile(path: String, wasDeleted: Boolean) {
        requireRepo()
        withGit { git ->
            if (wasDeleted) {
                git.rm().addFilepattern(path).setCached(true).call()
            } else {
                git.add().addFilepattern(path).call()
            }
        }
    }

    /** Take one file out of the index (reset that path to HEAD). */
    fun unstageFile(path: String) {
        requireRepo()
        withGit { git ->
            git.reset().addPath(path).call()
        }
    }

    /** Commit what is staged. Returns the short commit id.
     *  Throws when nothing is staged (JGit refuses an empty commit). */
    fun commit(message: String, authorName: String, authorEmail: String): String {
        requireRepo()
        return withGit { git ->
            val made = git.commit()
                .setMessage(message)
                .setAuthor(authorName, authorEmail)
                .setCommitter(authorName, authorEmail)
                .call()
            made.abbreviate(7).name()
        }
    }

    /** Push the current branch. Without a credential this only works
     *  for remotes that allow anonymous push (almost none) — for a
     *  private repo save the credential on the Git screen first. */
    fun push(credentials: GitCredentials?): String {
        requireRepo()
        return withGit { git ->
            val results = git.push().apply {
                credentials?.let { setCredentialsProvider(it.toProvider()) }
            }.call()
            val problems = results.flatMap { it.remoteUpdates }
                .filter {
                    it.status != RemoteRefUpdate.Status.OK &&
                        it.status != RemoteRefUpdate.Status.UP_TO_DATE
                }
            if (problems.isEmpty()) {
                "Pushed."
            } else {
                "Push finished with problems: " +
                    problems.joinToString { "${it.remoteName}: ${it.status}" }
            }
        }
    }

    /** Pull (fetch + merge) the current branch. Throws on a merge
     *  conflict, naming the conflicted files — resolve them, then commit. */
    fun pull(credentials: GitCredentials?): String {
        requireRepo()
        return withGit { git ->
            val result = git.pull().apply {
                credentials?.let { setCredentialsProvider(it.toProvider()) }
            }.call()
            val merge = result.mergeResult
            val mergeStatus = merge?.mergeStatus
            if (mergeStatus == MergeStatus.CONFLICTING) {
                val files = merge.conflicts?.keys?.joinToString(", ").orEmpty()
                throw IllegalStateException(
                    "Pull finished with conflicts in: $files. " +
                        "Resolve them, then commit."
                )
            }
            "Pulled — ${mergeStatus ?: "done"}."
        }
    }

    /** Switch branch. Throws when the working tree has changes that
     *  would be overwritten — commit or stash them first. */
    fun checkout(branch: String) {
        requireRepo()
        withGit { git ->
            git.checkout().setName(branch).call()
        }
    }

    private fun requireRepo() {
        if (!isRepo()) throw IllegalStateException("Not a Git repository: ${dir.absolutePath}")
    }

    private fun readStatus(git: Git): GitStatusInfo {
        val s = git.status().call()
        return GitStatusInfo(
            stagedAdded = s.added.sorted(),
            stagedChanged = s.changed.sorted(),
            stagedRemoved = s.removed.sorted(),
            unstagedModified = s.modified.sorted(),
            unstagedDeleted = s.missing.sorted(),
            untracked = s.untracked.sorted(),
            conflicting = s.conflicting.sorted()
        )
    }

    /** Run [block] with an opened repo, always closing it. (Plain
     *  try/finally instead of `use`: Git is AutoCloseable, not Closeable.) */
    private fun <T> withGit(block: (Git) -> T): T {
        val git = Git.open(dir)
        try {
            return block(git)
        } finally {
            git.close()
        }
    }

    companion object {
        /**
         * Clone [url] into a subfolder of [parentDir], named after the
         * repo (last URL segment, without ".git"). Returns the new folder.
         * Throws when a folder with that name already exists — delete or
         * rename it first — and when the clone fails (bad URL, no network,
         * auth failed for a private repo without a saved credential).
         */
        fun clone(url: String, parentDir: File, credentials: GitCredentials?): File {
            val rawName = url.trim().removeSuffix("/")
                .substringAfterLast("/").removeSuffix(".git")
            val safeName = rawName
                .filter { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
                .ifBlank { "repo" }
            val target = File(parentDir, safeName)
            require(!target.exists()) {
                "A folder named \"$safeName\" already exists here — " +
                    "delete or rename it first."
            }
            Git.cloneRepository()
                .setURI(url.trim())
                .setDirectory(target)
                .apply { credentials?.let { setCredentialsProvider(it.toProvider()) } }
                .call()
                .close()
            return target
        }
    }
}

/** Drop any userinfo (username:token@) from a remote URL before showing it. */
fun redactRemoteUrl(url: String): String {
    val schemeEnd = url.indexOf("://")
    if (schemeEnd < 0) return url
    val afterScheme = schemeEnd + 3
    val at = url.indexOf('@', afterScheme)
    val slash = url.indexOf('/', afterScheme)
    if (at < 0 || (slash >= 0 && at > slash)) return url
    return url.substring(0, afterScheme) + url.substring(at + 1)
}
