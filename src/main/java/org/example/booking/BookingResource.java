package org.example.booking;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.example.config.DatabaseConnection;
import org.example.notification.WhatsAppNotificationService;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("booking")
@RolesAllowed({"ADMIN", "TICKET_ASSIGNER"})
@Produces(MediaType.TEXT_HTML)
public class BookingResource {

    private static final Logger LOGGER =
            Logger.getLogger(BookingResource.class.getName());

    private static final String APP_BASE_URL =
            setting("APP_BASE_URL");

    @GET
    public Response dashboard(
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        String links;
        if (isAdmin(securityContext)) {
            links = """
                    <a href="%1$s/api/booking/current">Current Bookings</a>
                    | <a href="%1$s/api/booking/bookings">Bookings</a>
                    | <a href="%1$s/api/booking/duration">Duration Details</a>
                    | <a href="%1$s/api/booking/payment-history">Payment History</a>
                    """.formatted(base);
        } else {
            links = """
                    <a href="%1$s/api/booking/create">Book Vehicle</a>
                    | <a href="%1$s/api/booking/checkout">Check Out</a>
                    | <a href="%1$s/api/booking/current">Current Bookings</a>
                    | <a href="%1$s/api/booking/bookings">Bookings</a>
                    | <a href="%1$s/api/booking/duration">Duration Details</a>
                    | <a href="%1$s/api/booking/payment-history">Payment History</a>
                    """.formatted(base);
        }

        return page(base, "Booking", """
                <h2>Booking</h2>
                <p>%s</p>
                """.formatted(links));
    }

    @GET
    @Path("create")
    @RolesAllowed("TICKET_ASSIGNER")
    public Response createBookingForm(@Context HttpServletRequest request) {
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            return page(base, "Book Vehicle", """
                    <h2>Book Vehicle</h2>
                    <form method="post" action="%1$s/api/booking/create">
                        <label for="vehicleTypeId">Vehicle Type</label><br>
                        <select id="vehicleTypeId" name="vehicleTypeId" required>
                            %2$s
                        </select><br><br>
                        <label for="vehicleNumber">Vehicle Number</label><br>
                        <input type="text" id="vehicleNumber" name="vehicleNumber" maxlength="50" required><br><br>
                        <label for="phoneNumber">Phone Number</label><br>
                        <input type="text" id="phoneNumber" name="phoneNumber" maxlength="10" required><br><br>
                        <label for="color">Vehicle Color</label><br>
                        <input type="text" id="color" name="color" maxlength="50"><br><br>
                        <button type="submit">Book Slot</button>
                    </form>
                    <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                    """.formatted(base, vehicleTypeOptions(connection)));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load booking form.", exception);
            return error(base, "Unable to load booking form.");
        }
    }

    @POST
    @Path("create")
    @RolesAllowed("TICKET_ASSIGNER")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createBooking(
            @FormParam("vehicleTypeId") int vehicleTypeId,
            @FormParam("vehicleNumber") String vehicleNumber,
            @FormParam("phoneNumber") String phoneNumber,
            @FormParam("color") String color,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (vehicleTypeId <= 0 || blank(vehicleNumber) || blank(phoneNumber) || blank(color)) {
            return error(base, "Vehicle type, vehicle number, phone number, and color are required.");
        }

        String normalizedVehicle = normalizeVehicle(vehicleNumber);
        String normalizedPhone = phoneNumber.trim();
        String enteredColor = color.trim();
        if (normalizedVehicle.isEmpty()) {
            return error(base, "Vehicle number is required.");
        }

        String activeVehicleSql = """
            SELECT 1
            FROM booking b
            LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
            WHERE da.booking_id IS NULL
              AND b.vehicle_number = ?
            LIMIT 1
            """;
        String findSlotSql = """
            SELECT s.slot_id, f.floor_name, s.slot_name
            FROM slot s
            JOIN floor f ON f.floor_id = s.floor_id
            WHERE f.vehicle_type_id = ?
              AND f.status = TRUE
              AND s.state = 'AVAILABLE'
              AND NOT EXISTS (
                  SELECT 1 FROM floor_block fb
                  WHERE fb.floor_id = f.floor_id
                    AND fb.date_end IS NULL
              )
              AND NOT EXISTS (
                  SELECT 1 FROM slot_block sb
                  WHERE sb.slot_id = s.slot_id
                    AND sb.date_end IS NULL
              )
            ORDER BY f.floor_id ASC, s.slot_id ASC
            FOR UPDATE OF s
            LIMIT 1
            """;
        String vehicleDetailsLookupSql =
                "SELECT color FROM vehicle_details WHERE vehicle_number = ? AND phone_number = ?";
        String vehicleDetailsInsertSql =
                "INSERT INTO vehicle_details (vehicle_number, phone_number, color) VALUES (?, ?, ?)";
        String vehicleDetailsUpdateColorSql =
                "UPDATE vehicle_details SET color = ? WHERE vehicle_number = ? AND phone_number = ?";
        String insertBookingSql = """
            INSERT INTO booking (slot_id, vehicle_number, start_time, phone_number)
            VALUES (?, ?, ?, ?)
            """;
        String updateSlotSql = "UPDATE slot SET state = 'OCCUPIED' WHERE slot_id = ?";

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(activeVehicleSql)) {
                    statement.setString(1, normalizedVehicle);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            connection.rollback();
                            return error(base, Response.Status.CONFLICT,
                                    "This vehicle already has an active booking.");
                        }
                    }
                }

                long slotId;
                String floorName;
                String slotName;
                try (PreparedStatement statement = connection.prepareStatement(findSlotSql)) {
                    statement.setInt(1, vehicleTypeId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return error(base, Response.Status.CONFLICT,
                                    "No available slots for the selected vehicle type currently.");
                        }
                        slotId = result.getLong("slot_id");
                        floorName = result.getString("floor_name");
                        slotName = result.getString("slot_name");
                    }
                }

                String resolvedColor;
                try (PreparedStatement statement = connection.prepareStatement(vehicleDetailsLookupSql)) {
                    statement.setString(1, normalizedVehicle);
                    statement.setString(2, normalizedPhone);
                    try (ResultSet result = statement.executeQuery()) {
                        if (result.next()) {
                            String storedColor = result.getString("color");
                            if (storedColor != null
                                    && storedColor.trim().equalsIgnoreCase(enteredColor)) {
                                resolvedColor = storedColor;
                            } else {
                                try (PreparedStatement updateStatement =
                                             connection.prepareStatement(vehicleDetailsUpdateColorSql)) {
                                    updateStatement.setString(1, enteredColor);
                                    updateStatement.setString(2, normalizedVehicle);
                                    updateStatement.setString(3, normalizedPhone);
                                    updateStatement.executeUpdate();
                                }
                                resolvedColor = enteredColor;
                            }
                        } else {
                            try (PreparedStatement insertStatement =
                                         connection.prepareStatement(vehicleDetailsInsertSql)) {
                                insertStatement.setString(1, normalizedVehicle);
                                insertStatement.setString(2, normalizedPhone);
                                insertStatement.setString(3, enteredColor);
                                insertStatement.executeUpdate();
                            }
                            resolvedColor = enteredColor;
                        }
                    }
                }

                LocalDateTime startTime = LocalDateTime.now();
                long bookingId;
                try (PreparedStatement statement = connection.prepareStatement(
                        insertBookingSql, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setLong(1, slotId);
                    statement.setString(2, normalizedVehicle);
                    statement.setTimestamp(3, Timestamp.valueOf(startTime));
                    statement.setString(4, normalizedPhone);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("Booking ID was not generated.");
                        }
                        bookingId = keys.getLong(1);
                    }
                }

                try (PreparedStatement statement = connection.prepareStatement(updateSlotSql)) {
                    statement.setLong(1, slotId);
                    statement.executeUpdate();
                }

                connection.commit();
                WhatsAppNotificationService.sendBookingConfirmation(bookingId);

                String content = """
                    <h2>Booking Successful</h2>
                    <p><strong>Vehicle:</strong> %s</p>
                    <p><strong>Color:</strong> %s</p>
                    <p><strong>Floor:</strong> %s</p>
                    <p><strong>Slot:</strong> %s</p>
                    <p>Direct the vehicle to the above floor and slot.</p>
                    <p><a href="%s/api/booking/create">Book Another Vehicle</a>
                       | <a href="%s/api/booking">Booking Dashboard</a></p>
                    """.formatted(escape(normalizedVehicle), escape(resolvedColor),
                        escape(floorName), escape(slotName), base, base);
                return page(base, "Booking Successful", content);
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | RuntimeException exception) {
            LOGGER.log(Level.SEVERE, "Unable to create booking.", exception);
            if (exception instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return error(base, Response.Status.CONFLICT, "The vehicle or slot is already booked.");
            }
            return error(base, "Unable to create booking.");
        }
    }

    @GET
    @Path("checkout")
    @RolesAllowed("TICKET_ASSIGNER")
    public Response checkout(
            @QueryParam("vehicleNumber") String vehicleNumber,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(vehicleNumber)) {
            return page(base, "Check Out", """
                    <h2>Check Out</h2>
                    <form method="get" action="%1$s/api/booking/checkout">
                        <label for="vehicleNumber">Vehicle Number</label><br>
                        <input type="text" id="vehicleNumber" name="vehicleNumber" maxlength="50" required><br><br>
                        <button type="submit">Find Vehicle</button>
                    </form>
                    <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                    """.formatted(base));
        }

        String normalizedVehicle = normalizeVehicle(vehicleNumber);
        String sql = """
                SELECT b.booking_id, b.vehicle_number, b.phone_number, b.start_time,
                       s.slot_name, f.floor_name, f.vehicle_type_id,
                       vd.color,
                       CEIL(GREATEST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - b.start_time)) / 60, 1))::bigint AS minutes
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN vehicle_details vd
                       ON vd.vehicle_number = b.vehicle_number AND vd.phone_number = b.phone_number
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                WHERE b.vehicle_number = ?
                  AND da.booking_id IS NULL
                ORDER BY b.start_time DESC
                LIMIT 1
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, normalizedVehicle);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "No vehicle with this number exists.");
                }

                long bookingId = result.getLong("booking_id");
                int vehicleTypeId = result.getInt("vehicle_type_id");
                long minutes = result.getLong("minutes");
                BigDecimal amount = calculateAmount(connection, vehicleTypeId, minutes);

                String content = """
                        <h2>Check Out</h2>
                        <p><strong>Vehicle:</strong> %s</p>
                        <p><strong>Color:</strong> %s</p>
                        <p><strong>Phone Number:</strong> %s</p>
                        <p><strong>Floor:</strong> %s</p>
                        <p><strong>Slot:</strong> %s</p>
                        <p><strong>Start Time:</strong> %s</p>
                        <p><strong>Duration (minutes):</strong> %d</p>
                        <p><strong>Amount:</strong> %s</p>
                        <form method="post" action="%s/api/booking/checkout/%d/pay">
                            <label for="paymentMethod">Payment Method</label><br>
                            <select id="paymentMethod" name="paymentMethod" required>
                                <option value="CASH">CASH</option>
                                <option value="UPI">UPI</option>
                                <option value="CARD">CARD</option>
                                <option value="WALLET">WALLET</option>
                            </select><br><br>
                            <button type="submit">Pay</button>
                        </form>
                        <p><a href="%s/api/booking/checkout">Search Another Vehicle</a></p>
                        """.formatted(
                        escape(result.getString("vehicle_number")),
                        escape(result.getString("color") == null ? "-" : result.getString("color")),
                        escape(result.getString("phone_number")),
                        escape(result.getString("floor_name")),
                        escape(result.getString("slot_name")),
                        escape(String.valueOf(result.getTimestamp("start_time"))),
                        minutes,
                        amount,
                        base,
                        bookingId,
                        base);
                return page(base, "Check Out", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load checkout details.", exception);
            return error(base, "Unable to load checkout details.");
        }
    }

    @POST
    @Path("checkout/{bookingId}/pay")
    @RolesAllowed("TICKET_ASSIGNER")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response payCheckout(
            @PathParam("bookingId") long bookingId,
            @FormParam("paymentMethod") String paymentMethod,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (!Set.of("CASH", "UPI", "CARD", "WALLET").contains(paymentMethod)) {
            return error(base, "A valid payment method is required.");
        }

        String bookingSql = """
                SELECT b.start_time, f.vehicle_type_id
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                WHERE b.booking_id = ? AND da.booking_id IS NULL
                FOR UPDATE OF b
                """;
        String slotIdSql = "SELECT slot_id FROM booking WHERE booking_id = ?";
        String durationSql = """
                INSERT INTO duration_amount (booking_id, end_time, total_duration, amount)
                VALUES (?, CURRENT_TIMESTAMP, ?, ?)
                """;
        String paymentSql = """
                INSERT INTO payment (booking_id, payment_method, verified_by, payment_status)
                VALUES (?, ?, ?, 'SUCCESS')
                """;
        String updateSlotSql = "UPDATE slot SET state = 'AVAILABLE' WHERE slot_id = ?";

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Timestamp startTime;
                int vehicleTypeId;
                try (PreparedStatement statement = connection.prepareStatement(bookingSql)) {
                    statement.setLong(1, bookingId);
                    try (ResultSet result = statement.executeQuery()) {
                        if (!result.next()) {
                            connection.rollback();
                            return error(base, Response.Status.CONFLICT,
                                    "This booking is no longer active or does not exist.");
                        }
                        startTime = result.getTimestamp("start_time");
                        vehicleTypeId = result.getInt("vehicle_type_id");
                    }
                }

                long minutes;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT CEIL(GREATEST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - ?)) / 60, 1))::bigint AS minutes")) {
                    statement.setTimestamp(1, startTime);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        minutes = result.getLong("minutes");
                    }
                }
                BigDecimal amount = calculateAmount(connection, vehicleTypeId, minutes);

                try (PreparedStatement statement = connection.prepareStatement(durationSql)) {
                    statement.setLong(1, bookingId);
                    statement.setLong(2, minutes);
                    statement.setBigDecimal(3, amount);
                    statement.executeUpdate();
                }

                try (PreparedStatement statement = connection.prepareStatement(paymentSql)) {
                    statement.setLong(1, bookingId);
                    statement.setString(2, paymentMethod);
                    statement.setLong(3, currentUserId(securityContext));
                    statement.executeUpdate();
                }

                long slotId;
                try (PreparedStatement statement = connection.prepareStatement(slotIdSql)) {
                    statement.setLong(1, bookingId);
                    try (ResultSet result = statement.executeQuery()) {
                        result.next();
                        slotId = result.getLong("slot_id");
                    }
                }
                try (PreparedStatement statement = connection.prepareStatement(updateSlotSql)) {
                    statement.setLong(1, slotId);
                    statement.executeUpdate();
                }

                connection.commit();
                WhatsAppNotificationService.sendCheckoutConfirmation(bookingId);
                return redirect(base + "/api/booking/bookings");
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to process checkout payment.", exception);
            return error(base, "Unable to process checkout payment.");
        }
    }

    @GET
    @Path("current")
    public Response currentBookings(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Current Bookings</h2>
                <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                <table border="1">
                    <tr><th>Vehicle</th><th>Phone</th><th>Floor</th><th>Slot</th><th>Start Time</th></tr>
                """.formatted(base));
        String sql = """
                SELECT b.vehicle_number, b.phone_number, b.start_time,
                       s.slot_name, f.floor_name
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                WHERE da.booking_id IS NULL
                ORDER BY b.start_time DESC
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("vehicle_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("phone_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("slot_name")))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getTimestamp("start_time"))))
                        .append("</td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load current bookings.", exception);
            return error(base, "Unable to load current bookings.");
        }
        content.append("</table>");
        return page(base, "Current Bookings", content.toString());
    }

    @GET
    @Path("payment-history")
    public Response paymentHistory(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Payment History</h2>
                <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                <table border="1">
                    <tr><th>Vehicle</th><th>Floor</th><th>Slot</th><th>Amount</th><th>Method</th><th>Status</th><th>Verified By</th></tr>
                """.formatted(base));
        String sql = """
                SELECT b.vehicle_number, f.floor_name, s.slot_name,
                       da.amount, p.payment_method, p.payment_status,
                       p.verified_by
                FROM payment p
                JOIN booking b ON b.booking_id = p.booking_id
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                ORDER BY p.payment_id DESC
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("vehicle_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("slot_name")))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getBigDecimal("amount"))))
                        .append("</td><td>")
                        .append(escape(result.getString("payment_method")))
                        .append("</td><td>")
                        .append(escape(result.getString("payment_status")))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getObject("verified_by"))))
                        .append("</td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load payment history.", exception);
            return error(base, "Unable to load payment history.");
        }
        content.append("</table>");
        return page(base, "Payment History", content.toString());
    }

    @GET
    @Path("bookings")
    public Response bookings(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Bookings</h2>
                <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                <table border="1">
                    <tr><th>Vehicle</th><th>Phone</th><th>Floor</th><th>Slot</th><th>Start Time</th><th>Status</th></tr>
                """.formatted(base));

        String sql = """
                SELECT b.vehicle_number, b.phone_number, b.start_time,
                       s.slot_name, f.floor_name,
                       da.end_time, p.payment_status
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                LEFT JOIN payment p ON p.booking_id = b.booking_id
                ORDER BY b.start_time DESC
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                boolean active = result.getTimestamp("end_time") == null;
                content.append("<tr><td>")
                        .append(escape(result.getString("vehicle_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("phone_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("slot_name")))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getTimestamp("start_time"))))
                        .append("</td><td>")
                        .append(active ? "ACTIVE" : "COMPLETED")
                        .append("</td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load bookings.", exception);
            return error(base, "Unable to load bookings.");
        }

        content.append("</table>");
        return page(base, "Bookings", content.toString());
    }

    @GET
    @Path("duration")
    public Response duration(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Duration Details</h2>
                <p><a href="%1$s/api/booking">Booking Dashboard</a></p>
                <table border="1">
                    <tr><th>Vehicle</th><th>Floor</th><th>Slot</th><th>Start Time</th><th>End Time</th><th>Duration (min)</th><th>Amount</th></tr>
                """.formatted(base));

        String sql = """
                SELECT b.vehicle_number, b.start_time,
                       s.slot_name, f.floor_name,
                       da.end_time, da.total_duration, da.amount
                FROM booking b
                JOIN slot s ON s.slot_id = b.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                LEFT JOIN duration_amount da ON da.booking_id = b.booking_id
                ORDER BY b.start_time DESC
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("vehicle_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("slot_name")))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getTimestamp("start_time"))))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getTimestamp("end_time"))))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getBigDecimal("total_duration"))))
                        .append("</td><td>")
                        .append(escape(String.valueOf(result.getBigDecimal("amount"))))
                        .append("</td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load duration details.", exception);
            return error(base, "Unable to load duration details.");
        }

        content.append("</table>");
        return page(base, "Duration Details", content.toString());
    }

    private BigDecimal calculateAmount(Connection connection, int vehicleTypeId, long totalMinutes)
            throws SQLException {
        List<RateTier> tiers = new ArrayList<>();
        String sql = """
                SELECT duration_limit, rate_per_minute
                FROM vehicle_rate
                WHERE vehicle_type_id = ?
                ORDER BY duration_limit ASC
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, vehicleTypeId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    tiers.add(new RateTier(
                            result.getInt("duration_limit"),
                            result.getBigDecimal("rate_per_minute")));
                }
            }
        }
        if (tiers.isEmpty()) {
            throw new SQLException("No rate configured for vehicle type " + vehicleTypeId);
        }

        BigDecimal amount = BigDecimal.ZERO;
        long remaining = totalMinutes;
        int previousLimit = 0;
        for (RateTier tier : tiers) {
            if (remaining <= 0) {
                break;
            }
            int segmentSize = tier.durationLimit() - previousLimit;
            long minutesInSegment = Math.min(remaining, segmentSize);
            amount = amount.add(tier.ratePerMinute().multiply(BigDecimal.valueOf(minutesInSegment)));
            remaining -= minutesInSegment;
            previousLimit = tier.durationLimit();
        }
        if (remaining > 0) {
            RateTier lastTier = tiers.get(tiers.size() - 1);
            amount = amount.add(lastTier.ratePerMinute().multiply(BigDecimal.valueOf(remaining)));
        }
        return amount;
    }

    private boolean isAdmin(SecurityContext securityContext) {
        return securityContext != null && securityContext.isUserInRole("ADMIN");
    }

    private long currentUserId(SecurityContext securityContext) {
        if (securityContext == null || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated user is required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
    }

    private String normalizeVehicle(String value) {
        return value.trim()
                .replaceAll("[\\s-]+", "")
                .toUpperCase();
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    // NEW: resolves the app's absolute public base URL.
    // Prefers the APP_BASE_URL environment variable (correct on Catalyst,
    // where Jetty sits behind a proxy and misreports scheme/host/context path).
    // Falls back to request.getContextPath() only if the env var isn't set
    // (keeps local Tomcat testing working unchanged).
    private String base(HttpServletRequest request) {
        if (!blank(APP_BASE_URL)) {
            return APP_BASE_URL;
        }
        return request.getContextPath();
    }

    private static String setting(String environmentName) {
        return System.getenv("");
    }

    private Response page(String base, String title, String content) {
        String html = """
                <!doctype html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <title>Parking Management System - %s</title>
                </head>
                <body>
                    <h1>Parking Management System</h1>
                    %s
                    <hr>
                    <form method="post" action="%s/api/auth/logout">
                        <button type="submit">Logout</button>
                    </form>
                </body>
                </html>
                """.formatted(escape(title), content, base);
        return Response.ok(html).build();
    }

    private Response error(String base, String message) {
        return error(base, Response.Status.BAD_REQUEST, message);
    }

    private Response error(String base, Response.Status status, String message) {
        return Response.status(status)
                .entity(pageHtml(base, "Error", "<p>" + escape(message) + "</p>"
                        + "<p><a href=\"" + base + "/api/booking\">Booking Dashboard</a></p>"))
                .build();
    }

    private String pageHtml(String base, String title, String content) {
        return page(base, title, content).getEntity().toString();
    }

    private Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }

    private String escape(String value) {
        return value == null
                ? ""
                : value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String vehicleTypeOptions(Connection connection) throws SQLException {
        StringBuilder options = new StringBuilder();
        String sql = "SELECT vehicle_type_id, type_name FROM vehicle_type ORDER BY type_name";

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                options.append("<option value=\"")
                        .append(result.getLong("vehicle_type_id"))
                        .append("\">")
                        .append(escape(result.getString("type_name")))
                        .append("</option>");
            }
        }
        return options.toString();
    }

    private record RateTier(int durationLimit, BigDecimal ratePerMinute) {
    }
}