package org.jetbrains.mcpserverplugin

import com.intellij.openapi.project.Project
import kotlinx.serialization.KSerializer
import org.jetbrains.ide.mcp.Response
import kotlin.reflect.KClass

interface McpTool<Args : Any> {
    val name: String
    val description: String
    fun handle(args: Args): Response
}

abstract class AbstractMcpTool<Args : Any>(val serializer: KSerializer<Args>) : McpTool<Args> {
    val argKlass: KClass<Args> by lazy {
        val supertype = this::class.supertypes.find {
            it.classifier == AbstractMcpTool::class
        } ?: error("Cannot find McpTool supertype")

        val typeArgument = supertype.arguments.first().type
            ?: error("Cannot find type argument for McpTool")

        @Suppress("UNCHECKED_CAST")
        typeArgument.classifier as KClass<Args>
    }
}