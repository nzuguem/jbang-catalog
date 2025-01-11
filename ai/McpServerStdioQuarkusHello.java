///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21+
//FILES hello.txt
//DEPS io.quarkus:quarkus-bom:3.17.6@pom
//DEPS io.quarkiverse.mcp:quarkus-mcp-server-stdio:1.0.0.Alpha2

import io.quarkiverse.mcp.server.*;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;

public class McpServerStdioQuarkusHello {

    @Tool(name = "say_hello")
    ToolResponse sayHello(@ToolArg String name) {
        return ToolResponse.success(new TextContent("Hello %s !".formatted(name)));
    }

    @Prompt(name = "say_hello_prompt")
    PromptMessage sayHelloPrompt(@PromptArg String name) {
        return PromptMessage.withUserRole(new TextContent("say hello to " + name));

    }

    @Resource(name = "hello", uri = "file:///hello.txt")
    BlobResourceContents hello(String uri) throws IOException {
        return BlobResourceContents.create(uri, Files.readAllBytes(Paths.get(URI.create(uri))));
    }

}