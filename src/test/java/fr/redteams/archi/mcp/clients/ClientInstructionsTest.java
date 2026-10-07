package fr.redteams.archi.mcp.clients;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class ClientInstructionsTest {

    private static final String LOCAL = "http://127.0.0.1:18765/mcp";
    private static final String LAN = "http://192.168.1.10:18765/mcp";

    @Test
    void claudeCodeKeepsTheVariableReference() {
        String s = ClientInstructions.CLAUDE_CODE.snippet(LOCAL, true);
        assertEquals("claude mcp add --scope user --transport http archi " + LOCAL
                + " --header 'Authorization: Bearer ${ARCHI_MCP_TOKEN}'", s);
        assertFalse(ClientInstructions.CLAUDE_CODE.snippet(LOCAL, false).contains("Authorization"));
    }

    @Test
    void openCodeSnippetIsValidJson() {
        JsonObject archi = JsonParser.parseString(ClientInstructions.OPENCODE.snippet(LOCAL, true))
                .getAsJsonObject().getAsJsonObject("mcp").getAsJsonObject("archi");
        assertEquals("remote", archi.get("type").getAsString());
        assertEquals(LOCAL, archi.get("url").getAsString());
        assertFalse(archi.get("oauth").getAsBoolean());
        assertEquals("Bearer {env:ARCHI_MCP_TOKEN}", archi.getAsJsonObject("headers").get("Authorization").getAsString());

        JsonObject noToken = JsonParser.parseString(ClientInstructions.OPENCODE.snippet(LOCAL, false))
                .getAsJsonObject().getAsJsonObject("mcp").getAsJsonObject("archi");
        assertFalse(noToken.has("headers"));
    }

    @Test
    void vibeAllowsPlainHttpOnlyWhenNeeded() {
        assertEquals("vibe mcp add archi --url " + LOCAL + " --api-key-env ARCHI_MCP_TOKEN",
                ClientInstructions.MISTRAL_VIBE.snippet(LOCAL, true));
        assertTrue(ClientInstructions.MISTRAL_VIBE.snippet(LAN, true).endsWith(" --allow-insecure-http"));
        // Without a token, static auth must be explicit (otherwise Vibe would use OAuth)
        String noToken = ClientInstructions.MISTRAL_VIBE.snippet(LOCAL, false);
        assertTrue(noToken.contains("type = \"static\""));
        assertTrue(noToken.contains("url = \"" + LOCAL + "\""));
    }

    @Test
    void exportLine() {
        assertEquals("export ARCHI_MCP_TOKEN=\"abc\"", ClientInstructions.exportLine("abc"));
    }
}
