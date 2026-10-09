package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.config.DatabaseConnection;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WhatsAppTemplateAssignments {
    private static final Pattern VARIABLE =
            Pattern.compile("\\{\\{\\s*(\\w+)\\s*}}");

    public record MessageType(String key, String displayName, int requiredBodyVariableCount,
                             boolean adminAssignable, String templateId, String templateName,
                             String languageCode) { }

    public record Compatibility(int bodyVariableCount, List<String> bodyVariables,
                                String reason) {
        public boolean compatible() {
            return reason == null;
        }
    }

    private WhatsAppTemplateAssignments() {
    }

    public static List<MessageType> messageTypes() throws SQLException {
        String sql = """
            SELECT mt.message_key, mt.display_name, mt.required_body_variable_count,
                   mt.admin_assignable, a.meta_template_id, a.template_name, a.language_code
            FROM whatsapp_message_type mt
            LEFT JOIN whatsapp_message_template_assignment a
              ON a.message_key = mt.message_key
            ORDER BY mt.message_key
            """;
        List<MessageType> messageTypes = new ArrayList<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                messageTypes.add(new MessageType(
                        result.getString("message_key"),
                        result.getString("display_name"),
                        result.getInt("required_body_variable_count"),
                        result.getBoolean("admin_assignable"),
                        result.getString("meta_template_id"),
                        result.getString("template_name"),
                        result.getString("language_code")));
            }
        }
        return List.copyOf(messageTypes);
    }

    public static MessageType messageType(String messageKey) throws SQLException {
        return messageTypes().stream()
                .filter(type -> type.key().equals(messageKey))
                .findFirst()
                .orElse(null);
    }

    public static boolean isAdminAssignable(MessageType type) {
        return type.adminAssignable() && !"OTP".equals(type.key());
    }

    public static void assign(String messageKey, String templateId, long adminId)
            throws SQLException, IOException, InterruptedException, MetaOAuthService.MetaApiException {
        MessageType type = messageType(messageKey);
        if (type == null || !isAdminAssignable(type)) {
            throw new MetaOAuthService.MetaApiException(
                    "This message type cannot be assigned a template by an administrator.");
        }
        if (templateId == null || templateId.isBlank()) {
            String sql = """
                DELETE FROM whatsapp_message_template_assignment
                WHERE message_key = ?
                """;
            try (Connection connection = DatabaseConnection.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, messageKey);
                statement.executeUpdate();
            }
            return;
        }

        MetaOAuthService.MessageTemplate template = MetaOAuthService.messageTemplate(templateId);
        if (!"APPROVED".equalsIgnoreCase(template.status())) {
            throw new MetaOAuthService.MetaApiException(
                    "Only approved WhatsApp templates can be assigned.");
        }
        Compatibility compatibility = compatibility(template.components(),
                type.requiredBodyVariableCount());
        if (!compatibility.compatible()) {
            throw new MetaOAuthService.MetaApiException(compatibility.reason());
        }

        String sql = """
            INSERT INTO whatsapp_message_template_assignment
                (message_key, meta_template_id, template_name, language_code,
                 assigned_by_user_id, updated_at)
            VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT (message_key) DO UPDATE SET
                meta_template_id = EXCLUDED.meta_template_id,
                template_name = EXCLUDED.template_name,
                language_code = EXCLUDED.language_code,
                assigned_by_user_id = EXCLUDED.assigned_by_user_id,
                updated_at = CURRENT_TIMESTAMP
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, messageKey);
            statement.setString(2, template.id());
            statement.setString(3, template.name());
            statement.setString(4, template.language());
            statement.setLong(5, adminId);
            statement.executeUpdate();
        }
    }

    public static boolean isAssigned(String templateId) throws SQLException {
        String sql = """
            SELECT EXISTS (
                SELECT 1 FROM whatsapp_message_template_assignment
                WHERE meta_template_id = ?
            )
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, templateId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    public static Compatibility compatibility(JsonNode components, int expectedCount) {
        Set<String> bodyVariables = new LinkedHashSet<>();
        boolean nonBodyVariable = false;
        if (components != null && components.isArray()) {
            for (JsonNode component : components) {
                String type = component.path("type").asText("").toUpperCase();
                if ("BODY".equals(type)) {
                    collectVariables(component.path("text"), bodyVariables);
                } else {
                    nonBodyVariable |= containsVariableOutsideExamples(component);
                }
            }
        }

        List<String> orderedVariables = List.copyOf(bodyVariables);
        if (nonBodyVariable) {
            return new Compatibility(orderedVariables.size(), orderedVariables,
                    "This template has variables outside its body. Headers and buttons are not "
                            + "supported by this message service.");
        }
        if (orderedVariables.size() != expectedCount) {
            return new Compatibility(orderedVariables.size(), orderedVariables,
                    "This message requires exactly " + expectedCount + " body variable"
                            + (expectedCount == 1 ? "" : "s") + ", but this template has "
                            + orderedVariables.size() + ".");
        }
        if (!isSupportedVariableSequence(orderedVariables)) {
            return new Compatibility(orderedVariables.size(), orderedVariables,
                    "Template body variables must use consecutive positions starting at {{1}}, "
                            + "or distinct named variables.");
        }
        return new Compatibility(orderedVariables.size(), orderedVariables, null);
    }

    private static void collectVariables(JsonNode text, Set<String> variables) {
        if (!text.isTextual()) {
            return;
        }
        Matcher matcher = VARIABLE.matcher(text.asText());
        while (matcher.find()) {
            variables.add(matcher.group(1));
        }
    }

    private static boolean containsVariableOutsideExamples(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            Iterator<String> fieldNames = node.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                if (!"example".equals(fieldName)
                        && containsVariableOutsideExamples(node.get(fieldName))) {
                    return true;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsVariableOutsideExamples(item)) {
                    return true;
                }
            }
        } else if (node.isTextual()) {
            return VARIABLE.matcher(node.asText()).find();
        }
        return false;
    }

    private static boolean isSupportedVariableSequence(List<String> variables) {
        boolean positional = variables.stream().allMatch(value -> value.matches("\\d+"));
        if (!positional) {
            return variables.stream().noneMatch(value -> value.matches("\\d+"));
        }
        for (int index = 0; index < variables.size(); index++) {
            if (!Integer.toString(index + 1).equals(variables.get(index))) {
                return false;
            }
        }
        return true;
    }
}
