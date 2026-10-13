package fr.redteams.archi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ArgumentsTest {

    private static Arguments args(String json) {
        return new Arguments(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void objectArrayItemsAreArguments() throws Exception {
        List<Arguments> items = args("{\"elements\": [{\"name\": \"CRM\"}, {\"name\": \"ERP\", \"x\": 10}]}").optObjectArray("elements");
        assertEquals(2, items.size());
        assertEquals("CRM", items.get(0).string("name"));
        assertEquals(10, items.get(1).optInteger("x"));
        assertNull(args("{}").optObjectArray("elements"));
    }

    @Test
    void objectArraySentAsStringIsAccepted() throws Exception {
        List<Arguments> items = args("{\"elements\": \"[{\\\"name\\\": \\\"CRM\\\"}]\"}").optObjectArray("elements");
        assertEquals("CRM", items.get(0).string("name"));
    }

    @Test
    void objectArrayRejectsOtherShapes() {
        assertThrows(ToolException.class, () -> args("{\"elements\": {\"name\": \"CRM\"}}").optObjectArray("elements"));
        assertThrows(ToolException.class, () -> args("{\"elements\": [\"CRM\"]}").optObjectArray("elements"));
        assertThrows(ToolException.class, () -> args("{\"elements\": \"CRM\"}").optObjectArray("elements"));
    }

    @Test
    void objectArraySchema() {
        JsonObject schema = Schema.object()
                .objectArray("elements", "Elements.", Schema.object().string("name", "Name.", true), true)
                .build();
        JsonObject elements = schema.getAsJsonObject("properties").getAsJsonObject("elements");
        assertEquals("array", elements.get("type").getAsString());
        assertEquals("name", elements.getAsJsonObject("items").getAsJsonArray("required").get(0).getAsString());
        assertEquals("elements", schema.getAsJsonArray("required").get(0).getAsString());
    }
}
