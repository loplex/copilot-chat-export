/*
 * Copyright 2025 nineninesevenfour
 * Copyright 2026 Martin Lopatář (https://github.com/loplex)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cz.lopin.copilotchatexport;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.dizitart.no2.collection.Document;
import org.h2.mvstore.MVStore;
import org.h2.mvstore.MVStoreException;
import org.slf4j.Logger;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class CopilotChatExport {

    private static final Path OUTPUT_DIR_BASIC = Paths.get(".", "chat-export", "basic");
    private static final Path OUTPUT_DIR_DETAILED = Paths.get(".", "chat-export", "detailed");
    private static final Path OUTPUT_DIR_STYLED = Paths.get(".", "chat-export", "styled");
    private static final Path OUTPUT_DIR_ASSETS = Paths.get(".", "chat-export", "_assets");

    private static final String TEMPLATE_NAME_BASIC = "chat_template_basic.th";
    private static final String TEMPLATE_NAME_DETAILED = "chat_template_detailed.th";
    private static final String TEMPLATE_NAME_STYLED = "chat_template_styled.th";


    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(CopilotChatExport.class);

    private static final Pattern ESCAPE_PATTERN = Pattern.compile("`[^`]*`|([\\\\*_{}\\[\\]()#+-.!|<>$])");
    private static final Pattern LEADING_SPACES = Pattern.compile("^( +)");

    private static final SimpleDateFormat FILENAME_DATE = new SimpleDateFormat("yyyy-MM-dd");
    private static final Pattern NON_ALPHABETIC_OR_NUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern LEADING_TRAILING_UNDERSCORES = Pattern.compile("^_+|_+$");

    private static final Map<String, Integer> FILE_COUNTERS = new HashMap<>();

    record Chat(
            String id,
            String name,
            String user,
            Date createdAt,
            Date modifiedAt,
            List<Turn> turns
    ) {}

    record Turn(
            String sessionId,
            Date createdAt,
            String chatMode,
            boolean chatModeChanged,
            String modelName,
            boolean modelChanged,
            String request,
            String response,
            List<Reference> references,
            boolean referencesChanged,
            List<Step> steps,
            List<ThinkingStep> thinking,
            List<WorkingSetFile> workingSet,
            String errorMessage,
            Integer rating
    ) {}

    record Reference(String name, String uri) {}

    record Step(String title, String status, String errorMessage) {}

    record ThinkingStep(String title, String content) {}

    record WorkingSetFile(String displayPath, String uri, String newContent, String originalContent) {}

    private static String USER_HOME;
    private static String USER_HOME_FILE_URL;

    private static TemplateEngine templateEngine;

    public static void main(String[] args) throws IOException {
        USER_HOME = System.getenv("HOME");
        USER_HOME_FILE_URL = "^" + Pattern.quote("file://" + USER_HOME);
        final Path copilotConfig = Paths.get(USER_HOME, ".config/github-copilot");
        if (!Files.exists(copilotConfig)) {
            LOGGER.error("no copilot config found, exiting.");
            return;
        }
        templateEngine = createTemplateEngine();
        Files.createDirectories(OUTPUT_DIR_BASIC);
        Files.createDirectories(OUTPUT_DIR_DETAILED);
        Files.createDirectories(OUTPUT_DIR_STYLED);
        Files.createDirectories(OUTPUT_DIR_ASSETS);
        final int[] sessions = {0, 0};
        Files.walkFileTree(copilotConfig, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().equals("copilot-agent-sessions-nitrite.db")) {
                    String logFilePath = file.toAbsolutePath().toString().replaceFirst(USER_HOME, "~");
                    LOGGER.info("Database file found: {}", logFilePath);
                    sessions[0]++;
                    sessions[1] += exportAgentSessions(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        LOGGER.info("Sessions found: {}, skipped: {}", sessions[0], sessions[1]);
    }

    private static int exportAgentSessions(Path dbPath) throws IOException {
        String dbPathAbsolute = dbPath.toAbsolutePath().toString();
        List<Chat> chats = readAgentSessions(dbPathAbsolute);
        int skipped = 0;
        for (Chat chat : chats) {
            if (!chat.turns.isEmpty()) {
                Path fileName = createFileName(chat);
                String baseName = fileName.toString().replace(".md", "");

                // Collect all unique edited files (by URI, latest version wins)
                Map<String, WorkingSetFile> allEditedFiles = new LinkedHashMap<>();
                for (Turn turn : chat.turns) {
                    for (WorkingSetFile wsf : turn.workingSet) {
                        if (!wsf.newContent().isEmpty()) {
                            allEditedFiles.put(wsf.uri(), wsf);
                        }
                    }
                }

                // Write edited file contents to the shared _assets folder and build link map
                Map<String, String> localLinksByUri = new LinkedHashMap<>();
                if (!allEditedFiles.isEmpty()) {
                    Path assetsSubfolder = OUTPUT_DIR_ASSETS.resolve(baseName);
                    Files.createDirectories(assetsSubfolder);

                    Map<String, Integer> filenameCounters = new LinkedHashMap<>();
                    for (WorkingSetFile wsf : allEditedFiles.values()) {
                        // Extract filename from URI
                        String rawName = wsf.uri().replaceFirst(".*[/\\\\]", "");
                        int count = filenameCounters.merge(rawName, 0, Integer::sum);
                        filenameCounters.put(rawName, count + 1);
                        String outputName = count == 0 ? rawName
                                : rawName.replaceFirst("(\\.[^.]+)$", "_" + count + "$1");

                        // Write the new (post-edit) version
                        Files.writeString(assetsSubfolder.resolve(outputName), wsf.newContent());

                        // Write the original (pre-edit) version if it differs
                        String orig = wsf.originalContent();
                        if (!orig.isEmpty() && !orig.equals(wsf.newContent())) {
                            String origName = outputName.replaceFirst("(\\.[^.]+)$", ".orig$1");
                            Files.writeString(assetsSubfolder.resolve(origName), orig);
                        }

                        // Both basic/ and styled/ share the same relative path: ../../_assets/...
                        localLinksByUri.put(wsf.uri(), "../_assets/" + baseName + "/" + outputName);
                    }
                }

                String markdownChatBasic = exportChat(chat, TEMPLATE_NAME_BASIC, localLinksByUri);
                Files.writeString(OUTPUT_DIR_BASIC.resolve(fileName), markdownChatBasic);

                String markdownChatDetailed = exportChat(chat, TEMPLATE_NAME_DETAILED, localLinksByUri);
                Files.writeString(OUTPUT_DIR_DETAILED.resolve(fileName), markdownChatDetailed);

                String markdownChatStyled = exportChat(chat, TEMPLATE_NAME_STYLED, localLinksByUri);
                Files.writeString(OUTPUT_DIR_STYLED.resolve(fileName), markdownChatStyled);
            } else {
                LOGGER.info("No turns found for chat with title \"{}\", skipping it.", chat.name);
                skipped++;
            }
        }
        return skipped;
    }

    private static Path createFileName(Chat chat) {
        String chatNameCleared1 = NON_ALPHABETIC_OR_NUMERIC.matcher(chat.name).replaceAll("_");
        String chatNameCleared2 = LEADING_TRAILING_UNDERSCORES.matcher(chatNameCleared1).replaceAll("");
        String filename = FILENAME_DATE.format(chat.createdAt) + "_" + chatNameCleared2;
        Integer number = FILE_COUNTERS.compute(filename, (_, v) -> v == null ? 0 : v + 1);
        if (number > 0) {
            filename += "_" + number;
        }
        return Path.of(filename + ".md");
    }

    private static List<Chat> readAgentSessions(String path) {
        List<Chat> chats = new ArrayList<>();
        try (var mvStore = new MVStore.Builder().fileName(path).readOnly().open()) {
            Map<String, List<Turn>> turnsBySessionId = getTurnsBySessionId(mvStore);
            getSessions(mvStore, chats, turnsBySessionId);
        } catch (MVStoreException e) {
            LOGGER.error("unable to open {}", path, e);
            return List.of();
        }
        return chats;
    }

    private static Map<String, List<Turn>> getTurnsBySessionId(MVStore mvStore) {
        var agentTurnMap = mvStore.openMap("com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentTurn");
        return processTurns(agentTurnMap.values()).stream()
                .sorted(Comparator.comparing(turn -> turn.sessionId + "|" + turn.createdAt.getTime()))
                .collect(Collectors.collectingAndThen(
                        Collectors.groupingBy(turn -> turn.sessionId), markTurnsFunction()));
    }

    private static Function<Map<String, List<Turn>>, Map<String, List<Turn>>> markTurnsFunction() {
        return map -> {
            map.replaceAll((_, turns) -> markTurnsOfSession(turns));
            return map;
        };
    }

    @SuppressWarnings("unchecked")
    private static void getSessions(MVStore mvStore, List<Chat> chats, Map<String, List<Turn>> turnsBySessionId) {
        var agentSessionMap = mvStore.openMap("com.github.copilot.agent.session.persistence.nitrite.entity.NtAgentSession");
        for (Object value : agentSessionMap.values()) {
            if (value instanceof Document document) {
                String chatId = document.get("id", String.class);
                String name = document.get("name.value", String.class);
                String user = document.get("user", String.class);
                Date createdAt = new Date(document.get("createdAt", Long.class));
                Date modifiedAt = new Date(document.get("modifiedAt", Long.class));
                List<Turn> chatTurns;
                List<Object> nestedTurns = document.get("turns", List.class);
                if (nestedTurns != null && !nestedTurns.isEmpty()) {
                    chatTurns = markTurnsOfSession(processTurns(nestedTurns));
                } else {
                    chatTurns = turnsBySessionId.getOrDefault(chatId, List.of());
                }                Chat chat = new Chat(chatId, name, user, createdAt, modifiedAt, chatTurns);
                chats.add(chat);
            }
        }
    }

    private static List<Turn> markTurnsOfSession(List<Turn> turns) {
        List<Turn> markedTurns = new ArrayList<>();
        String previousChatMode = "";
        String previousModelName = "";
        List<Reference> previousReferences = List.of();
        for (Turn turn : turns) {
            String chatMode = turn.chatMode;
            boolean chatModeChanged = chatMode != null && !previousChatMode.equals(chatMode);
            previousChatMode = chatMode != null ? chatMode : "";
            String modelName = turn.modelName;
            boolean modelChanged = modelName != null && !previousModelName.equals(modelName);
            previousModelName = modelName != null ? modelName : "";
            var references = turn.references;
            boolean referencesChanged = !previousReferences.equals(references);
            previousReferences = references;
            Turn markedTurn = new Turn(turn.sessionId, turn.createdAt, turn.chatMode, chatModeChanged,
                    turn.modelName, modelChanged, turn.request, turn.response, turn.references, referencesChanged,
                    turn.steps, turn.thinking, turn.workingSet, turn.errorMessage, turn.rating);
            markedTurns.add(markedTurn);
        }
        return markedTurns;
    }

    private static List<Turn> processTurns(Collection<Object> turns) {
        List<Turn> result = new ArrayList<>();
        for (Object turn : turns) {
            if (turn instanceof Document document) {
                Long deletedAt = document.get("deletedAt", Long.class);
                if (deletedAt != null) {
                    continue;
                }
                String sessionId = document.get("sessionId", String.class);
                Date createdAt = new Date(document.get("createdAt", Long.class));
                String chatMode = document.get("request.chatMode", String.class);
                String modelName = document.get("response.modelInformation.modelName", String.class);
                Integer rating = document.get("rating", Integer.class);
                String requestString = document.get("request.stringContent", String.class);
                String requestContent = document.get("request.contents", String.class);
                Optional<JsonNode> requestJson = parseJson(requestContent);
                String request = requestJson.map(jsonNode -> oneOrTheOther(requestString, jsonNode)).orElse(requestString);
                String responseString = document.get("response.stringContent", String.class);
                String responseContent = document.get("response.contents", String.class);
                Optional<JsonNode> responseJson = parseJson(responseContent);
                String response = responseJson.map(jsonNode -> oneOrTheOther(responseString, jsonNode)).orElse(responseString);
                var references = responseJson.map(CopilotChatExport::getReferences).orElseGet(List::of);
                var steps = responseJson.map(CopilotChatExport::getSteps).orElseGet(List::of);
                var thinking = responseJson.map(CopilotChatExport::getThinking).orElseGet(List::of);
                // WorkingSet can appear in both request and response JSON
                var workingSetFromResponse = responseJson.map(CopilotChatExport::getWorkingSet).orElseGet(List::of);
                var workingSetFromRequest = requestJson.map(CopilotChatExport::getWorkingSet).orElseGet(List::of);
                var workingSet = Stream.concat(workingSetFromResponse.stream(), workingSetFromRequest.stream())
                        .distinct().toList();
                var errorMessage = responseJson.flatMap(CopilotChatExport::getErrorMessage).orElse(null);
                Turn chatTurn = new Turn(sessionId, createdAt, chatMode, false,
                        modelName, false, request, response, references, false, steps, thinking,
                        workingSet, errorMessage, rating);
                result.add(chatTurn);
            }
        }
        return result;
    }

    private static Optional<JsonNode> parseJson(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return Optional.empty();
        }
        if (jsonString.startsWith("{")) {
            try {
                return Optional.ofNullable(readTree(jsonString));
            } catch (JsonProcessingException e) {
                LOGGER.error("Error reading JSON", e);
            }
        }
        return Optional.empty();
    }

    private static List<Reference> getReferences(JsonNode jsonNode) {
        return getSubgraphData(jsonNode)
                .filter(entry -> "References".equals(entry.getKey()))
                .filter(entry -> entry.getValue().isArray())
                .flatMap(entry -> entry.getValue().valueStream())
                .map(node -> asSubNode(node, "type", "reference"))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .map(entry -> getTextualNode(entry.getValue(), "uri")
                        .map(uri -> new Reference(uri.replaceFirst(USER_HOME_FILE_URL, "~"), uri)))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .toList();
    }

    private static List<Step> getSteps(JsonNode jsonNode) {
        return getSubgraphData(jsonNode)
                .filter(entry -> "Steps".equals(entry.getKey()))
                .filter(entry -> entry.getValue().isArray())
                .flatMap(entry -> entry.getValue().valueStream())
                .map(node -> {
                    String errorMessage = null;
                    if (node.has("error")) {
                        JsonNode errorNode = node.get("error");
                        if (errorNode.has("message")) {
                            errorMessage = errorNode.get("message").asText();
                        }
                    }
                    return new Step(node.get("title").asText(), node.get("status").asText(), errorMessage);
                })
                .toList();
    }

    private static List<ThinkingStep> getThinking(JsonNode jsonNode) {
        return getSubgraphData(jsonNode)
                .filter(entry -> "Thinking".equals(entry.getKey()))
                .map(entry -> {
                    JsonNode data = entry.getValue();
                    String title = data.has("title") ? data.get("title").asText() : "";
                    String content = data.has("content") ? data.get("content").asText() : "";
                    return new ThinkingStep(title, content);
                })
                .filter(ts -> !ts.title().isEmpty() || !ts.content().isEmpty())
                .toList();
    }

    private static List<WorkingSetFile> getWorkingSet(JsonNode jsonNode) {
        return collectByType(jsonNode, "WorkingSet")
                .filter(n -> n.has("data") && n.get("data").isArray())
                .flatMap(n -> n.get("data").valueStream())
                .filter(item -> item.has("file") && item.has("newContent"))
                .map(item -> {
                    String fileUri = item.get("file").asText();
                    String newContent = item.get("newContent").asText();
                    String originalContent = item.has("originalContent") ? item.get("originalContent").asText() : "";
                    return new WorkingSetFile(fileUri.replaceFirst(USER_HOME_FILE_URL, "~"), fileUri, newContent, originalContent);
                })
                .distinct()
                .toList();
    }

    private static Optional<String> getErrorMessage(JsonNode jsonNode) {
        return collectByType(jsonNode, "Error")
                .filter(n -> n.has("data") && n.get("data").isObject())
                .map(n -> {
                    JsonNode data = n.get("data");
                    if (data.has("message")) {
                        String msg = data.get("message").asText();
                        if (data.has("model") && !data.get("model").asText().isEmpty()) {
                            msg += " (model: " + data.get("model").asText() + ")";
                        }
                        return msg;
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .findFirst();
    }

    /** Recursively finds all nodes anywhere in the JSON tree that have {@code "type": targetType}. */
    private static Stream<JsonNode> collectByType(JsonNode node, String targetType) {
        if (node == null) return Stream.empty();
        if (node.isObject()) {
            if (node.has("type") && targetType.equals(node.get("type").asText())) {
                return Stream.of(node);
            }
            return node.valueStream().flatMap(child -> collectByType(child, targetType));
        } else if (node.isArray()) {
            return node.valueStream().flatMap(child -> collectByType(child, targetType));
        }
        return Stream.empty();
    }

    private static Stream<Map.Entry<String, JsonNode>> getSubgraphData(JsonNode jsonNode) {
        return jsonNode.propertyStream()
                .map(property -> asSubNode(property.getValue(), "type", "value").orElse(property))
                .filter(entry -> "Subgraph".equals(entry.getKey()))
                .flatMap(entry -> entry.getValue().propertyStream())
                .map(property -> asSubNode(property.getValue(), "type", "value").orElse(property))
                .filter(entry -> "Value".equals(entry.getKey()))
                .map(property -> asSubNode(property.getValue(), "type", "data").orElse(property));
    }

    private static String oneOrTheOther(String firstOption, JsonNode secondOption) {
        if (firstOption != null && !firstOption.isEmpty()) {
            return firstOption;
        }
        return getText(secondOption);
    }

    private static String getText(JsonNode jsonNode) {
        String text = jsonNode.propertyStream()
                .map(property -> asSubNode(property.getValue(), "type", "value").orElse(property))
                .filter(entry -> "Value".equals(entry.getKey()))
                .map(entry -> asSubNode(entry.getValue(), "type", "data").orElse(entry))
                .filter(entry -> "Markdown".equals(entry.getKey()))
                .map(CopilotChatExport::valueAsText)
                .findFirst().orElse("");
        if (text.isEmpty()) {
            text = jsonNode.propertyStream()
                    .map(property -> asSubNode(property.getValue(), "type", "value").orElse(property))
                    .filter(entry -> "Value".equals(entry.getKey()))
                    .map(entry -> asSubNode(entry.getValue(), "type", "data").orElse(entry))
                    .filter(entry -> "AgentRound".equals(entry.getKey()))
                    .map(entry -> asSubNode(entry.getValue(), "roundId", "reply").orElse(entry))
                    .map(CopilotChatExport::valueAsText)
                    .collect(Collectors.joining("  \n"));
        }
        return text;
    }

    private static String valueAsText(Map.Entry<String, JsonNode> entry) {
        if (entry.getValue() instanceof ObjectNode objectNode) {
            return objectNode.get("text").asText();
        } else if (entry.getValue().isTextual()) {
            return entry.getValue().asText();
        } else {
            return "";
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static Optional<String> getTextualNode(JsonNode jsonNode, String key) {
        if (jsonNode instanceof ObjectNode && jsonNode.has(key) && jsonNode.get(key).isTextual()) {
            String text = jsonNode.get(key).asText();
            return text.isEmpty() ? Optional.empty() : Optional.of(text);
        }
        return Optional.empty();
    }

    private static Optional<Map.Entry<String, JsonNode>> asSubNode(JsonNode jsonNode, String typeKey, String valueKey) {
        if (jsonNode instanceof ObjectNode objectNode) {
            String type = objectNode.get(typeKey).asText();
            JsonNode valueNode = objectNode.get(valueKey);
            return Optional.of(Map.entry(type, valueNode));
        } else {
            return Optional.empty();
        }
    }

    private static JsonNode readTree(String documentField) throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        JsonNode jsonNode = objectMapper.readTree(documentField);
        processChildren(objectMapper, jsonNode);
        return jsonNode;
    }

    private static void processChildren(ObjectMapper objectMapper, JsonNode jsonNode) throws JsonProcessingException {
        Iterator<String> fieldNames = jsonNode.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            processNode(objectMapper, fieldName, jsonNode);
        }
    }

    private static void processNode(ObjectMapper objectMapper, String fieldName, JsonNode jsonNode) throws JsonProcessingException {
        JsonNode childNode = jsonNode.get(fieldName);
        if (childNode == null) {
            return;
        }
        if ((fieldName.equals("value") || fieldName.equals("data"))
                && childNode.isTextual()
                && (childNode.asText().startsWith("{") || childNode.asText().startsWith("["))) {
            JsonNode subNode = objectMapper.readTree(childNode.asText());
            if (subNode.isArray()) {
                ArrayNode arrayNode = objectMapper.createArrayNode();
                processElements(objectMapper, subNode, arrayNode);
                ((ObjectNode)jsonNode).replace(fieldName, arrayNode);
            } else {
                ((ObjectNode)jsonNode).replace(fieldName, subNode);
                processChildren(objectMapper, subNode);
            }
        } else {
            processChildren(objectMapper, childNode);
        }
    }

    private static void processElements(ObjectMapper objectMapper, JsonNode subNode, ArrayNode arrayNode) throws JsonProcessingException {
        for (JsonNode node : subNode) {
            if (node.isTextual() && node.asText().startsWith("{")) {
                JsonNode elementNode = objectMapper.readTree(node.asText());
                processChildren(objectMapper, elementNode);
                arrayNode.add(elementNode);
            } else {
                arrayNode.add(node);
            }
        }
    }

    private static TemplateEngine createTemplateEngine() {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        var engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static String exportChat(Chat chat, String templateName, Map<String, String> localLinksByUri) {
        var context = new Context();
        context.setVariable("chat", chat);
        context.setVariable("escapedMarkdownLines", escapedMarkdownLines());
        context.setVariable("rawBlockquoteLines", rawBlockquoteLines());
        context.setVariable("stepStatusToSymbol", stepStatusToSymbol());
        // Returns a Markdown-formatted link if a local file exists, or just a code span otherwise.
        // Appends a link to the original version when it differs.
        context.setVariable("workingSetEntry", (Function<WorkingSetFile, String>) wsf -> {
            String link = localLinksByUri.get(wsf.uri());
            String display = wsf.displayPath();
            if (link == null) return "`" + display + "`";
            boolean hasOrig = !wsf.originalContent().isEmpty() && !wsf.originalContent().equals(wsf.newContent());
            String origLink = hasOrig ? link.replaceFirst("(\\.[^./]+)$", ".orig$1") : null;
            String result = "[`" + display + "`](" + link + ")";
            if (origLink != null) result += " ([orig](" + origLink + "))";
            return result;
        });
        return templateEngine.process(templateName, context);
    }

    private static Function<String, List<String>> escapedMarkdownLines() {
        return text -> {
            String[] lines = text.split("\n");
            boolean inCodeBlockFenced = false;
            boolean inCodeBlock = false;
            boolean wasEmptyLine = false;
            int i = 0;
            List<String> escapedLines = new ArrayList<>();
            for (String line : lines) {
                // set status for fenced code blocks marked with ```
                boolean wasInCodeBlockFenced = inCodeBlockFenced;
                inCodeBlockFenced = inCodeBlockFenced || line.startsWith("```");
                // set status for code blocks marked with >= 4 spaces
                inCodeBlock = line.startsWith("    ") && (inCodeBlock || wasEmptyLine);
                // do not escape lines of code blocks
                if (inCodeBlockFenced || inCodeBlock) {
                    // add line
                    escapedLines.add(line);
                } else {
                    // escape
                    String escaped = ESCAPE_PATTERN.matcher(line).replaceAll(match -> {
                        String result = match.group(1) == null
                                ? match.group(0)
                                : "\\" + match.group(1);
                        return Matcher.quoteReplacement(result);
                    });
                    // preserve leading spaces
                    escaped = LEADING_SPACES.matcher(escaped).replaceAll(match ->
                            match.group(0).replace(" ", "&nbsp;"));
                    // append two spaces for visible line breaks (except on empty lines or the end)
                    String suffix = line.trim().isEmpty() || i == lines.length - 1 ? "" : "  ";
                    // add line
                    escapedLines.add(escaped + suffix);
                }
                // reset fenced code block status
                if (wasInCodeBlockFenced && line.startsWith("```")) {
                    inCodeBlockFenced = false;
                }
                wasEmptyLine = line.trim().isEmpty();
                i++;
            }
            return escapedLines;
        };
    }

    private static Function<String, String> stepStatusToSymbol() {
        return stepStatus -> switch (stepStatus) {
            case "completed" -> "&#x2705;";
            case "failed" -> "&#x274C;";
            default -> "<" + stepStatus + ">";
        };
    }

    private static Function<String, List<String>> rawBlockquoteLines() {
        return text -> {
            if (text == null || text.isEmpty()) return List.of();
            return Arrays.asList(text.split("\n", -1));
        };
    }
}
