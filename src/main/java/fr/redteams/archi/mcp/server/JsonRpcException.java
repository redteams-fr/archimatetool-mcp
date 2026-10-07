package fr.redteams.archi.mcp.server;

/**
 * Protocol-level error, returned to the client as a JSON-RPC error object.
 */
public class JsonRpcException extends Exception {

    private static final long serialVersionUID = 1L;

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;

    private final int code;

    public JsonRpcException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
