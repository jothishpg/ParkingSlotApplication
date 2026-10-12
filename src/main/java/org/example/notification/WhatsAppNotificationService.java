package org.example.notification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.config.DatabaseConnection;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class WhatsAppNotificationService {
    private static final Logger LOGGER =
            Logger.getLogger(WhatsAppNotificationService.class.getName());
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String API_VERSION =
            setting("META_WHATSAPP_API_VERSION", "v23.0");

    private WhatsAppNotificationService() {
    }

    public static void sendBookingConfirmation(long bookingId) {
        String sql = """
            SELECT b.phone_number, b.vehicle_number,
                   f.floor_name, s.slot_name, b.start_time
            FROM booking b
            JOIN slot s ON s.slot_id = b.slot_id
            JOIN floor f ON f.floor_id = s.floor_id
            WHERE b.booking_id = ?
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, bookingId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    Map<String, String> parameters = new LinkedHashMap<>();
                    parameters.put("driver_name", result.getString("vehicle_number"));
                    parameters.put("vehicle_number", result.getString("vehicle_number"));
                    parameters.put("floor_name", result.getString("floor_name"));
                    parameters.put("slot_name", result.getString("slot_name"));
                    parameters.put("start_time", result.getTimestamp("start_time").toString());

                    sendAssignedTemplate(result.getString("phone_number"),
                            "BOOKING_CONFIRMATION", parameters);
                }
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to load booking notification details.", exception);
        }
    }

    public static void sendCheckoutConfirmation(long bookingId) {
        String sql = """
            SELECT b.phone_number, b.vehicle_number, b.booking_id, s.slot_name,
                   da.total_duration, da.amount
            FROM booking b
            JOIN slot s ON s.slot_id = b.slot_id
            JOIN duration_amount da ON da.booking_id = b.booking_id
            WHERE b.booking_id = ?
            """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, bookingId);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    Map<String, String> parameters = new LinkedHashMap<>();
                    parameters.put("driver_name", result.getString("vehicle_number"));
                    parameters.put("slot_name", result.getString("slot_name"));
                    parameters.put("total_duration", result.getBigDecimal("total_duration") + " min");
                    parameters.put("amount", result.getBigDecimal("amount").toString());

                    sendAssignedTemplate(result.getString("phone_number"),
                            "CHECKOUT_CONFIRMATION", parameters);
                } else {
                    LOGGER.warning("Checkout notification skipped: booking details not found for booking "
                            + bookingId + ".");
                }
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING,
                    "Unable to load checkout notification details for booking " + bookingId + ".",
                    exception);
        }
    }

    public static void sendResetOtpCode(String phoneNumber, String otp) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("driver_name", otp);
        parameters.put("vehicle_number", "TN38M3389");
        parameters.put("floor_name", "OTP");
        parameters.put("slot_name", "VALIDATION");
        parameters.put("start_time", "TIME");
        sendTemplate(phoneNumber, "parking_booking_confirmed", parameters);
    }

    /** Outbox success delivery uses the checkout assignment and the committed checkout amount. */
    public static boolean sendPaymentCheckoutConfirmation(long bookingId, String recipient) throws SQLException {
        String sql = "SELECT b.vehicle_number,s.slot_name,da.total_duration,da.amount FROM booking b "
                + "JOIN slot s ON s.slot_id=b.slot_id JOIN duration_amount da ON da.booking_id=b.booking_id WHERE b.booking_id=?";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, bookingId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return false;
                return sendAssignedTemplate(recipient, "CHECKOUT_CONFIRMATION", checkoutTemplateParameters(
                        r.getString("vehicle_number"), r.getString("slot_name"),
                        r.getBigDecimal("total_duration"), r.getBigDecimal("amount")));
            }
        }
    }

    public static Map<String,String> checkoutTemplateParameters(String vehicle, String slot,
            java.math.BigDecimal duration, java.math.BigDecimal amount) {
        Map<String,String> parameters = new LinkedHashMap<>();
        parameters.put("driver_name", vehicle);
        parameters.put("slot_name", slot);
        parameters.put("total_duration", duration + " min");
        parameters.put("amount", amount.toString());
        return parameters;
    }

    /** Reuses the assigned booking template without changing its five-variable structure. */
    public static boolean sendPaymentMessage(long bookingId, String recipient, String message) throws SQLException {
        String sql = "SELECT b.vehicle_number,b.start_time,f.floor_name,s.slot_name FROM booking b "
                + "JOIN slot s ON s.slot_id=b.slot_id JOIN floor f ON f.floor_id=s.floor_id WHERE b.booking_id=?";
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement(sql)) {
            s.setLong(1, bookingId);
            try (ResultSet r = s.executeQuery()) {
                if (!r.next()) return false;
                return sendAssignedTemplate(recipient, "BOOKING_CONFIRMATION", paymentTemplateParameters(
                        r.getString("vehicle_number"),r.getString("floor_name"),r.getString("slot_name"),
                        r.getTimestamp("start_time").toString(),message));
            }
        }
    }

    public static Map<String,String> paymentTemplateParameters(String vehicle, String floor, String slot,
                                                               String start, String message) {
        Map<String,String> parameters = new LinkedHashMap<>();
        parameters.put("driver_name",vehicle);
        parameters.put("vehicle_number",vehicle);
        parameters.put("floor_name",floor);
        parameters.put("slot_name",slot);
        parameters.put("start_time",start + " | " + message);
        return parameters;
    }

    public static void sendTemplate(
            String phoneNumber,
            String templateName,
            Map<String, String> parameters) {
        sendTemplate(phoneNumber, templateName, "en_US", parameters, null, false);
    }

    private static boolean sendAssignedTemplate(String phoneNumber, String messageKey,
                                             Map<String, String> parameters) {
        final WhatsAppTemplateAssignments.MessageType messageType;
        try {
            messageType = WhatsAppTemplateAssignments.messageType(messageKey);
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to read the WhatsApp template assignment for "
                    + messageKey + "; notification was not sent.", exception);
            return false;
        }
        if (messageType == null || messageType.templateId() == null) {
            LOGGER.info("WhatsApp notification skipped: no template is assigned to "
                    + messageKey + ".");
            return false;
        }

        try {
            MetaOAuthService.MessageTemplate template =
                    MetaOAuthService.messageTemplate(messageType.templateId());
            if (!"APPROVED".equalsIgnoreCase(template.status())) {
                LOGGER.warning("WhatsApp notification skipped: assigned template "
                        + template.name() + " is no longer approved.");
                return false;
            }
            if (!template.name().equals(messageType.templateName())
                    || !template.language().equals(messageType.languageCode())) {
                LOGGER.warning("WhatsApp notification skipped: assigned template details changed. "
                        + "Review the " + messageKey + " assignment.");
                return false;
            }
            WhatsAppTemplateAssignments.Compatibility compatibility =
                    WhatsAppTemplateAssignments.compatibility(template.components(),
                            messageType.requiredBodyVariableCount());
            if (!compatibility.compatible()) {
                LOGGER.warning("WhatsApp notification skipped: assigned template "
                        + template.name() + " is no longer compatible: "
                        + compatibility.reason());
                return false;
            }
            if (parameters.size() != compatibility.bodyVariables().size()) {
                LOGGER.warning("WhatsApp notification skipped: the application supplied "
                        + parameters.size() + " values for "
                        + compatibility.bodyVariables().size() + " template variables.");
                return false;
            }
            boolean named = "NAMED".equalsIgnoreCase(
                    template.details().path("parameter_format").asText("POSITIONAL"));
            return sendTemplate(phoneNumber, template.name(), template.language(), parameters,
                    compatibility.bodyVariables(), named);
        } catch (MetaOAuthService.MetaApiException | IOException | SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to validate assigned WhatsApp template for "
                    + messageKey + "; notification was not sent.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Validation of the assigned WhatsApp template was "
                    + "interrupted; notification was not sent.", exception);
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unexpected error validating assigned WhatsApp template for "
                    + messageKey + "; notification was not sent.", exception);
        }
        return false;
    }

    private static boolean sendTemplate(String phoneNumber, String templateName, String language,
                                     Map<String, String> parameters, List<String> variableNames,
                                     boolean namedParameters) {
        String recipient = toWhatsAppNumber(phoneNumber);
        if (recipient == null) {
            LOGGER.warning("WhatsApp notification failed: recipient phone number is invalid.");
            return false;
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", templateName);
        template.put("language", Map.of("code", language));
        if (parameters != null && !parameters.isEmpty()) {
            List<Map<String, String>> bodyParameters = new ArrayList<>();
            List<String> values = new ArrayList<>(parameters.values());
            for (int index = 0; index < values.size(); index++) {
                Map<String, String> bodyParameter = new LinkedHashMap<>();
                bodyParameter.put("type", "text");
                if (namedParameters) {
                    bodyParameter.put("parameter_name", variableNames.get(index));
                } else if (variableNames == null) {
                    bodyParameter.put("parameter_name",
                            new ArrayList<>(parameters.keySet()).get(index));
                }
                bodyParameter.put("text", values.get(index));
                bodyParameters.add(bodyParameter);
            }
            template.put("components", List.of(Map.of(
                    "type", "body",
                    "parameters", bodyParameters
            )));
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("recipient_type", "individual");
        payload.put("to", recipient);
        payload.put("type", "template");
        payload.put("template", template);

        final String payloadJson;
        try {
            payloadJson = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            LOGGER.log(Level.WARNING, "Unable to build WhatsApp notification payload.", exception);
            return false;
        }
        LOGGER.fine("Preparing WhatsApp template " + templateName);

        final MetaOAuthService.MessagingCredentials credentials;
        try {
            credentials = MetaOAuthService.messagingCredentials();
        } catch (SQLException | IOException exception) {
            LOGGER.log(Level.WARNING,
                    "WhatsApp notification failed: no usable employee system-user token or "
                            + "active phone number is configured.",
                    exception);
            return false;
        } catch (RuntimeException exception) {
            LOGGER.log(Level.SEVERE,
                    "WhatsApp notification failed while decrypting or loading Meta credentials.",
                    exception);
            return false;
        }
        if (credentials.accessToken().isBlank() || credentials.phoneNumberId().isBlank()) {
            LOGGER.warning("WhatsApp notification failed: database messaging credentials are incomplete.");
            return false;
        }
        String apiUrl = "https://graph.facebook.com/" + API_VERSION + "/"
                + credentials.phoneNumberId() + "/messages";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .timeout(java.time.Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + credentials.accessToken())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOGGER.info("WhatsApp notification sent successfully using template "
                        + templateName + " to " + recipient + ".");
                return true;
            } else {
                LOGGER.warning("WhatsApp notification failed. HTTP status "
                        + response.statusCode() + ": " + response.body());
            }
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "WhatsApp notification failed due to an API request error.", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "WhatsApp notification failed because the API request was interrupted.",
                    exception);
        }
        return false;
    }

    private static String toWhatsAppNumber(String value) {
        if (value == null || !value.trim().matches("\\d{10}")) {
            return null;
        }
        return "+91" + value.trim();
    }

    private static String setting(String environmentName, String defaultValue) {
        String value = System.getenv(environmentName);
        return value == null || value.isBlank() ? defaultValue : value.trim();
    }

    private static void requestPrint(HttpRequest request){
        System.out.println("========== REQUEST ==========");
        System.out.println("Method: " + request.method());
        System.out.println("URI: " + request.uri());

        System.out.println("Headers:");
        request.headers().map().forEach(
                (name, values) ->
                        System.out.println(name + ": " + values)
        );

        System.out.println("Body:");
    }
    private static void responsePrint(HttpResponse<String> response){
        System.out.println("Status code : " + response.statusCode());
        System.out.println("HTTP version: " + response.version());

// Request info
        System.out.println("Request URI : " + response.uri());
        System.out.println("Method      : " + response.request().method());

// Headers
        System.out.println("\n--- Response headers ---");
        response.headers().map().forEach((name, values) ->
                System.out.println(name + ": " + String.join(", ", values)));

// Body
        System.out.println("\n--- Body ---");
        System.out.println(response.body());

// Extras
        System.out.println("\n--- Extras ---");
        System.out.println("SSL session    : " + response.sslSession().orElse(null));
        System.out.println("Previous resp. : " + response.previousResponse().orElse(null));
    }
}
