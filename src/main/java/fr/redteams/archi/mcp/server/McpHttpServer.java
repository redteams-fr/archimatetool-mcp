package fr.redteams.archi.mcp.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * MCP "Streamable HTTP" transport built on the JDK's HTTP server, on the loopback interface
 * by default (other addresses require a token).
 * <p>
 * Requests are answered with plain {@code application/json} (no SSE stream): every tool
 * call is synchronous, and the server never initiates messages, so GET returns 405.
 */
public final class McpHttpServer {

    public static final String PATH = "/mcp";
    private static final int MAX_BODY_BYTES = 10 * 1024 * 1024;
    private static final int MAX_SESSIONS = 1000;
    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    private final McpProtocolHandler handler;
    private final Set<String> sessions = ConcurrentHashMap.newKeySet();

    private HttpServer server;
    private ExecutorService executor;
    private volatile String token;

    public McpHttpServer(McpProtocolHandler handler) {
        this.handler = handler;
    }

    /**
     * @param bindAddress IP literal to listen on (see {@link BindAddresses}); null means loopback
     * @param port        TCP port (0 picks a free one)
     * @param token       bearer token required in the Authorization header, or null to disable
     *                    authentication (only allowed on the loopback interface)
     */
    public synchronized void start(String bindAddress, int port, String token) throws IOException {
        InetAddress address = BindAddresses.parse(bindAddress);
        if (address == null) {
            throw new IllegalArgumentException("Invalid listening address '" + bindAddress + "': use an IP address such as 127.0.0.1 or 0.0.0.0");
        }
        if (!address.isLoopbackAddress() && (token == null || token.isEmpty())) {
            throw new IllegalArgumentException("A token is required to listen on " + bindAddress);
        }
        stop();
        this.token = token;
        HttpServer s = HttpServer.create(new InetSocketAddress(address, port), 0);
        AtomicInteger count = new AtomicInteger();
        executor = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "archi-mcp-http-" + count.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        s.setExecutor(executor);
        s.createContext("/", this::handle);
        s.start();
        server = s;
    }

    public synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        sessions.clear();
    }

    public synchronized boolean isRunning() {
        return server != null;
    }

    public synchronized int getPort() {
        return server != null ? server.getAddress().getPort() : -1;
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            if (!PATH.equals(path) && !(PATH + "/").equals(path)) {
                sendJson(exchange, 404, McpProtocolHandler.error(null, JsonRpcException.INVALID_REQUEST, "Not found: use " + PATH));
                return;
            }
            Headers headers = exchange.getRequestHeaders();
            // Protection against DNS rebinding: browsers always send Origin
            if (!isLocalOrigin(headers.getFirst("Origin"))) {
                sendJson(exchange, 403, McpProtocolHandler.error(null, JsonRpcException.INVALID_REQUEST, "Forbidden origin"));
                return;
            }
            if (!isAuthorized(headers.getFirst("Authorization"))) {
                sendJson(exchange, 401, McpProtocolHandler.error(null, JsonRpcException.INVALID_REQUEST,
                        "Unauthorized: send 'Authorization: Bearer <token>' (token shown in Archi > Preferences > MCP Server)"));
                return;
            }
            switch (exchange.getRequestMethod()) {
                case "POST" -> handlePost(exchange);
                case "DELETE" -> {
                    String sessionId = headers.getFirst(SESSION_HEADER);
                    if (sessionId != null) {
                        sessions.remove(sessionId);
                    }
                    exchange.sendResponseHeaders(204, -1);
                }
                default -> {
                    // No server-initiated stream (GET) is offered
                    exchange.getResponseHeaders().set("Allow", "POST, DELETE");
                    exchange.sendResponseHeaders(405, -1);
                }
            }
        }
    }

    private void handlePost(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY_BYTES + 1);
        }
        if (body.length > MAX_BODY_BYTES) {
            sendJson(exchange, 413, McpProtocolHandler.error(null, JsonRpcException.INVALID_REQUEST, "Request too large"));
            return;
        }

        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(body, StandardCharsets.UTF_8));
        }
        catch (JsonParseException e) {
            sendJson(exchange, 400, McpProtocolHandler.error(null, JsonRpcException.PARSE_ERROR, "Parse error: " + e.getMessage()));
            return;
        }

        boolean isInitialize = containsInitialize(parsed);
        String sessionId = exchange.getRequestHeaders().getFirst(SESSION_HEADER);
        if (sessionId != null && !isInitialize && !sessions.contains(sessionId)) {
            // Unknown session (e.g. Archi was restarted): the client must initialize again
            sendJson(exchange, 404, McpProtocolHandler.error(null, -32001, "Session not found"));
            return;
        }

        JsonElement response;
        if (parsed.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement message : parsed.getAsJsonArray()) {
                JsonObject r = handler.handle(message);
                if (r != null) {
                    responses.add(r);
                }
            }
            response = responses.isEmpty() ? null : responses;
        }
        else {
            response = handler.handle(parsed);
        }

        if (response == null) {
            exchange.sendResponseHeaders(202, -1); // only notifications or responses
            return;
        }
        if (isInitialize) {
            exchange.getResponseHeaders().set(SESSION_HEADER, newSession());
        }
        sendJson(exchange, 200, response);
    }

    private String newSession() {
        if (sessions.size() >= MAX_SESSIONS) {
            Iterator<String> it = sessions.iterator();
            it.next();
            it.remove();
        }
        String id = UUID.randomUUID().toString();
        sessions.add(id);
        return id;
    }

    private static boolean containsInitialize(JsonElement parsed) {
        if (parsed.isJsonArray()) {
            for (JsonElement e : parsed.getAsJsonArray()) {
                if (isInitialize(e)) {
                    return true;
                }
            }
            return false;
        }
        return isInitialize(parsed);
    }

    private static boolean isInitialize(JsonElement e) {
        return e.isJsonObject() && e.getAsJsonObject().has("method")
                && "initialize".equals(e.getAsJsonObject().get("method").getAsString());
    }

    private boolean isAuthorized(String authorization) {
        String expected = token;
        if (expected == null || expected.isEmpty()) {
            return true;
        }
        if (authorization == null) {
            return false;
        }
        return MessageDigest.isEqual(("Bearer " + expected).getBytes(StandardCharsets.UTF_8),
                authorization.trim().getBytes(StandardCharsets.UTF_8));
    }

    static boolean isLocalOrigin(String origin) {
        if (origin == null) {
            return true; // not a browser
        }
        try {
            String host = URI.create(origin).getHost();
            return host != null && (host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1"));
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void sendJson(HttpExchange exchange, int status, JsonElement json) throws IOException {
        byte[] bytes = GSON.toJson(json != null ? json : JsonNull.INSTANCE).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
