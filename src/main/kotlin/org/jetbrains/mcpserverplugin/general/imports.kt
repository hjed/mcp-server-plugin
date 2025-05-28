package org.jetbrains.mcpserverplugin.general

import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.fileEditor.FileEditorManager.getInstance
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import org.jetbrains.ide.RestService.Companion.getLastFocusedOrOpenedProject
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.Response
import org.jetbrains.ide.mcp.getProject
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import java.util.concurrent.CountDownLatch

class OptimizeImportsCurrentFileTool : AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name: String = "optimize_imports_current_file"
    override val description: String = """
        Optimizes imports in the currently opened file in the JetBrains IDE editor.
        This tool removes unused imports and organizes the remaining imports according to the project's import settings.
        This tool doesn't require any parameters as it operates on the file currently open in the editor.

        Returns one of two possible responses:
            - "ok" if the imports were successfully optimized
            - "file doesn't exist or can't be opened" if there is no file currently selected in the editor
    """.trimIndent()

    override fun handle(args: NoArgs): Response {
        val project = getLastFocusedOrOpenedProject() ?: return Response(error = "Project not found")
        val latch = CountDownLatch(1)

        val psiFile = runReadAction {
            return@runReadAction getInstance(project).selectedTextEditor?.document?.run {
                PsiDocumentManager.getInstance(project).getPsiFile(this)
            }
        }

        if (psiFile == null) {return Response(error = "file doesn't exist or can't be opened")}

        val importsProcessor: OptimizeImportsProcessor = OptimizeImportsProcessor(project, psiFile)
        importsProcessor.setPostRunnable(Runnable {
            latch.countDown()
        })
        ApplicationManager.getApplication().invokeLater(Runnable { importsProcessor.run() })
        latch.await()
        return Response("ok")
    }
}

class OptimizeImportsFileTool : AbstractMcpTool<PathInProject>(PathInProject.serializer()) {
    override val name: String = "optimize_imports_file"
    override val description: String = """
        Optimizes imports in a specified file in the JetBrains IDE.
        This tool removes unused imports and organizes the remaining imports according to the project's import settings.
        Use this tool to clean up imports in a file identified by its path.
        Requires two parameters:
        - pathInProject: The file location relative to the project root
        - projectName: The name of the project containing the file. Use list_projects tool to get available project names.

        Returns one of these responses:
        - "ok" if the imports were successfully optimized
        - error "project dir not found" if project directory cannot be determined
        - error "file doesn't exist or can't be opened" if the file doesn't exist or cannot be accessed
    """.trimIndent()

    override fun handle(args: PathInProject): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val latch = CountDownLatch(1)

        val projectDir = project.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "project dir not found")

        val file = runReadAction {
            LocalFileSystem.getInstance()
                .refreshAndFindFileByNioFile(projectDir.resolveRel(args.pathInProject))
        }

        if (file == null) {return Response(error = "file doesn't exist or can't be opened")}

        val psiFile = runReadAction {
            return@runReadAction PsiManager.getInstance(project).findFile(file)
        }

        if (psiFile == null) {return Response(error = "file doesn't exist or can't be opened")}

        val importsProcessor: OptimizeImportsProcessor = OptimizeImportsProcessor(project, psiFile)
        importsProcessor.setPostRunnable(Runnable {
            latch.countDown()
        })
        ApplicationManager.getApplication().invokeLater(Runnable { importsProcessor.run() })
        latch.await()
        return Response("ok")
    }
}
