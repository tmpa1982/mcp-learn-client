import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) throw IllegalArgumentException("Usage: java -jar <your_path>/build/libs/kotlin-mcp-client-0.1.0-all.jar <path_to_server_script>")

    val client = MCPClient()
    val serverPath = args.first()

    client.use {
        client.connectToServer(serverPath)
        client.chatLoop()
    }
}
