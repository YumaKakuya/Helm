package dev.sorted.mcphub;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for JsonRpcServer.
 * Spec: Chapter 2 §2.5.2 (JSON-RPC 2.0 IPC protocol)
 */
class JsonRpcServerTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonRpcServer serverWith(JsonRpcServer.MethodHandler handler) {
        return new JsonRpcServer(handler, System.in, System.out);
    }

    @Test
    void successResponse() throws Exception {
        JsonRpcServer server = serverWith((method, params) ->
            mapper.createObjectNode().put("status", "ok"));
        String resp = server.processLine("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"test\",\"params\":{}}");
        JsonNode node = mapper.readTree(resp);
        assertNull(node.get("error"));
        assertEquals("ok", node.get("result").get("status").asText());
        assertEquals(1, node.get("id").asInt());
    }

    @Test
    void errorResponse_unknownMethod() throws Exception {
        JsonRpcServer server = serverWith((method, params) -> {
            throw new JsonRpcServer.JsonRpcException(JsonRpcServer.ERR_NOT_FOUND, "not found");
        });
        String resp = server.processLine("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"x\",\"params\":{}}");
        JsonNode node = mapper.readTree(resp);
        assertNotNull(node.get("error"));
        assertEquals(JsonRpcServer.ERR_NOT_FOUND, node.get("error").get("code").asInt());
    }

    @Test
    void parseError_invalidJson() throws Exception {
        JsonRpcServer server = serverWith((method, params) ->
            mapper.createObjectNode());
        String resp = server.processLine("not valid json{{{");
        JsonNode node = mapper.readTree(resp);
        assertNotNull(node.get("error"));
        assertEquals(JsonRpcServer.ERR_PARSE, node.get("error").get("code").asInt());
    }

    @Test
    void missingMethodField() throws Exception {
        JsonRpcServer server = serverWith((method, params) ->
            mapper.createObjectNode());
        String resp = server.processLine("{\"jsonrpc\":\"2.0\",\"id\":3,\"params\":{}}");
        JsonNode node = mapper.readTree(resp);
        assertNotNull(node.get("error"));
        assertEquals(JsonRpcServer.ERR_INVALID_REQ, node.get("error").get("code").asInt());
    }
}
