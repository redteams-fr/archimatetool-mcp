package fr.redteams.archi.mcp.archi;

import fr.redteams.archi.mcp.server.ToolRegistry;

/**
 * Entry point registering every Archi tool.
 */
public final class ArchiTools {

    private ArchiTools() {}

    public static void registerAll(ToolRegistry registry) {
        ReadTools.register(registry);
        EditTools.register(registry);
        ViewTools.register(registry);
        ValidationTools.register(registry);
    }
}
