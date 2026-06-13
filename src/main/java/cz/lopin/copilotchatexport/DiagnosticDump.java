package cz.lopin.copilotchatexport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dizitart.no2.collection.Document;
import org.h2.mvstore.MVStore;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Diagnostic tool: explores ALL data in the agent session database.
 */
public class DiagnosticDump {

    private static final Map<String, Map<String, String>> typeExamples = new TreeMap<>();
    private static final Set<String> documentFields = new TreeSet<>();
    private static int totalTurns = 0;
    private static int turnsWithContent = 0;

    public static void main(String[] args) throws IOException {
        String userHome = System.getenv("HOME");
        Path copilotConfig = Paths.get(userHome, ".config/github-copilot");

        System.out.println("=== SCANNING ALL DB FILES ===\n");

        Files.walkFileTree(copilotConfig, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("copilot-agent-sessions-nitrite.db")) {
                    scanDb(file.toAbsolutePath().toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("\n=== RESULTS ===");
        System.out.println("Total turns: " + totalTurns + ", with JSON content: " + turnsWithContent);

        System.out.println("\n--- All top-level document fields in NtAgentTurn ---");
        for (String field : documentFields) {
            System.out.println("  " + field);
        }

        System.out.println("\n--- All unique 'type' values found in subgraph data ---");
        for (var entry : typeExamples.entrySet()) {
            System.out.println("\n  type = \"" + entry.getKey() + "\"");
            for (var field : entry.getValue().entrySet()) {
                System.out.println("    " + field.getKey() + " = " + field.getValue());
            }
        }
    }

    private static void scanDb(String path) {
        try (var mvStore = new MVStore.Builder().fileName(path).readOnly().open()) {
            Set<String> mapNames = mvStore.getMapNames();
            if (!mapNames.isEmpty()) {
                System.out.println("Maps in " + path.replaceAll(".*/github-copilot/", "~/.config/github-copilot/") + ":");
                for (String name : mapNames) System.out.println("  " + name);
            }

            var agentTurnMap = mvStore.openMap("com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentTurn");
            for (Object value : agentTurnMap.values()) {
                if (value instanceof Document document) {
                    Long deletedAt = document.get("deletedAt", Long.class);
                    if (deletedAt != null) continue;
                    totalTurns++;
                    scanDocument(document);
                }
            }

            var agentSessionMap = mvStore.openMap("com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentSession");
            for (Object value : agentSessionMap.values()) {
                if (value instanceof Document document) {
                    var nestedTurns = document.get("turns", java.util.List.class);
                    if (nestedTurns == null) continue;
                    for (Object t : nestedTurns) {
                        if (t instanceof Document td) {
                            Long deletedAt = td.get("deletedAt", Long.class);
                            if (deletedAt != null) continue;
                            totalTurns++;
                            scanDocument(td);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("  Error: " + e.getMessage());
        }
    }

    private static void scanDocument(Document document) {
        document.getFields().forEach(key -> {
            Object val = document.get(key);
            documentFields.add(key + " (" + (val == null ? "null" : val.getClass().getSimpleName()) + ")");
        });

        for (String contentsField : List.of("request.contents", "response.contents")) {
            String content = document.get(contentsField, String.class);
            if (content == null || content.isEmpty()) continue;
            turnsWithContent++;
            try {
                collectTypes(readTree(content));
            } catch (Exception ignored) {}
        }
    }

    private static void collectTypes(JsonNode node) {
        if (node == null) return;
        if (node.isObject()) {
            if (node.has("type") && node.has("data")) {
                String type = node.get("type").asText();
                JsonNode data = node.get("data");
                Map<String, String> fields = typeExamples.computeIfAbsent(type, _ -> new TreeMap<>());
                if (data.isObject()) {
                    data.fieldNames().forEachRemaining(fn -> fields.computeIfAbsent(fn, _ -> {
                        JsonNode child = data.get(fn);
                        return child.isTextual()
                                ? "\"" + child.asText().substring(0, Math.min(child.asText().length(), 120)).replace("\n", "\\n") + "\""
                                : child.getNodeType().name();
                    }));
                } else if (data.isArray() && !data.isEmpty()) {
                    JsonNode first = data.get(0);
                    if (first.isObject()) {
                        first.fieldNames().forEachRemaining(fn -> fields.computeIfAbsent("array[0]." + fn, _ -> {
                            JsonNode child = first.get(fn);
                            return child.isTextual()
                                    ? "\"" + child.asText().substring(0, Math.min(child.asText().length(), 120)).replace("\n", "\\n") + "\""
                                    : child.getNodeType().name();
                        }));
                    } else {
                        fields.putIfAbsent("(array of)", first.getNodeType().name());
                    }
                } else {
                    fields.putIfAbsent("(value)", data.getNodeType().name() + ": " +
                            data.asText().substring(0, Math.min(data.asText().length(), 80)));
                }
            }
            if (node.has("type") && node.has("value") && !node.has("data")) {
                String type = node.get("type").asText();
                typeExamples.computeIfAbsent("__wrapper:" + type, _ -> new TreeMap<>())
                        .putIfAbsent("(value node type)", node.get("value").getNodeType().name());
            }
            node.elements().forEachRemaining(DiagnosticDump::collectTypes);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(DiagnosticDump::collectTypes);
        }
    }

    private static JsonNode readTree(String documentField) throws JsonProcessingException {
        ObjectMapper om = new ObjectMapper();
        JsonNode root = om.readTree(documentField);
        processChildren(om, root);
        return root;
    }

    private static void processChildren(ObjectMapper om, JsonNode node) throws JsonProcessingException {
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) processNode(om, it.next(), node);
    }

    private static void processNode(ObjectMapper om, String fieldName, JsonNode parent) throws JsonProcessingException {
        JsonNode child = parent.get(fieldName);
        if (child == null) return;
        if ((fieldName.equals("value") || fieldName.equals("data"))
                && child.isTextual()
                && (child.asText().startsWith("{") || child.asText().startsWith("["))) {
            JsonNode sub = om.readTree(child.asText());
            if (sub.isArray()) {
                ArrayNode arr = om.createArrayNode();
                for (JsonNode n : sub) {
                    if (n.isTextual() && n.asText().startsWith("{")) {
                        JsonNode el = om.readTree(n.asText());
                        processChildren(om, el);
                        arr.add(el);
                    } else arr.add(n);
                }
                ((ObjectNode) parent).replace(fieldName, arr);
            } else {
                ((ObjectNode) parent).replace(fieldName, sub);
                processChildren(om, sub);
            }
        } else {
            processChildren(om, child);
        }
    }
}