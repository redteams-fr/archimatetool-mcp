package fr.redteams.archi.mcp.server;

/**
 * Error raised by a tool. It is reported to the model as a tool result with
 * {@code isError: true} so that it can correct its call, not as a protocol error.
 */
public class ToolException extends Exception {

    private static final long serialVersionUID = 1L;

    public ToolException(String message) {
        super(message);
    }
}
