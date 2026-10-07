package fr.redteams.archi.mcp;

import org.eclipse.ui.IStartup;

/**
 * Called by the workbench (in a background thread) once Archi has started.
 */
public class Startup implements IStartup {

    @Override
    public void earlyStartup() {
        Activator.getDefault().getController().applyPreferences();
    }
}
