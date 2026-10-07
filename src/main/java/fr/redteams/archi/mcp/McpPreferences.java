package fr.redteams.archi.mcp;

/**
 * Preference keys of the plugin.
 */
public final class McpPreferences {

    public static final String ENABLED = "enabled";
    public static final String PORT = "port";
    /** IP address to listen on: 127.0.0.1 (default), 0.0.0.0 for all interfaces, or a specific one. */
    public static final String BIND_ADDRESS = "bindAddress";
    public static final String REQUIRE_TOKEN = "requireToken";
    public static final String TOKEN = "token";

    public static final int DEFAULT_PORT = 18765;

    private McpPreferences() {}
}
