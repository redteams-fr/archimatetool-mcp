package fr.redteams.archi.mcp.server;

import java.util.List;
import java.util.function.BiConsumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * Transport-independent MCP message handling (JSON-RPC 2.0).
 * Only the server features needed here are implemented: lifecycle, ping and tools.
 */
public final class McpProtocolHandler {

    /** Newest first. The first entry is offered when the client asks for an unknown version. */
    public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");

    public record ServerInfo(String name, String title, String version, String instructions) {}

    private final ServerInfo info;
    private final ToolRegistry tools;
    private final BiConsumer<String, Throwable> errorLog;

    public McpProtocolHandler(ServerInfo info, ToolRegistry tools, BiConsumer<String, Throwable> errorLog) {
        this.info = info;
        this.tools = tools;
        this.errorLog = errorLog;
    }

    /**
     * Handles one JSON-RPC message.
     *
     * @return the response, or {@code null} when none is due (notification or client response)
     */
    public JsonObject handle(JsonElement message) {
        if (message == null || !message.isJsonObject()) {
            return error(JsonNull.INSTANCE, JsonRpcException.INVALID_REQUEST, "Message must be a JSON object");
        }
        JsonObject msg = message.getAsJsonObject();
        JsonElement id = msg.get("id");
        boolean isRequest = id != null && !id.isJsonNull();

        if (!msg.has("method")) {
            return null; // a response to a server request: we never send any
        }
        String method;
        try {
            method = msg.get("method").getAsString();
        }
        catch (RuntimeException e) {
            return isRequest ? error(id, JsonRpcException.INVALID_REQUEST, "Invalid method") : null;
        }
        JsonObject params = msg.has("params") && msg.get("params").isJsonObject() ? msg.getAsJsonObject("params") : new JsonObject();

        if (!isRequest) {
            return null; // notifications/initialized, notifications/cancelled... nothing to do
        }

        try {
            JsonObject result = switch (method) {
                case "initialize" -> initialize(params);
                case "ping" -> new JsonObject();
                case "tools/list" -> listTools();
                case "tools/call" -> callTool(params);
                default -> throw new JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "Method not found: " + method);
            };
            JsonObject response = new JsonObject();
            response.addProperty("jsonrpc", "2.0");
            response.add("id", id);
            response.add("result", result);
            return response;
        }
        catch (JsonRpcException e) {
            return error(id, e.getCode(), e.getMessage());
        }
        catch (RuntimeException e) {
            errorLog.accept("Error handling " + method, e);
            return error(id, JsonRpcException.INTERNAL_ERROR, String.valueOf(e));
        }
    }

    private JsonObject initialize(JsonObject params) {
        String requested = params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : null;
        String version = SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ? requested : SUPPORTED_PROTOCOL_VERSIONS.get(0);

        JsonObject toolsCapability = new JsonObject();
        toolsCapability.addProperty("listChanged", false);
        JsonObject capabilities = new JsonObject();
        capabilities.add("tools", toolsCapability);

        JsonObject serverInfo = new JsonObject();
        serverInfo.addProperty("name", info.name());
        serverInfo.addProperty("title", info.title());
        serverInfo.addProperty("version", info.version());

        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", version);
        result.add("capabilities", capabilities);
        result.add("serverInfo", serverInfo);
        if (info.instructions() != null) {
            result.addProperty("instructions", info.instructions());
        }
        return result;
    }

    private JsonObject listTools() {
        JsonArray array = new JsonArray();
        for (Tool tool : tools.all()) {
            array.add(tool.toJson());
        }
        JsonObject result = new JsonObject();
        result.add("tools", array);
        return result;
    }

    private JsonObject callTool(JsonObject params) throws JsonRpcException {
        if (!params.has("name")) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Missing tool name");
        }
        String name = params.get("name").getAsString();
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new JsonRpcException(JsonRpcException.INVALID_PARAMS, "Unknown tool: " + name);
        }
        JsonElement args = params.get("arguments");
        Arguments arguments = new Arguments(args != null && args.isJsonObject() ? args.getAsJsonObject() : null);
        try {
            return tool.handler().call(arguments).toJson();
        }
        catch (ToolException e) {
            return ToolResult.error(e.getMessage()).toJson();
        }
        catch (Exception e) {
            errorLog.accept("Tool " + name + " failed", e);
            return ToolResult.error("Internal error in tool '" + name + "': " + e).toJson();
        }
    }

    public static JsonObject error(JsonElement id, int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id != null ? id : JsonNull.INSTANCE);
        response.add("error", error);
        return response;
    }
}
