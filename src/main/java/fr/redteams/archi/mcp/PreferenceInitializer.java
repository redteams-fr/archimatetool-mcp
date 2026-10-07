package fr.redteams.archi.mcp;

import fr.redteams.archi.mcp.server.BindAddresses;
import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.jface.preference.IPreferenceStore;

public class PreferenceInitializer extends AbstractPreferenceInitializer {

    @Override
    public void initializeDefaultPreferences() {
        IPreferenceStore store = Activator.getDefault().getPreferenceStore();
        store.setDefault(McpPreferences.ENABLED, true);
        store.setDefault(McpPreferences.PORT, McpPreferences.DEFAULT_PORT);
        store.setDefault(McpPreferences.BIND_ADDRESS, BindAddresses.LOOPBACK);
        store.setDefault(McpPreferences.REQUIRE_TOKEN, true);
        store.setDefault(McpPreferences.TOKEN, "");
    }
}
