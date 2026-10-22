package com.example.agent.lsp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * A scripted language server for LspToolsTest (#190), speaking LSP over stdio like a real one.
 * Before answering initialize it asks the client for workspace/configuration and waits for the
 * reply, as jdtls does. A synced file containing BROKEN gets an error on that line; definition
 * answers with a LocationLink into Target.java; references mixes workspace locations with a JDK
 * file and a jdt:// class, which must be reported by name only. On didChange it first publishes
 * a stale result for the previous version, as a busy server may. Flags: --env-out FILE writes the
 * names of its environment variables there; --mute never answers; --garbage sends a malformed frame
 * after initialize. Any other argument is ignored (tests use one as a marker to find the process).
 */
public class FakeLspServer {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static OutputStream out;
    private static String root;

    public static void main(String[] args) throws IOException {
        java.util.List<String> flags = java.util.List.of(args);
        int envOut = flags.indexOf("--env-out");
        if (envOut >= 0) {
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[envOut + 1]),
                    String.join("\n", new java.util.TreeSet<>(System.getenv().keySet())));
        }
        boolean mute = flags.contains("--mute");
        boolean garbage = flags.contains("--garbage");
        out = System.out;
        InputStream in = System.in;
        while (true) {
            JsonNode msg = read(in);
            if (msg == null) {
                return;
            }
            String method = msg.path("method").asText("");
            if (mute) {
                continue;
            }
            switch (method) {
                case "initialize" -> {
                    root = msg.path("params").path("rootUri").asText();
                    ObjectNode ask = JSON.createObjectNode();
                    ask.put("jsonrpc", "2.0").put("id", "srv-1").put("method", "workspace/configuration");
                    ask.putObject("params").putArray("items").addObject().put("section", "java");
                    send(ask);
                    JsonNode reply = read(in);
                    if (reply == null || !"srv-1".equals(reply.path("id").asText()) || !reply.has("result")) {
                        System.exit(3); // the client did not answer our request
                    }
                    reply(msg, JSON.createObjectNode().set("capabilities", JSON.createObjectNode()));
                    if (garbage) {
                        out.write("Content-Length: abc\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        out.flush();
                    }
                }
                case "textDocument/didOpen", "textDocument/didChange" -> {
                    JsonNode p = msg.path("params");
                    String uri = p.path("textDocument").path("uri").asText();
                    String text = method.endsWith("didOpen") ? p.path("textDocument").path("text").asText()
                            : p.path("contentChanges").get(0).path("text").asText();
                    int version = p.path("textDocument").path("version").asInt();
                    if (method.endsWith("didChange")) {
                        ObjectNode stale = JSON.createObjectNode();
                        stale.put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics");
                        ObjectNode sp = stale.putObject("params");
                        sp.put("uri", uri).put("version", version - 1);
                        ObjectNode old = sp.putArray("diagnostics").addObject();
                        old.put("severity", 1).put("message", "stale: from the previous version");
                        old.set("range", range(0, 0));
                        send(stale);
                    }
                    ObjectNode pub = JSON.createObjectNode();
                    pub.put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics");
                    ObjectNode params = pub.putObject("params");
                    params.put("uri", uri).put("version", version);
                    ArrayNode ds = params.putArray("diagnostics");
                    String[] lines = text.split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        int col = lines[i].indexOf("BROKEN");
                        if (col >= 0) {
                            ObjectNode d = ds.addObject();
                            d.put("severity", 1).put("message", "BROKEN cannot be resolved to a type");
                            d.set("range", range(i, col));
                        }
                        if (lines[i].contains("MISSINGLIB")) {
                            String self = java.nio.file.Paths.get(java.net.URI.create(uri)).toString();
                            ObjectNode d = ds.addObject();
                            d.put("severity", 1).put("message", "Missing required library: '/home/someone/.m2/repository/lib-1.0.jar' for " + self);
                            d.set("range", range(i, 0));
                        }
                    }
                    send(pub);
                }
                case "textDocument/definition" -> {
                    ArrayNode links = JSON.createArrayNode();
                    ObjectNode link = links.addObject();
                    link.put("targetUri", root + "Target.java");
                    link.set("targetRange", range(2, 0));
                    link.set("targetSelectionRange", range(2, 4));
                    reply(msg, links);
                }
                case "textDocument/references" -> {
                    ArrayNode locs = JSON.createArrayNode();
                    locs.addObject().put("uri", root + "src/A.java").set("range", range(0, 0));
                    locs.addObject().put("uri", root + "src/A.java").set("range", range(0, 0));
                    locs.addObject().put("uri", "file:///usr/lib/jvm/java-21/lib/src/java/lang/String.java").set("range", range(10, 2));
                    locs.addObject().put("uri", "jdt://contents/rt.jar/java.lang/Object.class").set("range", range(0, 0));
                    reply(msg, locs);
                }
                case "workspace/symbol" -> {
                    ArrayNode syms = JSON.createArrayNode();
                    ObjectNode s = syms.addObject();
                    s.put("name", msg.path("params").path("query").asText() + "Service").put("kind", 5);
                    s.putObject("location").put("uri", root + "src/Svc.java").set("range", range(4, 13));
                    reply(msg, syms);
                }
                case "shutdown" -> reply(msg, null);
                case "exit" -> System.exit(0);
                default -> {
                    if (msg.has("id") && msg.has("method")) {
                        reply(msg, null);
                    }
                }
            }
        }
    }

    private static ObjectNode range(int line, int character) {
        ObjectNode r = JSON.createObjectNode();
        r.putObject("start").put("line", line).put("character", character);
        r.putObject("end").put("line", line).put("character", character + 1);
        return r;
    }

    private static void reply(JsonNode request, JsonNode result) throws IOException {
        ObjectNode r = JSON.createObjectNode();
        r.put("jsonrpc", "2.0").set("id", request.get("id"));
        r.set("result", result == null ? JSON.nullNode() : result);
        send(r);
    }

    private static synchronized void send(JsonNode msg) throws IOException {
        byte[] body = JSON.writeValueAsBytes(msg);
        out.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    private static JsonNode read(InputStream in) throws IOException {
        int length = -1;
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            if (b == '\n') {
                String h = line.toString(StandardCharsets.US_ASCII).trim();
                line.reset();
                if (h.isEmpty()) {
                    return JSON.readTree(in.readNBytes(length));
                }
                if (h.startsWith("Content-Length:")) {
                    length = Integer.parseInt(h.substring(15).trim());
                }
            } else {
                line.write(b);
            }
        }
    }
}
