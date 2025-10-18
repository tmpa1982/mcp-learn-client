import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolUnion
import com.azure.ai.openai.OpenAIClient
import com.azure.ai.openai.OpenAIClientBuilder
import com.azure.ai.openai.models.*
import com.azure.core.credential.AzureKeyCredential
import com.azure.core.util.BinaryData
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject


class MCPClient : AutoCloseable {
    val endpoint = "https://tmpa-ai-foundry.cognitiveservices.azure.com/"
    val azureAiClient: OpenAIClient = OpenAIClientBuilder()
        .credential(AzureKeyCredential(System.getenv("AZURE_OPENAI_KEY") ?: throw IllegalStateException("AZURE_OPENAI_KEY environment variable is not set")))
        .endpoint(endpoint)
        .buildClient()

    private val mcp: Client = Client(clientInfo = Implementation(name = "mcp-client-cli", version = "1.0.0"))
    private lateinit var tools: List<ToolUnion>
    private lateinit var openaiTools: List<FunctionDefinition>

    override fun close() {
        runBlocking {
            mcp.close()
        }
    }

    suspend fun connectToServer(serverScriptPath: String) {
        try {
            val command = buildList {
                when (serverScriptPath.substringAfterLast(".")) {
                    "js" -> add("node")
                    "py" -> add(if (System.getProperty("os.name").lowercase().contains("win")) "python" else "python3")
                    "jar" -> addAll(listOf("java", "-jar"))
                    else -> throw IllegalArgumentException("Server script must be a .js, .py or .jar file")
                }
                add(serverScriptPath)
            }

            val process = ProcessBuilder(command).start()
            val transport = StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered()
            )

            mcp.connect(transport)

            val toolsResult = mcp.listTools()
            tools = toolsResult.tools.map { tool ->
                ToolUnion.ofTool(
                    Tool.builder()
                        .name(tool.name)
                        .description(tool.description ?: "")
                        .inputSchema(
                            Tool.InputSchema.builder()
                                .type(JsonValue.from(tool.inputSchema.type))
                                .properties(tool.inputSchema.properties.toJsonValue())
                                .putAdditionalProperty("required", JsonValue.from(tool.inputSchema.required))
                                .build()
                        )
                        .build()
                )
            }

            openaiTools = toolsResult.tools.map { tool ->
                val func = FunctionDefinition(tool.name)
                func.setDescription(tool.description)
                val params = mapOf(
                    "type" to tool.inputSchema.type,
                    "properties" to tool.inputSchema.properties,
                    "required" to tool.inputSchema.required
                )
                func.setParameters(BinaryData.fromObject(params))
            }
            println("Connected to server with tools: ${tools.joinToString(", ") { it.tool().get().name() }}")
        } catch (e: Exception) {
            println("Failed to connect to MCP server: $e")
            throw e
        }
    }

    suspend fun processQuery(query: String): String {
        val messages = mutableListOf<ChatRequestMessage>(
            ChatRequestUserMessage(query)
        )

        val deploymentName = "gpt-5-mini"
        val options = ChatCompletionsOptions(messages)
        options.setMaxCompletionTokens(16384)
        options.setFunctions(openaiTools)

        val completions = azureAiClient.getChatCompletions(deploymentName, options)
        val finalText = mutableListOf<String>()
        completions.choices.forEach { choice ->
            when (choice.message.role) {
                ChatRole.USER -> {
                    finalText.add(choice.message.content)
                }
                ChatRole.FUNCTION -> {
                    val toolName = choice.message.functionCall.name
                    val toolArgs = choice.message.functionCall?.arguments
                        ?.let {
                            val mapper = ObjectMapper()
                            mapper.readValue(it, object : TypeReference<Map<String, JsonValue>>() {})
                        } ?: emptyMap()

                    val result = mcp.callTool(
                        name = toolName ?: "",
                        arguments = toolArgs
                    )
                    finalText.add("[Calling tool $toolName with args $toolArgs]")

                    messages.add(
                        ChatRequestUserMessage("""
                            "type": "tool_result",
                            "tool_name": $toolName,
                            "result": ${result?.content?.joinToString("\n") { (it as TextContent).text ?: "" }}
                        """.trimIndent())
                    )

                    val toolOptions = ChatCompletionsOptions(messages)
                    toolOptions.setMaxCompletionTokens(16384)
                    val response = azureAiClient.getChatCompletions(deploymentName, toolOptions)
                    finalText.add(response.choices.single().message.content)
                }
            }
        }

        return finalText.joinToString("\n", prefix = "", postfix = "")
    }

    suspend fun chatLoop() {
        println("\nMCP Client Started!")
        println("Type your queries or 'quit' to exit.")

        while (true) {
            print("\nQuery: ")
            val message = readlnOrNull() ?: break
            if (message.lowercase() == "quit") break
            val response = processQuery(message)
            println("\n$response")
        }
    }

    private fun JsonObject.toJsonValue(): JsonValue {
        val mapper = ObjectMapper()
        val node = mapper.readTree(this.toString())
        return JsonValue.fromJsonNode(node)
    }
}
