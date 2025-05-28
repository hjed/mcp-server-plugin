package org.jetbrains.mcpserverplugin.git

import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.toNioPathOrNull
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepositoryManager
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.ProjectAware
import org.jetbrains.ide.mcp.ProjectOnly
import org.jetbrains.ide.mcp.Response
import org.jetbrains.ide.mcp.getProject
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import kotlin.io.path.Path

@Serializable
data class CommitQuery(val text: String,override val projectName: String) : ProjectAware

class FindCommitByTextTool : AbstractMcpTool<CommitQuery>(CommitQuery.serializer()) {
    override val name: String = "find_commit_by_message"
    override val description: String = """
        Searches for a commit based on the provided text or keywords in the project history.
        Useful for finding specific change sets or code modifications by commit messages or diff content.
        Requires two parameters:
        - text: The search text to look for in commit messages
        - projectName: The name of the project to search in. Use list_projects tool to get available project names.
        Returns matched commit hashes as a JSON array.
    """

    override fun handle(args: CommitQuery): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val queryText = args.text
        val matchingCommits = mutableListOf<String>()

        try {
            val vcs = ProjectLevelVcsManager.getInstance(project).allVcsRoots
                .mapNotNull { it.path }

            if (vcs.isEmpty()) {
                return Response("Error: No VCS configured for this project")
            }

            // Iterate over each VCS root to search for commits
            vcs.forEach { vcsRoot ->
                val repository = GitRepositoryManager.getInstance(project).getRepositoryForRoot(vcsRoot)
                    ?: return@forEach

                val gitLog = GitHistoryUtils.history(project, repository.root)

                gitLog.forEach { commit ->
                    if (commit.fullMessage.contains(queryText, ignoreCase = true)) {
                        matchingCommits.add(commit.id.toString())
                    }
                }
            }

            // Check if any matches were found
            return if (matchingCommits.isNotEmpty()) {
                Response(matchingCommits.joinToString(prefix = "[", postfix = "]", separator = ",") { "\"$it\"" })
            } else {
                Response("No commits found matching the query: $queryText")
            }

        } catch (e: Exception) {
            // Handle any errors that occur during the search
            return Response("Error while searching commits: ${e.message}")
        }
    }
}

class GetVcsStatusTool : AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "get_project_vcs_status"
    override val description: String = """
        Retrieves the current version control status of files in the project.
        Use this tool to get information about modified, added, deleted, and moved files in your VCS (e.g., Git).
        Requires parameter:
        - projectName: The name of the project to check VCS status. Use list_projects tool to get available project names.
        Returns a JSON-formatted list of changed files, where each entry contains:
        - path: The file path relative to project root
        - type: The type of change (e.g., MODIFICATION, ADDITION, DELETION, MOVED)
        Returns an empty list ([]) if no changes are detected or VCS is not configured.
        Returns error "project dir not found" if project directory cannot be determined.
        Note: Works with any VCS supported by the IDE, but is most commonly used with Git
    """

    override fun handle(args: ProjectOnly): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val changeListManager = ChangeListManager.getInstance(project)
        val changes = changeListManager.allChanges
        val unversionedFiles = changeListManager.unversionedFilesPaths

        val result = mutableListOf<Map<String, String>>()

        // Process tracked changes
        changes.forEach { change ->
            val absolutePath = change.virtualFile?.path ?: change.afterRevision?.file?.path
            val changeType = change.type

            if (absolutePath != null) {
                try {
                    val relativePath = projectDir.relativize(Path(absolutePath)).toString()
                    result.add(mapOf("path" to relativePath, "type" to changeType.toString()))
                } catch (e: IllegalArgumentException) {
                    // Skip files outside project directory
                }
            }
        }

        // Process unversioned files
        unversionedFiles.forEach { file ->
            try {
                val relativePath = projectDir.relativize(Path(file.path)).toString()
                result.add(mapOf("path" to relativePath, "type" to "UNVERSIONED"))
            } catch (e: IllegalArgumentException) {
                // Skip files outside project directory
            }
        }

        return Response(result.joinToString(",\n", prefix = "[", postfix = "]") {
            """{"path": "${it["path"]}", "type": "${it["type"]}"}"""
        })
    }
}