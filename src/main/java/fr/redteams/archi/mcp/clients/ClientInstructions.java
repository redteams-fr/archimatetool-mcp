package fr.redteams.archi.mcp.clients;

import java.net.URI;

/**
 * Configuration snippets for the supported MCP clients. The token is never written in a
 * config file: every client reads it from the {@value #TOKEN_ENV} environment variable.
 * <p>
 * Formats checked against Claude Code 2.1, OpenCode 1.18 and Mistral Vibe 2.26.
 */
public enum ClientInstructions {

    CLAUDE_CODE("Claude Code", "Run in a terminal. The server is added for all your projects (--scope user).") {
        @Override
        public String snippet(String url, boolean withToken) {
            String command = "claude mcp add --scope user --transport http archi " + url;
            // Single quotes: Claude Code stores ${ARCHI_MCP_TOKEN} and expands it when it connects
            return withToken ? command + " --header 'Authorization: Bearer ${" + TOKEN_ENV + "}'" : command;
        }
    },

    OPENCODE("OpenCode", "Add to ~/.config/opencode/opencode.json (or opencode.json in a project), merged into the existing \"mcp\" section.") {
        @Override
        public String snippet(String url, boolean withToken) {
            String headers = withToken
                    ? ",\n      \"headers\": {\n        \"Authorization\": \"Bearer {env:" + TOKEN_ENV + "}\"\n      }"
                    : "";
            return """
                    {
                      "$schema": "https://opencode.ai/config.json",
                      "mcp": {
                        "archi": {
                          "type": "remote",
                          "url": "%s",
                          "enabled": true,
                          "oauth": false%s
                        }
                      }
                    }""".formatted(url, headers);
        }
    },

    MISTRAL_VIBE("Mistral Vibe", "Run in a terminal. Vibe writes the server into ~/.vibe/config.toml.") {
        @Override
        public String snippet(String url, boolean withToken) {
            if (!withToken) {
                // Without --api-key-env, "vibe mcp add" would configure OAuth: use static auth in the TOML instead
                return """
                        # Add to ~/.vibe/config.toml
                        [[mcp_servers]]
                        name = "archi"
                        transport = "streamable-http"
                        url = "%s"

                        [mcp_servers.auth]
                        type = "static\"""".formatted(url);
            }
            String command = "vibe mcp add archi --url " + url + " --api-key-env " + TOKEN_ENV;
            // Vibe refuses plaintext http:// to a non-localhost host unless explicitly allowed
            return isLoopbackUrl(url) ? command : command + " --allow-insecure-http";
        }
    };

    /** Environment variable holding the bearer token, for every client. */
    public static final String TOKEN_ENV = "ARCHI_MCP_TOKEN";

    private final String label;
    private final String hint;

    ClientInstructions(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    /** Where/how to use the snippet. */
    public String hint() {
        return hint;
    }

    /**
     * @param url       MCP endpoint URL
     * @param withToken whether the server requires the bearer token
     */
    public abstract String snippet(String url, boolean withToken);

    /** Shell line defining the token variable (to put in ~/.zshrc or ~/.bashrc). */
    public static String exportLine(String token) {
        return "export " + TOKEN_ENV + "=\"" + token + "\"";
    }

    static boolean isLoopbackUrl(String url) {
        try {
            String host = URI.create(url).getHost();
            return host != null && (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]"));
        }
        catch (IllegalArgumentException e) {
            return false;
        }
    }
}
