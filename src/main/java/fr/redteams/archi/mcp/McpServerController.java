package fr.redteams.archi.mcp;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;

import fr.redteams.archi.mcp.archi.ArchiTools;
import fr.redteams.archi.mcp.server.BindAddresses;
import fr.redteams.archi.mcp.server.McpHttpServer;
import fr.redteams.archi.mcp.server.McpProtocolHandler;
import fr.redteams.archi.mcp.server.ToolRegistry;
import org.eclipse.jface.preference.IPersistentPreferenceStore;
import org.eclipse.jface.preference.IPreferenceStore;

/**
 * Starts, stops and reconfigures the MCP server from the plugin preferences.
 */
public final class McpServerController {

    private static final String INSTRUCTIONS = """
            This server gives access to the ArchiMate models currently open in the Archi modelling tool.
            - Start with list_models; pass model_id when several models are open.
            - Use list_element_types for valid element/relationship type names (e.g. BusinessActor, ApplicationComponent, ServingRelationship).
            - Search before creating to avoid duplicates (search_elements).
            - Relationships are checked against the ArchiMate rules; the error lists the valid types.
            - Every change is done as one undoable command in Archi (Edit > Undo). Nothing is written to disk until save_model.
            - get_selection returns what the user has currently selected in Archi.""";

    private final IPreferenceStore store;
    private final McpHttpServer server;
    private volatile String status = "Not started";

    McpServerController(IPreferenceStore store, String version) {
        this.store = store;
        ToolRegistry registry = new ToolRegistry();
        ArchiTools.registerAll(registry);
        McpProtocolHandler handler = new McpProtocolHandler(
                new McpProtocolHandler.ServerInfo("archi-mcp", "Archi MCP Server", version, INSTRUCTIONS),
                registry,
                Activator::error);
        this.server = new McpHttpServer(handler);
    }

    /** (Re)starts or stops the server according to the current preferences. */
    public synchronized void applyPreferences() {
        server.stop();
        if (!store.getBoolean(McpPreferences.ENABLED)) {
            status = "Disabled";
            return;
        }
        int port = store.getInt(McpPreferences.PORT);
        String bind = getBindAddress();
        boolean local = BindAddresses.isLoopback(bind);
        String token = null;
        // Outside the loopback interface a token is always required
        if (store.getBoolean(McpPreferences.REQUIRE_TOKEN) || !local) {
            token = ensureToken();
        }
        try {
            server.start(bind, port, token);
            status = local
                    ? "Running on " + getEndpointUrl()
                    : "Running on " + bind + ":" + port + " - REMOTE ACCESS ENABLED - clients use " + getEndpointUrl();
            Activator.info("MCP server " + status);
        }
        catch (IOException | IllegalArgumentException e) {
            status = "Error: cannot listen on " + bind + ":" + port + " (" + e.getMessage() + ")";
            Activator.error("MCP server failed to start on " + bind + ":" + port, e);
        }
    }

    public String getBindAddress() {
        return BindAddresses.normalize(store.getString(McpPreferences.BIND_ADDRESS));
    }

    public synchronized void stop() {
        server.stop();
        status = "Stopped";
    }

    public String getStatus() {
        return status;
    }

    public boolean isRunning() {
        return server.isRunning();
    }

    public String getEndpointUrl() {
        return endpointUrl(getBindAddress(), store.getInt(McpPreferences.PORT));
    }

    /** URL a client should use for the given listening address and port. */
    public static String endpointUrl(String bindAddress, int port) {
        return "http://" + BindAddresses.clientHost(bindAddress) + ":" + port + McpHttpServer.PATH;
    }

    private String ensureToken() {
        String token = store.getString(McpPreferences.TOKEN);
        if (token == null || token.isBlank()) {
            token = generateToken();
            store.setValue(McpPreferences.TOKEN, token);
            save(store);
        }
        return token;
    }

    public static String generateToken() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static void save(IPreferenceStore store) {
        if (store instanceof IPersistentPreferenceStore persistent && persistent.needsSaving()) {
            try {
                persistent.save();
            }
            catch (IOException e) {
                Activator.error("Cannot save preferences", e);
            }
        }
    }
}
