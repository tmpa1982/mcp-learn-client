import kotlinx.coroutines.runBlocking

fun main(args: Array<String>) = runBlocking {
    val client = MCPClient()

    client.use {
        if (args.isNotEmpty()) {
            val serverPath = args.first()
            client.connectToServer(serverPath)
        }
        client.chatLoop()
    }
}
