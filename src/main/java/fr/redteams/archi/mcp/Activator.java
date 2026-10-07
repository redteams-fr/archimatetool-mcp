package fr.redteams.archi.mcp;

import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "fr.redteams.archi.mcp";

    private static Activator instance;

    private McpServerController controller;

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        instance = this;
        controller = new McpServerController(getPreferenceStore(), context.getBundle().getVersion().toString());
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (controller != null) {
            controller.stop();
            controller = null;
        }
        instance = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return instance;
    }

    public McpServerController getController() {
        return controller;
    }

    public static void log(int severity, String message, Throwable t) {
        Activator a = instance;
        if (a != null) {
            a.getLog().log(new Status(severity, PLUGIN_ID, message, t));
        }
        else {
            System.err.println("[archi-mcp] " + message);
        }
    }

    public static void info(String message) {
        log(IStatus.INFO, message, null);
    }

    public static void error(String message, Throwable t) {
        log(IStatus.ERROR, message, t);
    }
}
