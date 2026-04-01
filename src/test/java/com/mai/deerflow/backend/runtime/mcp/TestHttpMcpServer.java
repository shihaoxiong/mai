package com.mai.deerflow.backend.runtime.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import reactor.netty.http.server.HttpServerRequest;
import reactor.netty.http.server.HttpServerResponse;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 Reactor Netty 的测试用 HTTP MCP server。
 *
 * 目标：
 * 1. 为 runtime MCP provider 提供真实的 SSE / streamable HTTP transport 验证环境
 * 2. 只实现当前测试所需的 initialize / tools.list / tools.call 最小协议面
 * 3. 避免给主代码引入额外的 servlet 容器依赖
 */
public final class TestHttpMcpServer implements AutoCloseable {

    private static final String TOOL_NAME = "mcp_reverse";
    private static final String SERVER_NAME = "demo-http-mcp";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentMap<String, Sinks.Many<String>> sessions = new ConcurrentHashMap<>();
    private final DisposableServer server;

    private TestHttpMcpServer() {
        this.server = HttpServer.create()
                .host("127.0.0.1")
                .port(0)
                .route(routes -> routes
                        .get("/sse", this::handleSseConnect)
                        .post("/message", this::handleSseMessage)
                        .get("/mcp", this::handleStreamableConnect)
                        .post("/mcp", this::handleStreamableMessage))
                .bindNow();
    }

    public static TestHttpMcpServer start() {
        return new TestHttpMcpServer();
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.port();
    }

    @Override
    public void close() {
        sessions.values().forEach(sink -> sink.tryEmitComplete());
        server.disposeNow();
    }

    private Publisher<Void> handleSseConnect(HttpServerRequest request, HttpServerResponse response) {
        String sessionId = UUID.randomUUID().toString();
        Sinks.Many<String> sink = Sinks.many().multicast().onBackpressureBuffer();
        sessions.put(sessionId, sink);

        Flux<String> stream = Flux.just(sseEvent("endpoint", baseUrl() + "/message?sessionId=" + sessionId))
                .concatWith(sink.asFlux().map(payload -> sseEvent("message", payload)));

        return response.status(200)
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .sendString(stream);
    }

    private Publisher<Void> handleSseMessage(HttpServerRequest request, HttpServerResponse response) {
        String sessionId = queryParam(request.uri(), "sessionId");
        if (sessionId == null || sessionId.isBlank()) {
            return response.status(400).send();
        }

        Sinks.Many<String> sink = sessions.get(sessionId);
        if (sink == null) {
            return response.status(404).send();
        }

        return request.receive()
                .aggregate()
                .asString()
                .flatMap(body -> {
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        JsonNode message = handleJsonRpcRequest(root, sessionId);
                        if (message != null) {
                            sink.tryEmitNext(objectMapper.writeValueAsString(message));
                        }
                        return response.status(202).send();
                    }
                    catch (IOException exception) {
                        return response.status(500).sendString(Mono.just(exception.getMessage())).then();
                    }
                });
    }

    private Publisher<Void> handleStreamableConnect(HttpServerRequest request, HttpServerResponse response) {
        String sessionId = request.requestHeaders().get("Mcp-Session-Id");
        String accept = request.requestHeaders().get("Accept");
        if (sessionId == null || sessionId.isBlank()) {
            return response.status(400).send();
        }
        if (accept == null || !accept.contains("text/event-stream")) {
            return response.status(400).send();
        }

        Sinks.Many<String> sink = sessions.computeIfAbsent(sessionId, ignored -> Sinks.many().multicast().onBackpressureBuffer());
        Flux<String> stream = Flux.just(": connected\n\n")
                .concatWith(sink.asFlux().map(payload -> sseEvent("message", payload)));

        return response.status(200)
                .header("Content-Type", "text/event-stream")
                .header("Cache-Control", "no-cache")
                .sendString(stream);
    }

    private Publisher<Void> handleStreamableMessage(HttpServerRequest request, HttpServerResponse response) {
        return request.receive()
                .aggregate()
                .asString()
                .flatMap(body -> {
                    try {
                        JsonNode root = objectMapper.readTree(body);
                        String currentSessionId = request.requestHeaders().get("Mcp-Session-Id");
                        String sessionId = currentSessionId == null || currentSessionId.isBlank()
                                ? UUID.randomUUID().toString()
                                : currentSessionId.trim();
                        sessions.computeIfAbsent(sessionId, ignored -> Sinks.many().multicast().onBackpressureBuffer());

                        JsonNode message = handleJsonRpcRequest(root, sessionId);
                        if (message == null) {
                            return response.status(202)
                                    .header("Mcp-Session-Id", sessionId)
                                    .send();
                        }

                        return response.status(200)
                                .header("Content-Type", "application/json")
                                .header("Mcp-Session-Id", sessionId)
                                .sendString(Mono.just(objectMapper.writeValueAsString(message)))
                                .then();
                    }
                    catch (IOException exception) {
                        return response.status(500).sendString(Mono.just(exception.getMessage())).then();
                    }
                });
    }

    private JsonNode handleJsonRpcRequest(JsonNode root, String sessionId) {
        String method = root.path("method").asText("");
        JsonNode id = root.get("id");
        return switch (method) {
            case "initialize" -> jsonRpcResponse(id, initializeResult());
            case "notifications/initialized" -> null;
            case "tools/list" -> jsonRpcResponse(id, toolsListResult());
            case "tools/call" -> jsonRpcResponse(id, callToolResult(root.path("params")));
            default -> jsonRpcError(id, "Unsupported method: " + method + " for session " + sessionId);
        };
    }

    private ObjectNode initializeResult() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        result.set("capabilities", objectMapper.createObjectNode().set("tools", objectMapper.createObjectNode()));
        ObjectNode serverInfo = objectMapper.createObjectNode();
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", "1.0.0");
        result.set("serverInfo", serverInfo);
        return result;
    }

    private ObjectNode toolsListResult() {
        ObjectNode result = objectMapper.createObjectNode();
        ArrayNode tools = result.putArray("tools");

        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Reverse text via HTTP MCP.");
        ObjectNode inputSchema = tool.putObject("inputSchema");
        inputSchema.put("type", "object");
        ObjectNode properties = inputSchema.putObject("properties");
        ObjectNode textProperty = properties.putObject("text");
        textProperty.put("type", "string");
        inputSchema.putArray("required").add("text");
        return result;
    }

    private ObjectNode callToolResult(JsonNode params) {
        String text = params.path("arguments").path("text").asText("");
        ObjectNode result = objectMapper.createObjectNode();
        result.put("isError", false);
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "text");
        item.put("text", "MCP:" + new StringBuilder(text).reverse());
        return result;
    }

    private ObjectNode jsonRpcResponse(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        response.set("result", result);
        return response;
    }

    private ObjectNode jsonRpcError(JsonNode id, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        if (id != null) {
            response.set("id", id);
        }
        ObjectNode error = response.putObject("error");
        error.put("code", -32601);
        error.put("message", message);
        return response;
    }

    private String sseEvent(String event, String data) {
        return "event:%s\ndata:%s\n\n".formatted(event, data);
    }

    private String queryParam(String uri, String name) {
        String rawQuery = URI.create(uri).getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return null;
        }

        String prefix = name + "=";
        for (String pair : rawQuery.split("&")) {
            if (pair.startsWith(prefix)) {
                return URLDecoder.decode(pair.substring(prefix.length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
