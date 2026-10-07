package fr.redteams.archi.mcp.archi;

import org.eclipse.gef.commands.Command;

/**
 * A GEF command built from two actions, executed on the model's command stack
 * so that the user can undo/redo what the assistant did.
 */
final class ModelCommand extends Command {

    private final Runnable doIt;
    private final Runnable undoIt;

    ModelCommand(String label, Runnable doIt, Runnable undoIt) {
        super(label);
        this.doIt = doIt;
        this.undoIt = undoIt;
    }

    @Override
    public void execute() {
        doIt.run();
    }

    @Override
    public void redo() {
        doIt.run();
    }

    @Override
    public void undo() {
        undoIt.run();
    }
}
