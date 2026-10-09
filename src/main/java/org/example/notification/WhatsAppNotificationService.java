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
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String API_VERSION =
            setting("META_WHATSAPP_API_VERSION", "v23.0");
    private static final String PHONE_NUMBER_ID =
            setting("META_WHATSAPP_PHONE_NUMBER_ID", "");
    private static final String ACCESS_TOKEN =
            setting("META_WHATSAPP_ACCESS_TOKEN", "");
    private static final String API_URL =
            "https://graph.facebook.com/" + API_VERSION + "/" + PHONE_NUMBER_ID + "/messages";

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

    public static void sendTemplate(
            String phoneNumber,
            String templateName,
            Map<String, String> parameters) {
        sendTemplate(phoneNumber, templateName, "en_US", parameters, null, false);
    }

    private static void sendAssignedTemplate(String phoneNumber, String messageKey,
                                             Map<String, String> parameters) {
        final WhatsAppTemplateAssignments.MessageType messageType;
        try {
            messageType = WhatsAppTemplateAssignments.messageType(messageKey);
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Unable to read the WhatsApp template assignment for "
                    + messageKey + "; notification was not sent.", exception);
            return;
        }
        if (messageType == null || messageType.templateId() == null) {
            LOGGER.info("WhatsApp notification skipped: no template is assigned to "
                    + messageKey + ".");
            return;
        }

        try {
            MetaOAuthService.MessageTemplate template =
                    MetaOAuthService.messageTemplate(messageType.templateId());
            if (!"APPROVED".equalsIgnoreCase(template.status())) {
                LOGGER.warning("WhatsApp notification skipped: assigned template "
                        + template.name() + " is no longer approved.");
                return;
            }
            if (!template.name().equals(messageType.templateName())
                    || !template.language().equals(messageType.languageCode())) {
                LOGGER.warning("WhatsApp notification skipped: assigned template details changed. "
                        + "Review the " + messageKey + " assignment.");
                return;
            }
            WhatsAppTemplateAssignments.Compatibility compatibility =
                    WhatsAppTemplateAssignments.compatibility(template.components(),
                            messageType.requiredBodyVariableCount());
            if (!compatibility.compatible()) {
                LOGGER.warning("WhatsApp notification skipped: assigned template "
                        + template.name() + " is no longer compatible: "
                        + compatibility.reason());
                return;
            }
            if (parameters.size() != compatibility.bodyVariables().size()) {
                LOGGER.warning("WhatsApp notification skipped: the application supplied "
                        + parameters.size() + " values for "
                        + compatibility.bodyVariables().size() + " template variables.");
                return;
            }
            boolean named = "NAMED".equalsIgnoreCase(
                    template.details().path("parameter_format").asText("POSITIONAL"));
            sendTemplate(phoneNumber, template.name(), template.language(), parameters,
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
    }

    private static void sendTemplate(String phoneNumber, String templateName, String language,
                                     Map<String, String> parameters, List<String> variableNames,
                                     boolean namedParameters) {
        if (PHONE_NUMBER_ID.isBlank() || ACCESS_TOKEN.isBlank()) {
            LOGGER.warning("WhatsApp notification failed: Meta credentials are not configured.");
            return;
        }
        String recipient = toWhatsAppNumber(phoneNumber);
        if (recipient == null) {
            LOGGER.warning("WhatsApp notification failed: recipient phone number is invalid.");
            return;
        }
//        MetaOAuthService.MessagingCredentials credentials;
//        try {
//            credentials = MetaOAuthService.messagingCredentials();
//        } catch (SQLException | IOException exception) {
//            LOGGER.log(Level.WARNING,
//                    "WhatsApp notification failed: no usable employee system-user token is configured.",
//                    exception);
//            return;
//        } catch (RuntimeException exception) {
//            LOGGER.log(Level.SEVERE,
//                    "WhatsApp notification failed while decrypting or loading Meta credentials.",
//                    exception);
//            return;
//        }

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
            return;
        }
        LOGGER.fine("WhatsApp payload for template " + templateName + ": " + payloadJson);

//        String apiUrl = "https://graph.facebook.com/" + API_VERSION + "/"
//                + credentials.phoneNumberId() + "/messages";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + ACCESS_TOKEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
                .build();
//        HttpRequest request = HttpRequest.newBuilder()
//                .uri(URI.create(apiUrl))
//                .header("Authorization", "Bearer " + credentials.accessToken())
//                .header("Content-Type", "application/json")
//                .POST(HttpRequest.BodyPublishers.ofString(payloadJson, StandardCharsets.UTF_8))
//                .build();
        try {
            HttpResponse<String> response =
                    HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                LOGGER.info("WhatsApp notification sent successfully using template "
                        + templateName + " to " + recipient + ".");
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