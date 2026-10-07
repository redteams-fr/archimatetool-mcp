package fr.redteams.archi.mcp.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

class BindAddressesTest {

    @Test
    void loopbackAddresses() {
        assertTrue(BindAddresses.isLoopback("127.0.0.1"));
        assertTrue(BindAddresses.isLoopback("localhost"));
        assertTrue(BindAddresses.isLoopback("::1"));
        assertTrue(BindAddresses.isLoopback("[::1]"));
        assertTrue(BindAddresses.isLoopback(""));   // blank = default
        assertTrue(BindAddresses.isLoopback(null));
        assertFalse(BindAddresses.isLoopback("0.0.0.0"));
        assertFalse(BindAddresses.isLoopback("::"));
        assertFalse(BindAddresses.isLoopback("192.168.1.10"));
    }

    @Test
    void onlyIpLiteralsAreAccepted() {
        assertTrue(BindAddresses.isValid("0.0.0.0"));
        assertTrue(BindAddresses.isValid("10.1.2.3"));
        assertTrue(BindAddresses.isValid("::"));
        assertTrue(BindAddresses.isValid("fe80::1"));
        assertFalse(BindAddresses.isValid("archi.example.com")); // would need DNS
        assertFalse(BindAddresses.isValid("zz:1"));
        assertFalse(BindAddresses.isValid("999.1.1.1"));
        assertFalse(BindAddresses.isValid("not an address"));
    }

    @Test
    void clientHost() {
        assertEquals("127.0.0.1", BindAddresses.clientHost("127.0.0.1"));
        assertEquals("127.0.0.1", BindAddresses.clientHost("::1"));
        assertEquals("10.1.2.3", BindAddresses.clientHost("10.1.2.3"));
        assertEquals("[fe80:0:0:0:0:0:0:1]", BindAddresses.clientHost("fe80::1"));
        assertNotEquals("0.0.0.0", BindAddresses.clientHost("0.0.0.0")); // a LAN address, or loopback
    }

    @Test
    void remoteBindingRequiresToken() {
        McpHttpServer server = new McpHttpServer(new McpProtocolHandler(
                new McpProtocolHandler.ServerInfo("t", "t", "1", null), new ToolRegistry(), (m, t) -> {}));
        assertThrows(IllegalArgumentException.class, () -> server.start("0.0.0.0", 0, null));
        assertThrows(IllegalArgumentException.class, () -> server.start("0.0.0.0", 0, ""));
        assertThrows(IllegalArgumentException.class, () -> server.start("archi.example.com", 0, "t"));
        assertFalse(server.isRunning());
    }

    @Test
    void listensOnAllInterfaces() throws Exception {
        McpHttpServer server = new McpHttpServer(new McpProtocolHandler(
                new McpProtocolHandler.ServerInfo("t", "t", "1", null), new ToolRegistry(), (m, t) -> {}));
        server.start("0.0.0.0", 0, "secret");
        try {
            HttpRequest ping = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + "/mcp"))
                    .header("Authorization", "Bearer secret")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                    .build();
            assertEquals(200, HttpClient.newHttpClient().send(ping, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
        finally {
            server.stop();
        }
    }
}
