package org.jetbrains.legacy.mcpserverplugin

import com.intellij.openapi.extensions.ExtensionPointName
import org.jetbrains.legacy.mcpserverplugin.general.CreateNewFileWithTextTool
import org.jetbrains.legacy.mcpserverplugin.general.ExecuteActionByIdTool
import org.jetbrains.legacy.mcpserverplugin.general.FindFilesByNameSubstring
import org.jetbrains.legacy.mcpserverplugin.general.GetAllOpenFilePathsTool
import org.jetbrains.legacy.mcpserverplugin.general.GetAllOpenFileTextsTool
import org.jetbrains.legacy.mcpserverplugin.general.GetCurrentFileErrorsTool
import org.jetbrains.legacy.mcpserverplugin.general.GetCurrentFilePathTool
import org.jetbrains.legacy.mcpserverplugin.general.GetCurrentFileTextTool
import org.jetbrains.legacy.mcpserverplugin.general.GetFileTextByPathTool
import org.jetbrains.legacy.mcpserverplugin.general.GetProblemsTools
import org.jetbrains.legacy.mcpserverplugin.general.GetProgressIndicatorsTool
import org.jetbrains.legacy.mcpserverplugin.general.GetProjectDependenciesTool
import org.jetbrains.legacy.mcpserverplugin.general.GetProjectModulesTool
import org.jetbrains.legacy.mcpserverplugin.general.GetRunConfigurationsTool
import org.jetbrains.legacy.mcpserverplugin.general.GetSelectedTextTool
import org.jetbrains.legacy.mcpserverplugin.general.ListAvailableActionsTool
import org.jetbrains.legacy.mcpserverplugin.general.ListDirectoryTreeInFolderTool
import org.jetbrains.legacy.mcpserverplugin.general.ListFilesInFolderTool
import org.jetbrains.legacy.mcpserverplugin.general.OpenFileInEditorTool
import org.jetbrains.legacy.mcpserverplugin.general.ReplaceCurrentFileTextTool
import org.jetbrains.legacy.mcpserverplugin.general.ReplaceSelectedTextTool
import org.jetbrains.legacy.mcpserverplugin.general.ReplaceSpecificTextTool
import org.jetbrains.legacy.mcpserverplugin.general.ReplaceTextByPathTool
import org.jetbrains.legacy.mcpserverplugin.general.RunConfigurationTool
import org.jetbrains.legacy.mcpserverplugin.general.SearchInFilesContentTool
import org.jetbrains.legacy.mcpserverplugin.general.WaitTool
import org.jetbrains.legacy.mcpserverplugin.git.GetVcsStatusTool
import org.jetbrains.legacy.mcpserverplugin.general.ReformatCurrentFileTool
import org.jetbrains.legacy.mcpserverplugin.general.ReformatFileTool
import org.jetbrains.legacy.mcpserverplugin.general.OptimizeImportsCurrentFileTool
import org.jetbrains.legacy.mcpserverplugin.general.OptimizeImportsFileTool
import org.jetbrains.legacy.mcpserverplugin.general.ListProjectsTool

class McpToolManager {
    companion object {
        private val EP_NAME = ExtensionPointName<AbstractMcpTool<*>>("com.intellij.legacyMcpServer.mcpTool")

        fun getAllTools(): List<AbstractMcpTool<*>> {
            return buildList {
                // Add built-in tools
                addAll(getBuiltInTools())
                // Add extension-provided tools
                addAll(EP_NAME.extensionList)
            }
        }

        private fun getBuiltInTools(): List<AbstractMcpTool<*>> = listOf(
            GetCurrentFileTextTool(),
            GetCurrentFilePathTool(),
            GetSelectedTextTool(),
            ReplaceSelectedTextTool(),
            ReplaceCurrentFileTextTool(),
            CreateNewFileWithTextTool(),
            FindFilesByNameSubstring(),
            GetFileTextByPathTool(),
            GetVcsStatusTool(),
            ToggleBreakpointTool(),
            GetBreakpointsTool(),
            ReplaceTextByPathTool(),
            ReplaceSpecificTextTool(),
            ListFilesInFolderTool(),
            ListDirectoryTreeInFolderTool(),
            SearchInFilesContentTool(),
            RunConfigurationTool(),
            GetRunConfigurationsTool(),
            GetProjectModulesTool(),
            GetProjectDependenciesTool(),
            GetAllOpenFileTextsTool(),
            GetAllOpenFilePathsTool(),
            OpenFileInEditorTool(),
            ListAvailableActionsTool(),
            ExecuteActionByIdTool(),
            GetProgressIndicatorsTool(),
            WaitTool(),
            GetCurrentFileErrorsTool(),
            ReformatCurrentFileTool(),
            ReformatFileTool(),
            OptimizeImportsCurrentFileTool(),
            OptimizeImportsFileTool(),
            GetProblemsTools(),
            ListProjectsTool(),
        )
    }
}