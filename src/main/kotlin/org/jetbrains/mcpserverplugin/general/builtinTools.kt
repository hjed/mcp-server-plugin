package org.jetbrains.mcpserverplugin.general

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunManager
import com.intellij.execution.application.ApplicationConfiguration
import com.intellij.execution.application.ApplicationConfigurationType
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.execution.configurations.ConfigurationType
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.find.FindManager
import com.intellij.find.impl.FindInProjectUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ex.ActionManagerEx
import com.intellij.openapi.actionSystem.impl.PresentationFactory
import com.intellij.openapi.actionSystem.impl.Utils
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.invokeAndWaitIfNeeded
import com.intellij.openapi.progress.impl.CoreProgressManager
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.roots.OrderEnumerator
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.toNioPathOrNull
import com.intellij.usageView.UsageInfo
import com.intellij.usages.FindUsagesProcessPresentation
import com.intellij.usages.UsageViewPresentation
import com.intellij.util.Processor
import kotlinx.serialization.Serializable
import org.jetbrains.ide.mcp.NoArgs
import org.jetbrains.ide.mcp.ProjectOnly
import org.jetbrains.ide.mcp.Response
import org.jetbrains.mcpserverplugin.AbstractMcpTool
import org.jetbrains.ide.mcp.ProjectAware
import org.jetbrains.ide.mcp.getProject
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.pathString

fun Path.resolveRel(pathInProject: String): Path {
    return when (pathInProject) {
        "/" -> this
        else -> resolve(pathInProject.removePrefix("/"))
    }
}

fun Path.relativizeByProjectDir(projDir: Path?): String =
    projDir?.relativize(this)?.pathString ?: this.absolutePathString()

@Serializable
data class SearchInFilesArgs(val searchText: String, override val projectName: String) : ProjectAware

class SearchInFilesContentTool : AbstractMcpTool<SearchInFilesArgs>(SearchInFilesArgs.serializer()) {
    override val name: String = "search_in_files_content"
    override val description: String = """
        Searches for a text substring within all files in the project using IntelliJ's search engine.
        Use this tool to find files containing specific text content.
        Requires two parameters:
        - searchText: The text to search for in project files
        - projectName: The name of the project to search in. Use list_projects tool to get available project names.
        Returns a JSON array of objects containing file information:
        - path: Path relative to project root
        Returns an empty array ([]) if no matches are found.
        Note: Only searches through text files within the project directory.
    """

    override fun handle(args: SearchInFilesArgs): Response {
        val project = args.getProject()
        val projectDir = project?.guessProjectDir()?.toNioPathOrNull()
            ?: return Response(error = "Project directory not found")

        val searchSubstring = args.searchText
        if (searchSubstring.isNullOrBlank()) {
            return Response(error = "contentSubstring parameter is required and cannot be blank")
        }

        val findModel = FindManager.getInstance(project).findInProjectModel.clone()
        findModel.stringToFind = searchSubstring
        findModel.isCaseSensitive = false
        findModel.isWholeWordsOnly = false
        findModel.isRegularExpressions = false
        findModel.setProjectScope(true)

        val results = mutableSetOf<String>()

        val processor = Processor<UsageInfo> { usageInfo ->
            val virtualFile = usageInfo.virtualFile ?: return@Processor true
            try {
                val relativePath = projectDir.relativize(Path(virtualFile.path)).toString()
                results.add("""{"path": "$relativePath", "name": "${virtualFile.name}"}""")
            } catch (e: IllegalArgumentException) {
            }
            true
        }
        FindInProjectUtil.findUsages(
            findModel,
            project,
            processor,
            FindUsagesProcessPresentation(UsageViewPresentation())
        )

        val jsonResult = results.joinToString(",\n", prefix = "[", postfix = "]")
        return Response(jsonResult)
    }
}

class GetRunConfigurationsTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String
        get() = "get_run_configurations"
    override val description: String = """
        Returns a list of run configurations for the current project.
        Use this tool to query the list of available run configurations in current project.
        Requires one parameter:
        - projectName: The name of the project to get configurations from. Use list_projects tool to get available project names.
        Then you shall to call "run_configuration" tool if you find anything relevant.
        Returns JSON list of run configuration names. Empty list if no run configurations found.
    """

    override fun handle(args: ProjectOnly): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val runManager = RunManager.getInstance(project)

        val configurations = runManager.allSettings.map { it.name }.joinToString(
            prefix = "[",
            postfix = "]",
            separator = ","
        ) { "\"$it\"" }

        return Response(configurations)
    }
}

@Serializable
data class RunConfigArgs(val configName: String, override val projectName: String) : ProjectAware

class RunConfigurationTool : AbstractMcpTool<RunConfigArgs>(RunConfigArgs.serializer()) {
    override val name: String = "run_configuration"
    override val description: String = """
        Run a specific run configuration in the current project and wait up to 120 seconds for it to finish.
        Use this tool to run a run configuration that you have found from the "get_run_configurations" tool.
        Requires two parameters:
        - configName: The name of the run configuration to execute
        - projectName: The name of the project containing the run configuration. Use list_projects tool to get available project names.
        Returns the output (stdout/stderr) of the execution, prefixed with 'ok\n' on success (exit code 0).
        Returns '<error message>' if the configuration is not found, times out, fails to start, or finishes with a non-zero exit code.
    """

    // Timeout in seconds
    private val executionTimeoutSeconds = 120L

    override fun handle(args: RunConfigArgs): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val runManager = RunManager.getInstance(project)
        val settings = runManager.allSettings.find { it.name == args.configName }
            ?: return Response(error = "Run configuration with name '${args.configName}' not found.")

        val executor =
            DefaultRunExecutor.getRunExecutorInstance() ?: return Response(error = "Default 'Run' executor not found.")

        val future = CompletableFuture<Pair<Int, String>>() // Pair<ExitCode, Output>
        val outputBuilder = StringBuilder()

        ApplicationManager.getApplication().invokeLater {
            try {
                val runner: ProgramRunner<*>? = ProgramRunner.getRunner(executor.id, settings.configuration)
                if (runner == null) {
                    future.completeExceptionally(
                        ExecutionException("No suitable runner found for configuration '${settings.name}' and executor '${executor.id}'")
                    )
                    return@invokeLater
                }

                val environment = ExecutionEnvironmentBuilder.create(project, executor, settings.configuration).build()

                val callback = object : ProgramRunner.Callback {
                    override fun processStarted(descriptor: RunContentDescriptor?) {
                        if (descriptor == null) {
                            if (!future.isDone) {
                                future.completeExceptionally(
                                    ExecutionException("Run configuration doesn't support catching output")
                                )
                            }
                            return
                        }

                        val processHandler = descriptor.processHandler
                        if (processHandler == null) {
                            if (!future.isDone) {
                                future.completeExceptionally(
                                    IllegalStateException("Process handler is null even though RunContentDescriptor exists.")
                                )
                            }
                            return
                        }

                        processHandler.addProcessListener(object : ProcessAdapter() {
                            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                                synchronized(outputBuilder) {
                                    outputBuilder.append(event.text)
                                }
                            }

                            override fun processTerminated(event: ProcessEvent) {
                                val finalOutput = synchronized(outputBuilder) { outputBuilder.toString() }
                                future.complete(Pair(event.exitCode, finalOutput))
                            }

                            override fun processNotStarted() {
                                if (!future.isDone) {
                                    future.completeExceptionally(RuntimeException("Process explicitly reported as not started."))
                                }
                            }
                        })
                        processHandler.startNotify()
                    }
                }
                runner.execute(environment, callback)

            } catch (e: Throwable) {
                if (!future.isDone) {
                    future.completeExceptionally(
                        ExecutionException("Failed to prepare or start run configuration: ${e.message}", e)
                    )
                }
            }
        }

        try {
            val result = future.get(executionTimeoutSeconds, TimeUnit.SECONDS)
            val exitCode = result.first
            val output = result.second

            return if (exitCode == 0) {
                Response("ok\n$output")
            } else {
                Response(error = "Execution failed with exit code $exitCode.\nOutput:\n$output")
            }
        } catch (e: TimeoutException) {
            return Response(error = "Execution timed out after $executionTimeoutSeconds seconds.")
        } catch (e: ExecutionException) {
            val causeMessage = e.cause?.message ?: e.message
            return Response(error = "Failed to execute run configuration: $causeMessage")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Response(error = "Execution was interrupted.")
        } catch (e: Throwable) {
            return Response(error = "An unexpected error occurred during execution wait: ${e.message}")
        }
    }
}

class GetProjectModulesTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "get_project_modules"
    override val description: String = """
        Get list of all modules in the project with their dependencies.
        Requires parameter:
        - projectName: The name of the project to get modules from. Use list_projects tool to get available project names.
        Returns JSON list of module names.
    """

    override fun handle(args: ProjectOnly): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val modules = moduleManager.modules.map { it.name }
        return Response(modules.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

class GetProjectDependenciesTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "get_project_dependencies"
    override val description: String = """
        Get list of all dependencies defined in the project.
        Requires parameter:
        - projectName: The name of the project to get dependencies from. Use list_projects tool to get available project names.
        Returns JSON list of dependency names.
    """

    override fun handle(args: ProjectOnly): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val moduleManager = com.intellij.openapi.module.ModuleManager.getInstance(project)
        val dependencies = moduleManager.modules.flatMap { module ->
            OrderEnumerator.orderEntries(module).librariesOnly().classes().roots.map { root ->
                """{"name": "${root.name}", "type": "library"}"""
            }
        }.toHashSet()

        return Response(dependencies.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

class ListAvailableActionsTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "list_available_actions"
    override val description: String = """
        Lists all available actions in JetBrains IDE editor.
        Requires one parameter:
        - projectName: The name of the project context. Use list_projects tool to get available project names.
        Returns a JSON array of objects containing action information:
        - id: The action ID
        - text: The action presentation text
        Use this tool to discover available actions for execution with execute_action_by_id.
    """.trimIndent()

    override fun handle(args: ProjectOnly): Response {
        val actionManager = ActionManager.getInstance() as ActionManagerEx
        val dataContext = invokeAndWaitIfNeeded {
            DataManager.getInstance().getDataContext()
        }

        val actionIds = actionManager.getActionIdList("")
        val presentationFactory = PresentationFactory()
        val visibleActions = invokeAndWaitIfNeeded {
            Utils.expandActionGroup(
                DefaultActionGroup(
                    actionIds.mapNotNull { actionManager.getAction(it) }
                ), presentationFactory, dataContext, "", ActionUiKind.NONE)
        }
        val availableActions = visibleActions.mapNotNull {
            val presentation = presentationFactory.getPresentation(it)
            val actionId = actionManager.getId(it)
            if (presentation.isEnabledAndVisible && !presentation.text.isNullOrBlank()) {
                """{"id": "$actionId", "text": "${presentation.text.replace("\"", "\\\"")}"}"""
            } else null
        }
        return Response(availableActions.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

@Serializable
data class ExecuteActionArgs(val actionId: String)

class ExecuteActionByIdTool : AbstractMcpTool<ExecuteActionArgs>(ExecuteActionArgs.serializer()) {
    override val name: String = "execute_action_by_id"
    override val description: String = """
        Executes an action by its ID in JetBrains IDE editor.
        Requires an actionId parameter containing the ID of the action to execute.
        Returns one of two possible responses:
        - "ok" if the action was successfully executed
        - "action not found" if the action with the specified ID was not found
        Note: This tool doesn't wait for the action to complete.
    """.trimIndent()

    override fun handle(args: ExecuteActionArgs): Response {
        val actionManager = ActionManager.getInstance()
        val action = actionManager.getAction(args.actionId)

        if (action == null) {
            return Response(error = "action not found")
        }

        ApplicationManager.getApplication().invokeLater({
            val event = AnActionEvent.createFromAnAction(
                action,
                null,
                "",
                DataManager.getInstance().getDataContext()
            )
            action.actionPerformed(event)
        }, ModalityState.nonModal())

        return Response("ok")
    }
}

class GetProgressIndicatorsTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "get_progress_indicators"
    override val description: String = """
        Retrieves the status of all running progress indicators in JetBrains IDE editor.
        Requires one parameter:
        - projectName: The name of the project context. Use list_projects tool to get available project names.
        Returns a JSON array of objects containing progress information:
        - text: The progress text/description
        - fraction: The progress ratio (0.0 to 1.0)
        - indeterminate: Whether the progress is indeterminate
        Returns an empty array if no progress indicators are running.
    """.trimIndent()

    override fun handle(args: ProjectOnly): Response {
        val runningIndicators = CoreProgressManager.getCurrentIndicators()

        val progressInfos = runningIndicators.map { indicator ->
            val text = indicator.text ?: ""
            val fraction = if (indicator.isIndeterminate) -1.0 else indicator.fraction
            val indeterminate = indicator.isIndeterminate

            """{"text": "${text.replace("\"", "\\\"")}", "fraction": $fraction, "indeterminate": $indeterminate}"""
        }

        return Response(progressInfos.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

@Serializable
data class WaitArgs(val milliseconds: Long = 5000)

class WaitTool : AbstractMcpTool<WaitArgs>(WaitArgs.serializer()) {
    override val name: String = "wait"
    override val description: String = """
        Waits for a specified number of milliseconds (default: 5000ms = 5 seconds).
        Optionally accepts a milliseconds parameter to specify the wait duration.
        Returns "ok" after the wait completes.
        Use this tool when you need to pause before executing the next command.
    """.trimIndent()

    override fun handle(args: WaitArgs): Response {
        val waitTime = if (args.milliseconds <= 0) 5000 else args.milliseconds

        try {
            TimeUnit.MILLISECONDS.sleep(waitTime)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return Response(error = "Wait interrupted")
        }

        return Response("ok")
    }
}

class ListProjectsTool : org.jetbrains.mcpserverplugin.AbstractMcpTool<NoArgs>(NoArgs.serializer()) {
    override val name: String = "list_projects"
    override val description: String = """
        Returns a list of all available projects.
        Use this to get project names for specifying projectName parameter.
        No parameters required.
        Returns JSON array of project information objects.
    """

    override fun handle(args: NoArgs): Response {
        val projects = ProjectManager.getInstance().openProjects.map { openProject ->
            """{"name": "${openProject.name}", "baseDir": "${openProject.basePath ?: ""}"}"""
        }
        return Response(projects.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}

@Serializable
data class CreateRunConfigurationArgs(
    val configurationName: String,
    val configurationType: String,
    val configurationSettings: Map<String, String> = emptyMap(),
    override val projectName: String
) : ProjectAware

class CreateRunConfigurationTool : AbstractMcpTool<CreateRunConfigurationArgs>(CreateRunConfigurationArgs.serializer()) {
    override val name: String = "create_run_configuration"
    override val description: String = """
        Creates a new run configuration in the specified project.
        Requires four parameters:
        - configurationName: The name for the new run configuration
        - configurationType: The type of configuration to create (e.g., "Application", "Gradle", "Shell Script")
        - configurationSettings: A map of configuration-specific settings (optional)
        - projectName: The name of the project to create the configuration in. Use list_projects tool to get available project names.

        Common configuration types:
        - "Application": Java application run configuration
        - "Gradle": Gradle task run configuration
        - "Shell Script": Shell script run configuration

        Configuration settings depend on the type:
        - Application: mainClass, programParameters, workingDirectory, vmOptions
        - Gradle: tasks, arguments, workingDirectory
        - Shell Script: scriptPath, scriptOptions, workingDirectory

        Returns "ok" if the configuration was successfully created.
        Returns error message if the configuration type is not supported or creation fails.
    """

    override fun handle(args: CreateRunConfigurationArgs): Response {
        val project = args.getProject() ?: return Response(error = "Project not found")
        val runManager = RunManager.getInstance(project)

        try {
            // Find the configuration type
            val configurationType = findConfigurationType(args.configurationType)
                ?: return Response(error = "Configuration type '${args.configurationType}' not found or not supported")

            // Get the configuration factory
            val factory = configurationType.configurationFactories.firstOrNull()
                ?: return Response(error = "No factory found for configuration type '${args.configurationType}'")

            // Create the configuration
            val settings = runManager.createConfiguration(args.configurationName, factory)
            val configuration = settings.configuration

            // Apply configuration-specific settings
            val result = applyConfigurationSettings(configuration, args.configurationSettings, args.configurationType)
            if (result != null) {
                return Response(error = result)
            }

            // Add the configuration to the run manager
            runManager.addConfiguration(settings)

            return Response("ok")
        } catch (e: Exception) {
            return Response(error = "Failed to create run configuration: ${e.message}")
        }
    }

    private fun findConfigurationType(typeName: String): ConfigurationType? {
        return ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.find { configurationType ->
            configurationType.displayName.equals(typeName, ignoreCase = true) ||
            configurationType.id.equals(typeName, ignoreCase = true)
        }
    }

    private fun applyConfigurationSettings(
        configuration: RunConfiguration,
        settings: Map<String, String>,
        typeName: String
    ): String? {
        return try {
            when (typeName.lowercase()) {
                "application" -> applyApplicationSettings(configuration, settings)
                "gradle" -> applyGradleSettings(configuration, settings)
                "shell script" -> applyShellScriptSettings(configuration, settings)
                else -> null // No specific settings to apply for unknown types
            }
        } catch (e: Exception) {
            "Failed to apply settings: ${e.message}"
        }
    }

    private fun applyApplicationSettings(configuration: RunConfiguration, settings: Map<String, String>): String? {
        if (configuration !is ApplicationConfiguration) {
            return "Configuration is not an Application configuration"
        }

        settings["mainClass"]?.let { configuration.mainClassName = it }
        settings["programParameters"]?.let { configuration.programParameters = it }
        settings["workingDirectory"]?.let { configuration.workingDirectory = it }
        settings["vmOptions"]?.let { configuration.vmParameters = it }

        return null
    }

    private fun applyGradleSettings(configuration: RunConfiguration, settings: Map<String, String>): String? {
        // For Gradle configurations, we need to use reflection or find the appropriate class
        // This is a simplified implementation
        try {
            val tasksField = configuration.javaClass.getDeclaredField("mySettings")
            tasksField.isAccessible = true
            val gradleSettings = tasksField.get(configuration)

            settings["tasks"]?.let { tasks ->
                val taskNamesField = gradleSettings.javaClass.getDeclaredField("taskNames")
                taskNamesField.isAccessible = true
                taskNamesField.set(gradleSettings, tasks.split(" ").toMutableList())
            }

            settings["arguments"]?.let { args ->
                val scriptParametersField = gradleSettings.javaClass.getDeclaredField("scriptParameters")
                scriptParametersField.isAccessible = true
                scriptParametersField.set(gradleSettings, args)
            }

            settings["workingDirectory"]?.let { workDir ->
                val externalProjectPathField = gradleSettings.javaClass.getDeclaredField("externalProjectPath")
                externalProjectPathField.isAccessible = true
                externalProjectPathField.set(gradleSettings, workDir)
            }
        } catch (e: Exception) {
            return "Failed to configure Gradle settings: ${e.message}"
        }

        return null
    }

    private fun applyShellScriptSettings(configuration: RunConfiguration, settings: Map<String, String>): String? {
        // For Shell Script configurations, we need to use reflection or find the appropriate class
        // This is a simplified implementation
        try {
            settings["scriptPath"]?.let { scriptPath ->
                val scriptPathField = configuration.javaClass.getDeclaredField("scriptPath")
                scriptPathField.isAccessible = true
                scriptPathField.set(configuration, scriptPath)
            }

            settings["scriptOptions"]?.let { options ->
                val scriptOptionsField = configuration.javaClass.getDeclaredField("scriptOptions")
                scriptOptionsField.isAccessible = true
                scriptOptionsField.set(configuration, options)
            }

            settings["workingDirectory"]?.let { workDir ->
                val workingDirectoryField = configuration.javaClass.getDeclaredField("workingDirectory")
                workingDirectoryField.isAccessible = true
                workingDirectoryField.set(configuration, workDir)
            }
        } catch (e: Exception) {
            return "Failed to configure Shell Script settings: ${e.message}"
        }

        return null
    }
}

class ListConfigurationTypesTool : AbstractMcpTool<ProjectOnly>(ProjectOnly.serializer()) {
    override val name: String = "list_configuration_types"
    override val description: String = """
        Lists all available run configuration types in the IDE.
        Requires one parameter:
        - projectName: The name of the project context. Use list_projects tool to get available project names.
        Returns a JSON array of objects containing configuration type information:
        - id: The configuration type ID
        - displayName: The human-readable display name
        - description: Description of the configuration type
        Use this tool to discover available configuration types for creating run configurations.
    """

    override fun handle(args: ProjectOnly): Response {
        val configurationTypes = ConfigurationType.CONFIGURATION_TYPE_EP.extensionList.map { configurationType ->
            """{"id": "${configurationType.id}", "displayName": "${configurationType.displayName}", "description": "${configurationType.configurationTypeDescription ?: ""}"}"""
        }
        return Response(configurationTypes.joinToString(",\n", prefix = "[", postfix = "]"))
    }
}