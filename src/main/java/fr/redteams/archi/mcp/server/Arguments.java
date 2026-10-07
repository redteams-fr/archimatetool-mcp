package fr.redteams.archi.mcp.server;

import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed access to the {@code arguments} object of a {@code tools/call} request.
 * Every accessor throws a {@link ToolException} with a message meant for the model.
 */
public final class Arguments {

    private final JsonObject json;

    public Arguments(JsonObject json) {
        this.json = json != null ? json : new JsonObject();
    }

    public JsonObject raw() {
        return json;
    }

    public boolean has(String name) {
        JsonElement e = json.get(name);
        return e != null && !e.isJsonNull();
    }

    public String string(String name) throws ToolException {
        String value = optString(name, null);
        if (value == null || value.isBlank()) {
            throw new ToolException("Missing required argument '" + name + "'");
        }
        return value;
    }

    public String optString(String name, String defaultValue) throws ToolException {
        if (!has(name)) {
            return defaultValue;
        }
        JsonElement e = json.get(name);
        if (!e.isJsonPrimitive()) {
            throw new ToolException("Argument '" + name + "' must be a string");
        }
        return e.getAsString();
    }

    public int optInt(String name, int defaultValue) throws ToolException {
        if (!has(name)) {
            return defaultValue;
        }
        try {
            return json.get(name).getAsInt();
        }
        catch (RuntimeException e) {
            throw new ToolException("Argument '" + name + "' must be an integer");
        }
    }

    public Integer optInteger(String name) throws ToolException {
        return has(name) ? optInt(name, 0) : null;
    }

    public boolean optBoolean(String name, boolean defaultValue) throws ToolException {
        if (!has(name)) {
            return defaultValue;
        }
        JsonElement e = json.get(name);
        if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
            return e.getAsBoolean();
        }
        throw new ToolException("Argument '" + name + "' must be a boolean");
    }

    /**
     * Reads an object of string values. A {@code null} value is kept as {@code null}
     * (used to mean "remove this key").
     */
    public Map<String, String> optStringMap(String name) throws ToolException {
        if (!has(name)) {
            return null;
        }
        JsonElement e = json.get(name);
        if (!e.isJsonObject()) {
            throw new ToolException("Argument '" + name + "' must be an object of string values");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : e.getAsJsonObject().entrySet()) {
            JsonElement v = entry.getValue();
            if (v == null || v.isJsonNull()) {
                map.put(entry.getKey(), null);
            }
            else if (v.isJsonPrimitive()) {
                map.put(entry.getKey(), v.getAsString());
            }
            else {
                throw new ToolException("Value of '" + name + "." + entry.getKey() + "' must be a string or null");
            }
        }
        return map;
    }
}
