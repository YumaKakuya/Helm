package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

/**
 * JSON-RPC 2.0 server reading from stdin, writing to stdout.
 * Go daemon shell communicates with Java core via this pipe protocol.
 * Spec: Chapter 2 §2.5.2 (JSON-RPC 2.0 over IPC)
 *
 * REQ-2.5.2: IPC protocol MUST be JSON-RPC 2.0
 */
public class JsonRpcServer {
    private static final Logger log = LoggerFactory.getLogger(JsonRpcServer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    /** Handler: (method, params) -> result node. Throw JsonRpcException for errors. */
    public interface MethodHandler {
        JsonNode handle(String method, JsonNode params) throws JsonRpcException;
    }

    public static class JsonRpcException extends Exception {
        private final int code;
        public JsonRpcException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int getCode() { return code; }
    }

    // Standard JSON-RPC error codes
    public static final int ERR_PARSE       = -32700;
    public static final int ERR_INVALID_REQ = -32600;
    public static final int ERR_NOT_FOUND   = -32601;
    public static final int ERR_PARAMS      = -32602;
    public static final int ERR_INTERNAL    = -32603;

    private final MethodHandler handler;
    private final InputStream in;
    private final OutputStream out;

    public JsonRpcServer(MethodHandler handler) {
        this(handler, System.in, System.out);
    }

    public JsonRpcServer(MethodHandler handler, InputStream in, OutputStream out) {
        this.handler = handler;
        this.in = in;
        this.out = out;
    }

    /**
     * Run the server loop: read requests from stdin, write responses to stdout.
     * Terminates when stdin is closed.
     */
    public void run() throws IOException {
        PrintWriter writer = new PrintWriter(new OutputStreamWriter(out), true);
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) continue;
            String response = processLine(line);
            writer.println(response);
            writer.flush();
        }
        log.info("JsonRpcServer: stdin closed, shutting down");
    }

    /** Process one JSON-RPC request line; return JSON response string. */
    String processLine(String line) {
        JsonNode id = null;
        try {
            JsonNode req = mapper.readTree(line);
            id = req.get("id");
            String method = req.path("method").asText(null);
            if (method == null) {
                return errorResponse(id, ERR_INVALID_REQ, "Missing method field");
            }
            JsonNode params = req.path("params");
            JsonNode result = handler.handle(method, params);
            return successResponse(id, result);
        } catch (JsonRpcException e) {
            return errorResponse(id, e.getCode(), e.getMessage());
        } catch (com.fasterxml.jackson.core.JsonParseException e) {
            return errorResponse(null, ERR_PARSE, "Parse error: " + e.getMessage());
        } catch (Exception e) {
            log.error("Internal error processing request", e);
            return errorResponse(id, ERR_INTERNAL, "Internal error: " + e.getMessage());
        }
    }

    private String successResponse(JsonNode id, JsonNode result) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id != null ? id : mapper.nullNode());
        resp.set("result", result);
        return resp.toString();
    }

    private String errorResponse(JsonNode id, int code, String message) {
        ObjectNode resp = mapper.createObjectNode();
        resp.put("jsonrpc", "2.0");
        resp.set("id", id != null ? id : mapper.nullNode());
        ObjectNode error = mapper.createObjectNode();
        error.put("code", code);
        error.put("message", message);
        resp.set("error", error);
        return resp.toString();
    }
}
