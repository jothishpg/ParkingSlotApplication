package org.example.cliq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.example.booking.BookingResource;
import org.example.config.DatabaseConnection;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Path("/cliq")
public class CliqWebhookResource {
    private static final Logger LOGGER = Logger.getLogger(CliqWebhookResource.class.getName());
    private static final String BOOKING_FORMAT_MESSAGE =
            "Hi! To get your parking booking details, send a message in this format:\n"
                    + "BookingID:19, Vehicle_Number:TN28MA1829\n"
                    + "To see current parking fees, send: Fee details";
    private static final String BOOKING_FORMAT_REGEX =
            "(?i)\\bbooking[\\s_]*id\\s*:\\s*(\\d+)\\s*[,;]?\\s*"
            + "vehicle[\\s_]*number\\s*:\\s*"
            + "([a-z]{2}\\s*-?\\s*\\d{1,2}\\s*-?\\s*[a-z]{1,3}\\s*-?\\s*\\d{1,4})\\b";
    private static final Pattern BOOKING_FORMAT = Pattern.compile(BOOKING_FORMAT_REGEX);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    record BookingRequest(long bookingId, String vehicleNumber) {
    }

    private record FeeTier(int durationLimit, BigDecimal ratePerMinute) {
    }

    @POST
    @Path("/webhook")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receiveMessage(JsonNode payload) {
        if (payload == null || !payload.isObject()) {
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        String responseUrl = payload.path("response_url").asText(null);
        if (!isValidResponseUrl(responseUrl)) {
            LOGGER.warning("Cliq webhook payload has no valid Zoho response_url.");
            return Response.status(Response.Status.BAD_REQUEST).build();
        }
        try {
            BookingRequest bookingRequest =
                    parseBookingRequest(payload.path("params").path("message").asText(""));
            String reply;
            String message = payload.path("params").path("message").asText("");
            if (isFeeDetailsRequest(message)) {
                try {
                    reply = feeDetails();
                } catch (SQLException exception) {
                    LOGGER.log(Level.SEVERE, "Unable to load Cliq parking fee rates.", exception);
                    reply = "Sorry, I could not retrieve parking fee details right now. "
                            + "Please try again later.";
                }
            } else if (bookingRequest == null) {
                reply = BOOKING_FORMAT_MESSAGE;
            } else {
                try {
                    reply = bookingDetails(
                            bookingRequest.bookingId(), bookingRequest.vehicleNumber());
                } catch (SQLException exception) {
                    LOGGER.log(Level.SEVERE, "Unable to look up the Cliq booking request.", exception);
                    reply = "Sorry, I could not retrieve the booking details right now. "
                            + "Please try again later.";
                }
            }
            return postReply(responseUrl, reply);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            LOGGER.log(Level.WARNING, "Sending the Cliq response_url message was interrupted.", exception);
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        } catch (IOException exception) {
            LOGGER.log(Level.WARNING, "Unable to send a message to the Cliq response_url.", exception);
            return Response.status(Response.Status.BAD_GATEWAY).build();
        }
    }

    static boolean isFeeDetailsRequest(String message) {
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.matches("(?s).*\\b(fee|fees|rate|rates|charge|charges|pricing|price)\\b.*");
    }

    private String feeDetails() throws SQLException {
        String sql = """
                SELECT vt.type_name, vr.duration_limit, vr.rate_per_minute
                FROM vehicle_type vt
                LEFT JOIN vehicle_rate vr ON vr.vehicle_type_id = vt.vehicle_type_id
                ORDER BY LOWER(vt.type_name), vr.duration_limit
                """;
        Map<String, List<FeeTier>> ratesByVehicleType = new LinkedHashMap<>();
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                String typeName = result.getString("type_name");
                List<FeeTier> tiers = ratesByVehicleType.computeIfAbsent(
                        typeName, ignored -> new ArrayList<>());
                int durationLimit = result.getInt("duration_limit");
                if (!result.wasNull()) {
                    tiers.add(new FeeTier(durationLimit, result.getBigDecimal("rate_per_minute")));
                }
            }
        }
        if (ratesByVehicleType.isEmpty()) {
            return "Parking fee rates are not configured yet. Please contact the parking staff.";
        }

        StringBuilder response = new StringBuilder("Current parking fees (per minute):");
        ratesByVehicleType.forEach((typeName, tiers) -> {
            response.append("\n\n").append(typeName).append(':');
            if (tiers.isEmpty()) {
                response.append("\nRates are not configured.");
                return;
            }
            int previousLimit = 0;
            for (FeeTier tier : tiers) {
                int minutesInTier = tier.durationLimit() - previousLimit;
                response.append("\n");
                if (previousLimit == 0) {
                    response.append("First ").append(minutesInTier).append(" min");
                } else {
                    response.append("Next ").append(minutesInTier)
                            .append(" min (through ").append(tier.durationLimit()).append(" min)");
                }
                response.append(": INR ")
                        .append(tier.ratePerMinute().setScale(2, RoundingMode.HALF_UP))
                        .append("/min");
                previousLimit = tier.durationLimit();
            }
            FeeTier lastTier = tiers.get(tiers.size() - 1);
            response.append("\nAfter ").append(previousLimit).append(" min: INR ")
                    .append(lastTier.ratePerMinute().setScale(2, RoundingMode.HALF_UP))
                    .append("/min");
        });
        return response.toString();
    }

    static BookingRequest parseBookingRequest(String message) {
        if (message == null) {
            return null;
        }
        Matcher matcher = BOOKING_FORMAT.matcher(message);
        if (!matcher.find()) {
            return null;
        }
        try {
            long bookingId = Long.parseLong(matcher.group(1));
            if (bookingId <= 0) {
                return null;
            }
            return new BookingRequest(bookingId, normalizeVehicle(matcher.group(2)));
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private Response postReply(String responseUrl, String message)
            throws IOException, InterruptedException {
        String responseBody = JSON.writeValueAsString(Map.of("output", Map.of("text", message)));
        HttpRequest request = HttpRequest.newBuilder(URI.create(responseUrl))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", MediaType.APPLICATION_JSON)
                .POST(HttpRequest.BodyPublishers.ofString(responseBody))
                .build();
        HttpResponse<String> response =
                HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            LOGGER.warning("Zoho Cliq rejected the response_url message (HTTP "
                    + response.statusCode() + ").");
            return Response.status(Response.Status.BAD_GATEWAY).build();
        }
        return Response.ok().build();
    }

    private String bookingDetails(long bookingId, String vehicleNumber) throws SQLException {
        String sql = """
                SELECT b.vehicle_number, b.start_time, f.vehicle_type_id,
                       f.floor_name, s.slot_name, da.end_time,
                       da.total_duration, da.amount,
                       CEIL(GREATEST(
                           EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - b.start_time)) / 60, 1
                       ))::bigint AS current_minutes
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                WHERE b.booking_id = ?
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, bookingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()
                        || !normalizeVehicle(result.getString("vehicle_number")).equals(vehicleNumber)) {
                    return "I could not find a booking matching that booking ID and vehicle number. "
                            + "Please check both values and try again.";
                }

                Timestamp startTime = result.getTimestamp("start_time");
                Timestamp endTime = result.getTimestamp("end_time");
                BigDecimal duration;
                BigDecimal fee;
                String feeLabel;
                if (endTime == null) {
                    long currentMinutes = result.getLong("current_minutes");
                    duration = BigDecimal.valueOf(currentMinutes);
                    fee = BookingResource.calculateAmount(
                            connection, result.getInt("vehicle_type_id"), currentMinutes);
                    feeLabel = "Fee up to now";
                } else {
                    duration = result.getBigDecimal("total_duration");
                    fee = result.getBigDecimal("amount");
                    if (fee == null) {
                        throw new SQLException("Completed booking has no stored parking fee.");
                    }
                    feeLabel = "Final fee";
                }

                return "Booking details\n"
                        + "Booking ID: " + bookingId + "\n"
                        + "Vehicle: " + result.getString("vehicle_number") + "\n"
                        + "Floor: " + result.getString("floor_name") + "\n"
                        + "Slot: " + result.getString("slot_name") + "\n"
                        + "Start time: " + startTime + "\n"
                        + (endTime == null ? "" : "End time: " + endTime + "\n")
                        + "Duration: " + (duration == null ? "unavailable" : duration.stripTrailingZeros())
                        + " minutes\n"
                        + feeLabel + ": INR " + fee.setScale(2, RoundingMode.HALF_UP);
            }
        }
    }

    private static String normalizeVehicle(String value) {
        return value.replaceAll("[\\s-]+", "").toUpperCase(Locale.ROOT);
    }

    private boolean isValidResponseUrl(String responseUrl) {
        if (responseUrl == null || responseUrl.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(responseUrl);
            return "https".equalsIgnoreCase(uri.getScheme())
                    && "cliq.zoho.in".equalsIgnoreCase(uri.getHost())
                    && (uri.getPort() == -1 || uri.getPort() == 443)
                    && uri.getUserInfo() == null
                    && uri.getQuery() == null
                    && uri.getFragment() == null
                    && uri.getPath().matches(
                            "/company/[0-9]+/v2/extensions/-1/responses/[0-9]+");
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}