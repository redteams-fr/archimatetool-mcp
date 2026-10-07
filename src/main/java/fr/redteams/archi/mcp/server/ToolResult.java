package fr.redteams.archi.mcp.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Result of a {@code tools/call}: a single text content block.
 */
public record ToolResult(String text, boolean isError) {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create();

    public static ToolResult text(String text) {
        return new ToolResult(text, false);
    }

    public static ToolResult json(JsonElement data) {
        return new ToolResult(GSON.toJson(data), false);
    }

    public static ToolResult error(String message) {
        return new ToolResult(message, true);
    }

    public JsonObject toJson() {
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray array = new JsonArray();
        array.add(content);
        JsonObject result = new JsonObject();
        result.add("content", array);
        result.addProperty("isError", isError);
        return result;
    }
}
