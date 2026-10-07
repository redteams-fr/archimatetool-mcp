package fr.redteams.archi.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Small builder for the JSON Schema of a tool's input.
 */
public final class Schema {

    private final JsonObject properties = new JsonObject();
    private final JsonArray required = new JsonArray();

    public static Schema object() {
        return new Schema();
    }

    public Schema string(String name, String description, boolean isRequired) {
        return property(name, "string", description, isRequired);
    }

    public Schema integer(String name, String description, boolean isRequired) {
        return property(name, "integer", description, isRequired);
    }

    public Schema bool(String name, String description, boolean isRequired) {
        return property(name, "boolean", description, isRequired);
    }

    /** An object whose values are strings (or null), e.g. ArchiMate properties. */
    public Schema stringMap(String name, String description) {
        JsonObject p = new JsonObject();
        p.addProperty("type", "object");
        p.addProperty("description", description);
        JsonObject values = new JsonObject();
        JsonArray types = new JsonArray();
        types.add("string");
        types.add("null");
        values.add("type", types);
        p.add("additionalProperties", values);
        properties.add(name, p);
        return this;
    }

    private Schema property(String name, String type, String description, boolean isRequired) {
        JsonObject p = new JsonObject();
        p.addProperty("type", type);
        p.addProperty("description", description);
        properties.add(name, p);
        if (isRequired) {
            required.add(name);
        }
        return this;
    }

    public JsonObject build() {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", properties.deepCopy());
        if (!required.isEmpty()) {
            schema.add("required", required.deepCopy());
        }
        return schema;
    }
}
