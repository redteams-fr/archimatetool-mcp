package fr.redteams.archi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

class McpHttpServerTest {

    private static final String TOKEN = "secret-token";

    private McpHttpServer server;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws Exception {
        ToolRegistry tools = new ToolRegistry()
                .register(new Tool("echo", "Echo", "Returns its text argument",
                        Schema.object().string("text", "Text", true).build(), true,
                        args -> ToolResult.text(args.string("text"))))
                .register(new Tool("fail", "Fail", "Always fails", Schema.object().build(), false,
                        args -> { throw new ToolException("boom"); }));
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpProtocolHandler.ServerInfo("test", "Test", "1.0", "Test server"), tools, (m, t) -> {});
        server = new McpHttpServer(handler);
        server.start(BindAddresses.LOOPBACK, 0, TOKEN);
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private HttpResponse<String> post(String body, String token, String origin, String session) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        if (origin != null) {
            b.header("Origin", origin);
        }
        if (session != null) {
            b.header("Mcp-Session-Id", session);
        }
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonObject json(HttpResponse<String> r) {
        return JsonParser.parseString(r.body()).getAsJsonObject();
    }

    private static final String INIT = """
            {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"t","version":"1"}}}""";

    @Test
    void initializeNegotiatesVersionAndCreatesSession() throws Exception {
        HttpResponse<String> r = post(INIT, TOKEN, null, null);
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Mcp-Session-Id").isPresent());
        JsonObject result = json(r).getAsJsonObject("result");
        assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
        assertTrue(result.getAsJsonObject("capabilities").has("tools"));
        assertEquals("test", result.getAsJsonObject("serverInfo").get("name").getAsString());
    }

    @Test
    void unknownProtocolVersionGetsLatest() throws Exception {
        HttpResponse<String> r = post(INIT.replace("2025-06-18", "1999-01-01"), TOKEN, null, null);
        assertEquals(McpProtocolHandler.SUPPORTED_PROTOCOL_VERSIONS.get(0),
                json(r).getAsJsonObject("result").get("protocolVersion").getAsString());
    }

    @Test
    void notificationIsAccepted() throws Exception {
        HttpResponse<String> r = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN, null, null);
        assertEquals(202, r.statusCode());
    }

    @Test
    void listsAndCallsTools() throws Exception {
        String session = post(INIT, TOKEN, null, null).headers().firstValue("Mcp-Session-Id").orElseThrow();

        JsonObject list = json(post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}", TOKEN, null, session));
        assertEquals(2, list.getAsJsonObject("result").getAsJsonArray("tools").size());

        JsonObject call = json(post("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{\"text\":\"hello\"}}}",
                TOKEN, null, session)).getAsJsonObject("result");
        assertFalse(call.get("isError").getAsBoolean());
        assertEquals("hello", call.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void toolErrorsAreResultsNotProtocolErrors() throws Exception {
        JsonObject failed = json(post("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"fail\"}}", TOKEN, null, null));
        assertTrue(failed.getAsJsonObject("result").get("isError").getAsBoolean());

        JsonObject missingArg = json(post("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"echo\",\"arguments\":{}}}", TOKEN, null, null));
        assertTrue(missingArg.getAsJsonObject("result").get("isError").getAsBoolean());

        JsonObject unknown = json(post("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}", TOKEN, null, null));
        assertEquals(JsonRpcException.INVALID_PARAMS, unknown.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void unknownMethodIsMethodNotFound() throws Exception {
        JsonObject r = json(post("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"resources/list\"}", TOKEN, null, null));
        assertEquals(JsonRpcException.METHOD_NOT_FOUND, r.getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void rejectsMissingOrWrongToken() throws Exception {
        assertEquals(401, post(INIT, null, null, null).statusCode());
        assertEquals(401, post(INIT, "wrong", null, null).statusCode());
    }

    @Test
    void rejectsForeignOrigin() throws Exception {
        assertEquals(403, post(INIT, TOKEN, "https://evil.example.com", null).statusCode());
        assertEquals(200, post(INIT, TOKEN, "http://localhost:3000", null).statusCode());
    }

    @Test
    void unknownSessionGets404() throws Exception {
        assertEquals(404, post("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}", TOKEN, null, "stale-session").statusCode());
    }

    @Test
    void parseErrorGets400() throws Exception {
        HttpResponse<String> r = post("{not json", TOKEN, null, null);
        assertEquals(400, r.statusCode());
        assertEquals(JsonRpcException.PARSE_ERROR, json(r).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void getIsNotAllowed() throws Exception {
        HttpRequest get = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/mcp"))
                .header("Authorization", "Bearer " + TOKEN).GET().build();
        assertEquals(405, client.send(get, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    @Test
    void batchReturnsArray() throws Exception {
        HttpResponse<String> r = post("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]",
                TOKEN, null, null);
        assertEquals(200, r.statusCode());
        assertNotNull(JsonParser.parseString(r.body()).getAsJsonArray());
        assertEquals(1, JsonParser.parseString(r.body()).getAsJsonArray().size());
    }
}
