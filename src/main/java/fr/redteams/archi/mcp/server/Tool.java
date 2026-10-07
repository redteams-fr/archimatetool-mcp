package fr.redteams.archi.mcp.server;

import com.google.gson.JsonObject;

/**
 * An MCP tool: its metadata (as returned by {@code tools/list}) and its implementation.
 *
 * @param readOnly true if the tool never modifies the model (advertised as {@code readOnlyHint})
 */
public record Tool(String name, String title, String description, JsonObject inputSchema, boolean readOnly, Handler handler) {

    @FunctionalInterface
    public interface Handler {
        ToolResult call(Arguments args) throws Exception;
    }

    public JsonObject toJson() {
        JsonObject json = new JsonObject();
        json.addProperty("name", name);
        json.addProperty("title", title);
        json.addProperty("description", description);
        json.add("inputSchema", inputSchema);
        JsonObject annotations = new JsonObject();
        annotations.addProperty("title", title);
        annotations.addProperty("readOnlyHint", readOnly);
        annotations.addProperty("destructiveHint", false);
        annotations.addProperty("openWorldHint", false);
        json.add("annotations", annotations);
        return json;
    }
}
