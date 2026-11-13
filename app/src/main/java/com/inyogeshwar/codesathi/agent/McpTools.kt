package com.inyogeshwar.codesathi.agent

import com.inyogeshwar.codesathi.data.McpServer

/**
 * Namespaced MCP tool names: "mcp:<serverId>:<toolName>".
 * The namespace keeps MCP tools from ever colliding with built-in tools
 * (read / glob / grep / write / edit) or with another server's tools.
 */
fun mcpToolName(serverId: String, toolName: String) = "mcp:$serverId:$toolName"

/**
 * One MCP tool, as a normal [Tool] — so [AgentRunner] dispatches it and
 * gates it with the same allow / ask / deny rules as the built-ins, with
 * no special-casing in the runner. A denied MCP tool is refused exactly
 * like a denied built-in one.
 *
 * MCP tools never touch project files, so Plan mode's read-only flag does
 * not apply to them — but Plan mode never calls any tool anyway.
 * The auth token is read fresh on every call (never stored on the tool),
 * through the [token] lambda that reads [com.inyogeshwar.codesathi.data.KeyStore].
 */
class McpTool(
    private val server: McpServer,
    private val info: McpToolInfo,
    private val token: () -> String?
) : Tool {
    override val name = mcpToolName(server.id, info.name)
    override val description =
        "MCP tool '${info.name}' from server '${server.name}'" +
            if (info.description.isNotBlank()) ": ${info.description}" else "."

    override suspend fun run(args: Map<String, String>, ctx: ToolContext): ToolResult {
        return try {
            val client = McpClient(server.url, token())
            ToolResult.Ok(client.callTool(info.name, args))
        } catch (e: McpException) {
            ToolResult.Failed(e.message ?: "MCP tool failed.")
        } catch (e: Exception) {
            ToolResult.Failed("MCP tool failed: ${e.message}")
        }
    }
}

/** Tools for one server, from its last fetched tools/list result. */
fun mcpToolsFor(
    server: McpServer,
    tools: List<McpToolInfo>,
    token: () -> String?
): List<Tool> = tools.map { McpTool(server, it, token) }
