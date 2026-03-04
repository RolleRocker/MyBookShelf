package com.bookshelf.mcp;

import com.bookshelf.BookRepository;
import com.bookshelf.HttpRequest;
import com.bookshelf.HttpResponse;
import com.bookshelf.ShelfRepository;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import java.util.HashMap;
import java.util.Map;

public class McpController {

    private static final String PROTOCOL_VERSION = "2025-03-26";
    private static final String SERVER_NAME = "bookshelf-mcp";
    private static final String SERVER_VERSION = "1.0.0";

    private final McpToolHandler toolHandler;

    public McpController(BookRepository bookRepository, ShelfRepository shelfRepository) {
        this.toolHandler = new McpToolHandler(bookRepository, shelfRepository);
    }

    public HttpResponse handleMcp(HttpRequest request) {
        String body = request.getBody();
        if (body == null || body.isBlank()) {
            return jsonRpcError(null, -32700, "Parse error: empty body");
        }

        JsonObject json;
        try {
            JsonElement element = JsonParser.parseString(body);
            if (!element.isJsonObject()) {
                return jsonRpcError(null, -32700, "Parse error: expected JSON object");
            }
            json = element.getAsJsonObject();
        } catch (JsonSyntaxException e) {
            return jsonRpcError(null, -32700, "Parse error: " + e.getMessage());
        }

        String method = json.has("method") ? json.get("method").getAsString() : null;
        if (method == null) {
            return jsonRpcError(getId(json), -32600, "Invalid request: missing method");
        }

        // Notifications have no "id" field — return 202 Accepted
        if (method.startsWith("notifications/")) {
            return new HttpResponse(202, "Accepted", new HashMap<>(), null);
        }

        JsonElement idElement = json.get("id");
        JsonObject params = json.has("params") && json.get("params").isJsonObject()
                ? json.getAsJsonObject("params")
                : new JsonObject();

        return switch (method) {
            case "initialize" -> handleInitialize(idElement);
            case "ping" -> jsonRpcResult(idElement, new JsonObject());
            case "tools/list" -> handleToolsList(idElement);
            case "tools/call" -> handleToolsCall(idElement, params);
            default -> jsonRpcError(idElement, -32601, "Method not found: " + method);
        };
    }

    private HttpResponse handleInitialize(JsonElement id) {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);

        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", new JsonObject());
        result.add("capabilities", capabilities);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", SERVER_NAME);
        serverInfo.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", serverInfo);

        return jsonRpcResult(id, result);
    }

    private HttpResponse handleToolsList(JsonElement id) {
        JsonObject result = new JsonObject();
        result.add("tools", toolHandler.getToolDefinitions());
        return jsonRpcResult(id, result);
    }

    private HttpResponse handleToolsCall(JsonElement id, JsonObject params) {
        String toolName = params.has("name") ? params.get("name").getAsString() : null;
        if (toolName == null) {
            return jsonRpcError(id, -32602, "Invalid params: missing tool name");
        }

        JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
                ? params.getAsJsonObject("arguments")
                : new JsonObject();

        String text;
        boolean isError = false;
        try {
            text = switch (toolName) {
                case "check_book" -> toolHandler.checkBook(args);
                case "search_books" -> toolHandler.searchBooks(args);
                case "list_books" -> toolHandler.listBooks(args);
                case "get_book_by_isbn" -> toolHandler.getBookByIsbn(args);
                case "get_bookshelf_stats" -> toolHandler.getBookshelfStats(args);
                default -> {
                    isError = true;
                    yield "Unknown tool: " + toolName;
                }
            };
        } catch (Exception e) {
            text = "Tool error: " + e.getMessage();
            isError = true;
            System.err.println("MCP tool '" + toolName + "' failed: " + e.getMessage());
            e.printStackTrace(System.err);
        }

        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        JsonObject textContent = new JsonObject();
        textContent.addProperty("type", "text");
        textContent.addProperty("text", text);
        content.add(textContent);
        result.add("content", content);
        result.addProperty("isError", isError);
        return jsonRpcResult(id, result);
    }

    // --- JSON-RPC response helpers ---

    private HttpResponse jsonRpcResult(JsonElement id, JsonObject result) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        response.add("result", result);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return new HttpResponse(200, "OK", headers, response.toString());
    }

    private HttpResponse jsonRpcError(JsonElement id, int code, String message) {
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        response.add("error", error);
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        return new HttpResponse(200, "OK", headers, response.toString());
    }

    private JsonElement getId(JsonObject json) {
        return json.has("id") ? json.get("id") : null;
    }
}
