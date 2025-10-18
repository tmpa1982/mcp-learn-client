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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull


class MCPClient : AutoCloseable {
    val endpoint = "https://tmpa-ai-foundry.cognitiveservices.azure.com/"
    val azureAiClient: OpenAIClient = OpenAIClientBuilder()
        .credential(AzureKeyCredential(System.getenv("AZURE_OPENAI_KEY") ?: throw IllegalStateException("AZURE_OPENAI_KEY environment variable is not set")))
        .endpoint(endpoint)
        .buildClient()

    private val mcp: Client = Client(clientInfo = Implementation(name = "mcp-client-cli", version = "1.0.0"))
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

            fun unwrapJsonElement(v: Any?): Any? = when (v) {
                is JsonPrimitive -> when {
                    v.isString -> v.content
                    v.booleanOrNull != null -> v.boolean
                    v.longOrNull != null -> v.long
                    v.doubleOrNull != null -> v.double
                    else -> v.content
                }
                is JsonObject -> v.mapValues { unwrapJsonElement(it.value) }
                is JsonArray -> v.map { unwrapJsonElement(it) }
                else -> v
            }

            fun normalizeSchemaProperties(props: Map<String, Any?>): Map<String, Any?> {
                return props.mapValues { (_, v) ->
                    when (val unwrapped = unwrapJsonElement(v)) {
                        is Map<*, *> -> {
                            val typeVal = unwrapped["type"]
                            val descVal = unwrapped["description"]

                            // If "type" is itself a map from kotlinx-json, unwrap it
                            val typeName = when (typeVal) {
                                is Map<*, *> -> typeVal["content"] ?: typeVal["type"] ?: typeVal
                                else -> typeVal
                            }

                            mapOf(
                                "type" to typeName,
                                "description" to when (descVal) {
                                    is Map<*, *> -> descVal["content"] ?: descVal
                                    else -> descVal
                                }
                            ).filterValues { it != null }
                        }
                        else -> unwrapped
                    }
                }
            }

            openaiTools = toolsResult.tools.map { tool ->
                val func = FunctionDefinition(tool.name)
                func.setDescription(tool.description)
                val params = mapOf(
                    "type" to tool.inputSchema.type,
                    "properties" to normalizeSchemaProperties(tool.inputSchema.properties),
                    "required" to tool.inputSchema.required
                )
                func.setParameters(BinaryData.fromObject(params))
            }
            println("Connected to server with tools: ${openaiTools.joinToString(", ") { it.name }}")
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
            if (choice.message.role != ChatRole.ASSISTANT) {
                println("Received non-assistant role: ${choice.message.role}")
            } else {
                val message = choice.message
                if (message.content != null) {
                    finalText.add(message.content)
                }

                if (message.functionCall != null) {
                    val toolName = choice.message.functionCall.name
                    val toolArgs = choice.message.functionCall?.arguments
                        ?.let {
                            val mapper = ObjectMapper()
                            mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {})
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
}
