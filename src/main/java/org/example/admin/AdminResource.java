package org.example.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import org.example.config.DatabaseConnection;

import java.math.BigDecimal;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("admin")
@RolesAllowed("ADMIN")
@Produces(MediaType.TEXT_HTML)
public class AdminResource {

    private static final Logger LOGGER =
            Logger.getLogger(AdminResource.class.getName());
    private static final String APP_BASE_URL =
            setting("APP_BASE_URL", "app.base.url");

    @GET
    public Response dashboard(@Context HttpServletRequest request) {
        String base = base(request);
        return page(base, "Admin Dashboard", """
                <h2>Admin Dashboard</h2>
                <p>Manage parking floors and slots.</p>
                <p>
                    <a href="%1$s/api/admin/floors">View Floors</a>
                    |
                    <a href="%1$s/api/admin/slots">View Slots</a>
                    |
                    <a href="%1$s/api/admin/users">View Users</a>
                    |
                    <a href="%1$s/api/booking/payment-history">Payment History</a>
                    |
                    <a href="%1$s/api/admin/email-setup">Email Setup</a>
                    |
                    <a href="%1$s/api/admin/facebook-setup">Facebook / WhatsApp Setup</a>
                    |
                    <a href="%1$s/api/auth/totp">Security / 2FA</a>
                </p>
                <p>
                    <a href="%1$s/api/admin/floors/create">Create Floor</a>
                    |
                    <a href="%1$s/api/admin/slots/create">Create Slot</a>
                    |
                    <a href="%1$s/api/admin/floor-blocks/create">Block Floor</a>
                    |
                    <a href="%1$s/api/admin/slot-blocks/create">Block Slot</a>
                </p>
                <p>
                    <a href="%1$s/api/admin/floor-blocks/history">Floor Block History</a>
                    |
                    <a href="%1$s/api/admin/slot-blocks/history">Slot Block History</a>
                    |
                    <a href="%1$s/api/admin/floor-blocks/current">Current Blocked Floors</a>
                    |
                    <a href="%1$s/api/admin/slot-blocks/current">Current Blocked Slots</a>
                </p>
                <p>
                    <a href="%1$s/api/admin/vehicle-types">View Vehicle Types</a>
                    |
                    <a href="%1$s/api/admin/vehicle-types/create">Create Vehicle Type</a>
                    |
                    <a href="%1$s/api/admin/vehicle-rates">View Vehicle Rates</a>
                    |
                    <a href="%1$s/api/admin/vehicle-rates/create">Create Vehicle Rate</a>
                </p>
                """.formatted(base));
    }

    @GET
    @Path("floor-blocks/create")
    public Response createFloorBlockForm(@Context HttpServletRequest request) {
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            return page(base, "Block Floor", """
                    <h2>Block Floor</h2>
                    <form method="post" action="%1$s/api/admin/floor-blocks/create">
                        <label for="floorId">Floor</label><br>
                        <select id="floorId" name="floorId" required>%2$s</select><br><br>
                        <label for="reason">Reason</label><br>
                        <textarea id="reason" name="reason" maxlength="500" required></textarea><br><br>
                        <p>Start time is recorded automatically.</p>
                        <button type="submit">Block Floor</button>
                    </form>
                    <p><a href="%1$s/api/admin">Cancel</a></p>
                    """.formatted(base, floorOptions(connection)));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load floor block form.", exception);
            return error(base, "Unable to load floor block form.");
        }
    }

    @POST
    @Path("floor-blocks/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createFloorBlock(
            @FormParam("floorId") long floorId,
            @FormParam("reason") String reason,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (floorId <= 0 || blank(reason)) {
            return error(base, "Floor and reason are required.");
        }
        String sql = """
                INSERT INTO floor_block (user_id, floor_id, date_start, reason)
                SELECT ?, f.floor_id, CURRENT_TIMESTAMP, ?
                FROM floor f
                WHERE f.floor_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM floor_block fb
                      WHERE fb.floor_id = f.floor_id
                        AND fb.date_start <= CURRENT_TIMESTAMP
                        AND fb.date_end IS NULL
                  )
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, currentUserId(securityContext));
            statement.setString(2, reason.trim());
            statement.setLong(3, floorId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.CONFLICT,
                        "The selected floor is already currently blocked.");
            }
            return redirect(base + "/api/admin/floor-blocks/current");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to block floor.", exception);
            return error(base, databaseMessage("block floor", exception));
        }
    }

    @GET
    @Path("slot-blocks/create")
    public Response createSlotBlockForm(@Context HttpServletRequest request) {
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            return page(base, "Block Slot", """
                    <h2>Block Slot</h2>
                    <form method="post" action="%1$s/api/admin/slot-blocks/create">
                        <label for="slotId">Slot</label><br>
                        <select id="slotId" name="slotId" required>%2$s</select><br><br>
                        <label for="reason">Reason</label><br>
                        <textarea id="reason" name="reason" maxlength="500" required></textarea><br><br>
                        <p>Start time is recorded automatically.</p>
                        <button type="submit">Block Slot</button>
                    </form>
                    <p><a href="%1$s/api/admin">Cancel</a></p>
                    """.formatted(base, slotOptions(connection)));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load slot block form.", exception);
            return error(base, "Unable to load slot block form.");
        }
    }

    @POST
    @Path("slot-blocks/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createSlotBlock(
            @FormParam("slotId") long slotId,
            @FormParam("reason") String reason,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (slotId <= 0 || blank(reason)) {
            return error(base, "Slot and reason are required.");
        }
        String sql = """
                INSERT INTO slot_block (user_id, slot_id, date_start, reason)
                SELECT ?, s.slot_id, CURRENT_TIMESTAMP, ?
                FROM slot s
                WHERE s.slot_id = ?
                  AND NOT EXISTS (
                      SELECT 1
                      FROM slot_block sb
                      WHERE sb.slot_id = s.slot_id
                        AND sb.date_start <= CURRENT_TIMESTAMP
                        AND sb.date_end IS NULL
                  )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM floor_block fb
                      WHERE fb.floor_id = s.floor_id
                        AND fb.date_start <= CURRENT_TIMESTAMP
                        AND fb.date_end IS NULL
                  )
                """;
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, currentUserId(securityContext));
            statement.setString(2, reason.trim());
            statement.setLong(3, slotId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.CONFLICT,
                        "The selected slot is already blocked or belongs to a currently blocked floor.");
            }
            return redirect(base + "/api/admin/slot-blocks/current");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to block slot.", exception);
            return error(base, databaseMessage("block slot", exception));
        }
    }

    @POST
    @Path("floor-blocks/{blockId}/unblock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response unblockFloor(
            @PathParam("blockId") long blockId,
            @Context HttpServletRequest request) {
        return finishBlock("floor_block", blockId, "floor-blocks/current", base(request));
    }

    @POST
    @Path("slot-blocks/{blockId}/unblock")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response unblockSlot(
            @PathParam("blockId") long blockId,
            @Context HttpServletRequest request) {
        return finishBlock("slot_block", blockId, "slot-blocks/current", base(request));
    }

    @GET
    @Path("floor-blocks/history")
    public Response floorBlockHistory(@Context HttpServletRequest request) {
        return blockList("Floor Block History", """
                SELECT fb.block_id, f.floor_name, fb.date_start, fb.date_end,
                       fb.reason, fb.user_id
                FROM floor_block fb
                JOIN floor f ON f.floor_id = fb.floor_id
                WHERE fb.date_end IS NOT NULL
                ORDER BY fb.date_start DESC
                """, false, false, base(request));
    }

    @GET
    @Path("slot-blocks/history")
    public Response slotBlockHistory(@Context HttpServletRequest request) {
        return blockList("Slot Block History", """
                SELECT sb.block_id, f.floor_name, s.slot_name,
                       sb.date_start, sb.date_end, sb.reason, sb.user_id
                FROM slot_block sb
                JOIN slot s ON s.slot_id = sb.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                WHERE sb.date_end IS NOT NULL
                ORDER BY sb.date_start DESC
                """, true, false, base(request));
    }

    @GET
    @Path("floor-blocks/current")
    public Response currentFloorBlocks(@Context HttpServletRequest request) {
        return blockList("Current Blocked Floors", """
                SELECT fb.block_id, f.floor_name, fb.date_start, fb.date_end,
                       fb.reason, fb.user_id
                FROM floor_block fb
                JOIN floor f ON f.floor_id = fb.floor_id
                WHERE fb.date_end IS NULL
                ORDER BY fb.date_start DESC
                """, false, true, base(request));
    }

    @GET
    @Path("slot-blocks/current")
    public Response currentSlotBlocks(@Context HttpServletRequest request) {
        return blockList("Current Blocked Slots", """
                SELECT sb.block_id, f.floor_name, s.slot_name,
                       sb.date_start, sb.date_end, sb.reason, sb.user_id
                FROM slot_block sb
                JOIN slot s ON s.slot_id = sb.slot_id
                JOIN floor f ON f.floor_id = s.floor_id
                WHERE sb.date_end IS NULL
                ORDER BY sb.date_start DESC
                """, true, true, base(request));
    }

    @GET
    @Path("floors")
    public Response floors(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
            <h2>Floors</h2>
            <p>
                <a href="%1$s/api/admin">Admin Dashboard</a>
                |
                <a href="%1$s/api/admin/floors/create">Create Floor</a>
            </p>
            <table border="1">
                <tr><th>Floor Name</th><th>Vehicle Type</th><th>Status</th><th>Created By</th><th>Action</th></tr>
            """.formatted(base));

        String sql = """
            SELECT f.floor_id, f.floor_name, f.status, f.created_by, vt.type_name,
                   EXISTS (
                       SELECT 1
                       FROM floor_block fb
                       WHERE fb.floor_id = f.floor_id
                         AND fb.date_start <= CURRENT_TIMESTAMP
                         AND fb.date_end IS NULL
                   ) AS blocked
            FROM floor f
            JOIN vehicle_type vt ON vt.vehicle_type_id = f.vehicle_type_id
            ORDER BY f.floor_name;
            """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getLong("floor_id");
                boolean blocked = result.getBoolean("blocked");
                content.append("<tr><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("type_name")))
                        .append("</td><td>")
                        .append(blocked ? "BLOCKED" : result.getBoolean("status")
                                ? "ACTIVE" : "INACTIVE")
                        .append("</td><td>")
                        .append(result.getLong("created_by"))
                        .append("</td><td><a href=\"")
                        .append(base)
                        .append("/api/admin/floors/")
                        .append(id)
                        .append("/edit\">Edit</a></td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load floors.", exception);
            return error(base, "Unable to load floors.");
        }

        content.append("</table>");
        return page(base, "Floors", content.toString());
    }

    @GET
    @Path("floors/create")
    public Response createFloorForm(@Context HttpServletRequest request) {
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            return page(base, "Create Floor", """
                    <h2>Create Floor</h2>
                    <form method="post" action="%1$s/api/admin/floors/create">
                        <label for="floorName">Floor Name</label><br>
                        <input type="text" id="floorName" name="floorName" maxlength="50" required><br><br>
                        <label for="vehicleTypeId">Vehicle Type</label><br>
                        <select id="vehicleTypeId" name="vehicleTypeId" required>%2$s</select><br><br>
                        <p>Status will be set to ACTIVE automatically.</p>
                        <button type="submit">Create Floor</button>
                    </form>
                    <p><a href="%1$s/api/admin/floors">Cancel</a></p>
                    """.formatted(base, vehicleTypeOptions(connection, null)));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load create floor form.", exception);
            return error(base, "Unable to load create floor form.");
        }
    }

    @POST
    @Path("floors/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createFloor(
            @FormParam("floorName") String floorName,
            @FormParam("vehicleTypeId") int vehicleTypeId,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(floorName) || vehicleTypeId <= 0) {
            return error(base, "Floor name and a vehicle type are required.");
        }

        String sql = """
                INSERT INTO floor (floor_name, status, created_by, vehicle_type_id)
                VALUES (?, TRUE, ?, ?)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, floorName.trim());
            statement.setLong(2, currentUserId(securityContext));
            statement.setInt(3, vehicleTypeId);
            statement.executeUpdate();
            return redirect(base + "/api/admin/floors");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to create floor.", exception);
            return error(base, databaseMessage("create floor", exception));
        }
    }

    @GET
    @Path("floors/{floorId}/edit")
    public Response editFloorForm(
            @PathParam("floorId") long floorId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String sql = "SELECT floor_name, status, vehicle_type_id FROM floor WHERE floor_id = ?";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, floorId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "Floor not found.");
                }

                String content = """
                        <h2>Update Floor</h2>
                        <form method="post" action="%1$s/api/admin/floors/%2$d/edit">
                            <label for="floorName">Floor Name</label><br>
                            <input type="text" id="floorName" name="floorName" maxlength="50" value="%3$s" required><br><br>
                            <label for="vehicleTypeId">Vehicle Type</label><br>
                            <select id="vehicleTypeId" name="vehicleTypeId" required>%4$s</select><br><br>
                            <button type="submit">Update Floor</button>
                        </form>
                        <p><a href="%1$s/api/admin/floors">Cancel</a></p>
                        """.formatted(
                        base,
                        floorId,
                        escapeAttribute(result.getString("floor_name")),
                        vehicleTypeOptions(connection, (long) result.getInt("vehicle_type_id"))
                );
                return page(base, "Update Floor", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load floor.", exception);
            return error(base, "Unable to load floor.");
        }
    }

    @POST
    @Path("floors/{floorId}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateFloor(
            @PathParam("floorId") long floorId,
            @FormParam("floorName") String floorName,
            @FormParam("vehicleTypeId") int vehicleTypeId,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(floorName) || vehicleTypeId <= 0) {
            return error(base, "Floor name and a vehicle type are required.");
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            String sql = """
                UPDATE floor
                SET floor_name = ?, vehicle_type_id = ?
                WHERE floor_id = ?
                """;

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, floorName.trim());
                statement.setInt(2, vehicleTypeId);
                statement.setLong(3, floorId);
                if (statement.executeUpdate() == 0) {
                    return error(base, Response.Status.NOT_FOUND, "Floor not found.");
                }
                return redirect(base + "/api/admin/floors");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update floor.", exception);
            return error(base, databaseMessage("update floor", exception));
        }
    }

    @GET
    @Path("slots")
    public Response slots(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Slots</h2>
                <p>
                    <a href="%1$s/api/admin">Admin Dashboard</a>
                    |
                    <a href="%1$s/api/admin/slots/create">Create Slot</a>
                </p>
                <table border="1">
                    <tr><th>Slot Name</th><th>State</th><th>Floor</th><th>Created By</th><th>Action</th></tr>
                """.formatted(base));

        String sql = """
                SELECT s.slot_id, s.slot_name, s.state, f.floor_name, s.created_by,
                       CASE
                           WHEN EXISTS (
                               SELECT 1
                               FROM floor_block fb
                               WHERE fb.floor_id = s.floor_id
                                 AND fb.date_start <= CURRENT_TIMESTAMP
                                 AND fb.date_end IS NULL
                           )
                           OR EXISTS (
                               SELECT 1
                               FROM slot_block sb
                               WHERE sb.slot_id = s.slot_id
                                 AND sb.date_start <= CURRENT_TIMESTAMP
                                 AND sb.date_end IS NULL
                           )
                           THEN 'BLOCKED'
                           ELSE s.state
                       END AS effective_state
                FROM slot s
                JOIN floor f ON f.floor_id = s.floor_id
                ORDER BY f.floor_name, s.slot_name
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("slot_name")))
                        .append("</td><td>")
                        .append(escape(result.getString("effective_state")))
                        .append("</td><td>")
                        .append(escape(result.getString("floor_name")))
                        .append("</td><td>")
                        .append(result.getLong("created_by"))
                        .append("</td><td><a href=\"")
                        .append(base)
                        .append("/api/admin/slots/")
                        .append(result.getLong("slot_id"))
                        .append("/edit\">Edit</a></td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load slots.", exception);
            return error(base, "Unable to load slots.");
        }

        content.append("</table>");
        return page(base, "Slots", content.toString());
    }

    @GET
    @Path("slots/create")
    public Response createSlotForm(@Context HttpServletRequest request) {
        String base = base(request);
        return slotForm(
                base,
                "Create Slot",
                base + "/api/admin/slots/create",
                null,
                null,
                true
        );
    }

    @POST
    @Path("slots/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createSlot(
            @FormParam("slotName") String slotName,
            @FormParam("floorId") long floorId,
            @Context SecurityContext securityContext,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(slotName) || floorId <= 0) {
            return error(base, "Slot name and a floor are required.");
        }

        String sql = """
                INSERT INTO slot (slot_name, state, floor_id, created_by)
                VALUES (?, 'AVAILABLE', ?, ?)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slotName.trim());
            statement.setLong(2, floorId);
            statement.setLong(3, currentUserId(securityContext));
            statement.executeUpdate();
            return redirect(base + "/api/admin/slots");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to create slot.", exception);
            return error(base, databaseMessage("create slot", exception));
        }
    }

    @GET
    @Path("slots/{slotId}/edit")
    public Response editSlotForm(
            @PathParam("slotId") long slotId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String sql = "SELECT slot_name, floor_id FROM slot WHERE slot_id = ?";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, slotId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "Slot not found.");
                }
                return slotForm(
                        base,
                        "Update Slot",
                        base + "/api/admin/slots/" + slotId + "/edit",
                        result.getString("slot_name"),
                        result.getLong("floor_id"),
                        false
                );
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load slot.", exception);
            return error(base, "Unable to load slot.");
        }
    }

    @POST
    @Path("slots/{slotId}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateSlot(
            @PathParam("slotId") long slotId,
            @FormParam("slotName") String slotName,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(slotName)) {
            return error(base, "Slot name is required.");
        }

        String sql = """
                UPDATE slot
                SET slot_name = ?
                WHERE slot_id = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, slotName.trim());
            statement.setLong(2, slotId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.NOT_FOUND, "Slot not found.");
            }
            return redirect(base + "/api/admin/slots");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update slot.", exception);
            return error(base, databaseMessage("update slot", exception));
        }
    }

    @GET
    @Path("users")
    public Response users(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Users</h2>
                <p>
                    <a href="%1$s/api/admin">Admin Dashboard</a>
                </p>
                <table border="1">
                    <tr><th>Name</th><th>Email</th><th>Phone Number</th><th>Role</th><th>Action</th></tr>
                """.formatted(base));

        String sql = """
                SELECT u.user_id, u.name, u.email, u.phone_number, r.role_name
                FROM users u
                JOIN roles r ON r.role_id = u.role_id
                ORDER BY u.user_id
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("name")))
                        .append("</td><td>")
                        .append(escape(result.getString("email")))
                        .append("</td><td>")
                        .append(escape(result.getString("phone_number")))
                        .append("</td><td>")
                        .append(escape(result.getString("role_name")))
                        .append("</td><td><a href=\"")
                        .append(base)
                        .append("/api/admin/users/")
                        .append(result.getLong("user_id"))
                        .append("/edit\">Edit Role</a></td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load users.", exception);
            return error(base, "Unable to load users.");
        }

        content.append("</table>");
        return page(base, "Users", content.toString());
    }

    @GET
    @Path("users/{userId}/edit")
    public Response editUserRoleForm(
            @PathParam("userId") long userId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String userSql = """
                SELECT u.name, u.email, u.phone_number, u.role_id, r.role_name
                FROM users u
                JOIN roles r ON r.role_id = u.role_id
                WHERE u.user_id = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement userStatement = connection.prepareStatement(userSql)) {
            userStatement.setLong(1, userId);
            try (ResultSet user = userStatement.executeQuery()) {
                if (!user.next()) {
                    return error(base, Response.Status.NOT_FOUND, "User not found.");
                }

                String content = """
                        <h2>Update User Role</h2>
                        <p><strong>Name:</strong> %s</p>
                        <p><strong>Email:</strong> %s</p>
                        <p><strong>Phone Number:</strong> %s</p>
                        <form method="post" action="%s/api/admin/users/%d/edit">
                            <label for="roleId">Role</label><br>
                            <select id="roleId" name="roleId" required>
                                %s
                            </select><br><br>
                            <button type="submit">Update Role</button>
                        </form>
                        <p><a href="%s/api/admin/users">Cancel</a></p>
                        """.formatted(
                        escape(user.getString("name")),
                        escape(user.getString("email")),
                        escape(user.getString("phone_number")),
                        base,
                        userId,
                        roleOptions(connection, user.getLong("role_id")),
                        base
                );
                return page(base, "Update User Role", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load user role form.", exception);
            return error(base, "Unable to load user role form.");
        }
    }

    @POST
    @Path("users/{userId}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateUserRole(
            @PathParam("userId") long userId,
            @FormParam("roleId") long roleId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String sql = """
                UPDATE users
                SET role_id = ?
                WHERE user_id = ?
                AND ? IN (
                    SELECT role_id
                    FROM roles
                    WHERE role_name IN ('ADMIN', 'USER', 'TICKET_ASSIGNER')
                )
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, roleId);
            statement.setLong(2, userId);
            statement.setLong(3, roleId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.BAD_REQUEST, "User or selected role was not found.");
            }
            return redirect(base + "/api/admin/users");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update user role.", exception);
            return error(base, databaseMessage("update user role", exception));
        }
    }

    @GET
    @Path("vehicle-types")
    public Response vehicleTypes(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Vehicle Types</h2>
                <p>
                    <a href="%1$s/api/admin">Admin Dashboard</a>
                    |
                    <a href="%1$s/api/admin/vehicle-types/create">Create Vehicle Type</a>
                </p>
                <table border="1">
                    <tr><th>Type Name</th><th>Action</th></tr>
                """.formatted(base));

        String sql = "SELECT vehicle_type_id, type_name FROM vehicle_type ORDER BY type_name";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("type_name")))
                        .append("</td><td><a href=\"")
                        .append(base)
                        .append("/api/admin/vehicle-types/")
                        .append(result.getInt("vehicle_type_id"))
                        .append("/edit\">Edit</a></td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load vehicle types.", exception);
            return error(base, "Unable to load vehicle types.");
        }

        content.append("</table>");
        return page(base, "Vehicle Types", content.toString());
    }

    @GET
    @Path("vehicle-types/create")
    public Response createVehicleTypeForm(@Context HttpServletRequest request) {
        String base = base(request);
        return page(base, "Create Vehicle Type", """
                <h2>Create Vehicle Type</h2>
                <form method="post" action="%1$s/api/admin/vehicle-types/create">
                    <label for="typeName">Type Name</label><br>
                    <input type="text" id="typeName" name="typeName" maxlength="20" required><br><br>
                    <button type="submit">Create Vehicle Type</button>
                </form>
                <p><a href="%1$s/api/admin/vehicle-types">Cancel</a></p>
                """.formatted(base));
    }

    @POST
    @Path("vehicle-types/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createVehicleType(
            @FormParam("typeName") String typeName,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(typeName)) {
            return error(base, "Type name is required.");
        }

        String sql = "INSERT INTO vehicle_type (type_name) VALUES (?)";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, typeName.trim());
            statement.executeUpdate();
            return redirect(base + "/api/admin/vehicle-types");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to create vehicle type.", exception);
            return error(base, databaseMessage("create vehicle type", exception));
        }
    }

    @GET
    @Path("vehicle-types/{vehicleTypeId}/edit")
    public Response editVehicleTypeForm(
            @PathParam("vehicleTypeId") int vehicleTypeId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String sql = "SELECT type_name FROM vehicle_type WHERE vehicle_type_id = ?";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, vehicleTypeId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "Vehicle type not found.");
                }

                String content = """
                        <h2>Update Vehicle Type</h2>
                        <form method="post" action="%1$s/api/admin/vehicle-types/%2$d/edit">
                            <label for="typeName">Type Name</label><br>
                            <input type="text" id="typeName" name="typeName" maxlength="20" value="%3$s" required><br><br>
                            <button type="submit">Update Vehicle Type</button>
                        </form>
                        <p><a href="%1$s/api/admin/vehicle-types">Cancel</a></p>
                        """.formatted(
                        base,
                        vehicleTypeId,
                        escapeAttribute(result.getString("type_name"))
                );
                return page(base, "Update Vehicle Type", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load vehicle type.", exception);
            return error(base, "Unable to load vehicle type.");
        }
    }

    @POST
    @Path("vehicle-types/{vehicleTypeId}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateVehicleType(
            @PathParam("vehicleTypeId") int vehicleTypeId,
            @FormParam("typeName") String typeName,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (blank(typeName)) {
            return error(base, "Type name is required.");
        }

        String sql = "UPDATE vehicle_type SET type_name = ? WHERE vehicle_type_id = ?";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, typeName.trim());
            statement.setInt(2, vehicleTypeId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.NOT_FOUND, "Vehicle type not found.");
            }
            return redirect(base + "/api/admin/vehicle-types");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update vehicle type.", exception);
            return error(base, databaseMessage("update vehicle type", exception));
        }
    }

    @GET
    @Path("vehicle-rates")
    public Response vehicleRates(@Context HttpServletRequest request) {
        String base = base(request);
        StringBuilder content = new StringBuilder("""
                <h2>Vehicle Rates</h2>
                <p>
                    <a href="%1$s/api/admin">Admin Dashboard</a>
                    |
                    <a href="%1$s/api/admin/vehicle-rates/create">Create Vehicle Rate</a>
                </p>
                <table border="1">
                    <tr><th>Vehicle Type</th><th>Duration Limit (min)</th><th>Rate Per Minute</th><th>Action</th></tr>
                """.formatted(base));

        String sql = """
                SELECT vr.rate_id, vt.type_name, vr.duration_limit, vr.rate_per_minute
                FROM vehicle_rate vr
                JOIN vehicle_type vt ON vt.vehicle_type_id = vr.vehicle_type_id
                ORDER BY vt.type_name, vr.duration_limit
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr><td>")
                        .append(escape(result.getString("type_name")))
                        .append("</td><td>")
                        .append(result.getInt("duration_limit"))
                        .append("</td><td>")
                        .append(result.getBigDecimal("rate_per_minute"))
                        .append("</td><td><a href=\"")
                        .append(base)
                        .append("/api/admin/vehicle-rates/")
                        .append(result.getLong("rate_id"))
                        .append("/edit\">Edit</a></td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load vehicle rates.", exception);
            return error(base, "Unable to load vehicle rates.");
        }

        content.append("</table>");
        return page(base, "Vehicle Rates", content.toString());
    }

    @GET
    @Path("vehicle-rates/create")
    public Response createVehicleRateForm(@Context HttpServletRequest request) {
        String base = base(request);
        try (Connection connection = DatabaseConnection.getConnection()) {
            return page(base, "Create Vehicle Rate", """
                    <h2>Create Vehicle Rate</h2>
                    <form method="post" action="%1$s/api/admin/vehicle-rates/create">
                        <label for="vehicleTypeId">Vehicle Type</label><br>
                        <select id="vehicleTypeId" name="vehicleTypeId" required>%2$s</select><br><br>
                        <label for="durationLimit">Duration Limit (minutes)</label><br>
                        <input type="number" id="durationLimit" name="durationLimit" min="1" required><br><br>
                        <label for="ratePerMinute">Rate Per Minute</label><br>
                        <input type="number" id="ratePerMinute" name="ratePerMinute" step="0.01" min="0" required><br><br>
                        <button type="submit">Create Vehicle Rate</button>
                    </form>
                    <p><a href="%1$s/api/admin/vehicle-rates">Cancel</a></p>
                    """.formatted(base, vehicleTypeOptions(connection, null)));
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load create vehicle rate form.", exception);
            return error(base, "Unable to load create vehicle rate form.");
        }
    }

    @POST
    @Path("vehicle-rates/create")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response createVehicleRate(
            @FormParam("vehicleTypeId") int vehicleTypeId,
            @FormParam("durationLimit") int durationLimit,
            @FormParam("ratePerMinute") BigDecimal ratePerMinute,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (vehicleTypeId <= 0 || durationLimit <= 0
                || ratePerMinute == null || ratePerMinute.signum() < 0) {
            return error(base, "Vehicle type, duration limit, and rate per minute are required.");
        }

        String sql = """
                INSERT INTO vehicle_rate (vehicle_type_id, duration_limit, rate_per_minute)
                VALUES (?, ?, ?)
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, vehicleTypeId);
            statement.setInt(2, durationLimit);
            statement.setBigDecimal(3, ratePerMinute);
            statement.executeUpdate();
            return redirect(base + "/api/admin/vehicle-rates");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to create vehicle rate.", exception);
            return error(base, databaseMessage("create vehicle rate", exception));
        }
    }

    @GET
    @Path("vehicle-rates/{rateId}/edit")
    public Response editVehicleRateForm(
            @PathParam("rateId") long rateId,
            @Context HttpServletRequest request) {
        String base = base(request);
        String sql = "SELECT vehicle_type_id, duration_limit, rate_per_minute FROM vehicle_rate WHERE rate_id = ?";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, rateId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return error(base, Response.Status.NOT_FOUND, "Vehicle rate not found.");
                }

                String content = """
                        <h2>Update Vehicle Rate</h2>
                        <form method="post" action="%1$s/api/admin/vehicle-rates/%2$d/edit">
                            <label for="vehicleTypeId">Vehicle Type</label><br>
                            <select id="vehicleTypeId" name="vehicleTypeId" required>%3$s</select><br><br>
                            <label for="durationLimit">Duration Limit (minutes)</label><br>
                            <input type="number" id="durationLimit" name="durationLimit" min="1" value="%4$d" required><br><br>
                            <label for="ratePerMinute">Rate Per Minute</label><br>
                            <input type="number" id="ratePerMinute" name="ratePerMinute" step="0.01" min="0" value="%5$s" required><br><br>
                            <button type="submit">Update Vehicle Rate</button>
                        </form>
                        <p><a href="%1$s/api/admin/vehicle-rates">Cancel</a></p>
                        """.formatted(
                        base,
                        rateId,
                        vehicleTypeOptions(connection, (long) result.getInt("vehicle_type_id")),
                        result.getInt("duration_limit"),
                        result.getBigDecimal("rate_per_minute")
                );
                return page(base, "Update Vehicle Rate", content);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load vehicle rate.", exception);
            return error(base, "Unable to load vehicle rate.");
        }
    }

    @POST
    @Path("vehicle-rates/{rateId}/edit")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response updateVehicleRate(
            @PathParam("rateId") long rateId,
            @FormParam("vehicleTypeId") int vehicleTypeId,
            @FormParam("durationLimit") int durationLimit,
            @FormParam("ratePerMinute") BigDecimal ratePerMinute,
            @Context HttpServletRequest request) {
        String base = base(request);
        if (vehicleTypeId <= 0 || durationLimit <= 0
                || ratePerMinute == null || ratePerMinute.signum() < 0) {
            return error(base, "Vehicle type, duration limit, and rate per minute are required.");
        }

        String sql = """
                UPDATE vehicle_rate
                SET vehicle_type_id = ?, duration_limit = ?, rate_per_minute = ?
                WHERE rate_id = ?
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, vehicleTypeId);
            statement.setInt(2, durationLimit);
            statement.setBigDecimal(3, ratePerMinute);
            statement.setLong(4, rateId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.NOT_FOUND, "Vehicle rate not found.");
            }
            return redirect(base + "/api/admin/vehicle-rates");
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to update vehicle rate.", exception);
            return error(base, databaseMessage("update vehicle rate", exception));
        }
    }

    private String roleOptions(Connection connection, long selectedRoleId)
            throws SQLException {
        StringBuilder options = new StringBuilder();
        String sql = """
                SELECT role_id, role_name
                FROM roles
                WHERE role_name IN ('ADMIN', 'USER', 'TICKET_ASSIGNER')
                ORDER BY role_name
                """;

        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                options.append("<option value=\"")
                        .append(result.getLong("role_id"))
                        .append("\"")
                        .append(result.getLong("role_id") == selectedRoleId
                                ? " selected"
                                : "")
                        .append(">")
                        .append(escape(result.getString("role_name")))
                        .append("</option>");
            }
        }
        return options.toString();
    }

    private String floorOptions(Connection connection) throws SQLException {
        StringBuilder options = new StringBuilder();
        String sql = """
                SELECT f.floor_id, f.floor_name
                FROM floor f
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM floor_block fb
                    WHERE fb.floor_id = f.floor_id
                      AND fb.date_start <= CURRENT_TIMESTAMP
                      AND fb.date_end IS NULL
                )
                ORDER BY f.floor_name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                options.append("<option value=\"")
                        .append(result.getLong("floor_id"))
                        .append("\">")
                        .append(escape(result.getString("floor_name")))
                        .append("</option>");
            }
        }
        return options.toString();
    }

    private String slotOptions(Connection connection) throws SQLException {
        StringBuilder options = new StringBuilder();
        String sql = """
                SELECT s.slot_id, s.slot_name, f.floor_name
                FROM slot s
                JOIN floor f ON f.floor_id = s.floor_id
                WHERE NOT EXISTS (
                    SELECT 1
                    FROM floor_block fb
                    WHERE fb.floor_id = f.floor_id
                      AND fb.date_start <= CURRENT_TIMESTAMP
                      AND fb.date_end IS NULL
                )
                  AND NOT EXISTS (
                      SELECT 1
                      FROM slot_block sb
                      WHERE sb.slot_id = s.slot_id
                        AND sb.date_start <= CURRENT_TIMESTAMP
                        AND sb.date_end IS NULL
                  )
                ORDER BY f.floor_name, s.slot_name
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                options.append("<option value=\"")
                        .append(result.getLong("slot_id"))
                        .append("\">")
                        .append(escape(result.getString("floor_name")))
                        .append(" - ")
                        .append(escape(result.getString("slot_name")))
                        .append("</option>");
            }
        }
        return options.toString();
    }

    private String vehicleTypeOptions(Connection connection, Long selectedVehicleTypeId)
            throws SQLException {
        StringBuilder options = new StringBuilder();
        String sql = "SELECT vehicle_type_id, type_name FROM vehicle_type ORDER BY type_name";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                long id = result.getInt("vehicle_type_id");
                options.append("<option value=\"")
                        .append(id)
                        .append("\"")
                        .append(selectedVehicleTypeId != null && selectedVehicleTypeId == id
                                ? " selected"
                                : "")
                        .append(">")
                        .append(escape(result.getString("type_name")))
                        .append("</option>");
            }
        }
        return options.toString();
    }

    private Response finishBlock(
            String tableName,
            long blockId,
            String redirectPath,
            String base) {
//        if (blockId <= 0 || !Set.of("floor_block", "slot_block").contains(tableName)) {
        if (blockId <= 0){
            return error(base, "A valid block is required.");
        }
        String sql = "UPDATE " + tableName
                + " SET date_end = CURRENT_TIMESTAMP"
                + " WHERE block_id = ? AND date_end IS NULL";
        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, blockId);
            if (statement.executeUpdate() == 0) {
                return error(base, Response.Status.NOT_FOUND, "Active block not found.");
            }
            return redirect(base + "/api/admin/" + redirectPath);
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to unblock record.", exception);
            return error(base, "Unable to unblock record.");
        }
    }

    private Response blockList(
            String title,
            String sql,
            boolean slotBlock,
            boolean current,
            String base) {
        StringBuilder content = new StringBuilder("""
                <h2>%s</h2>
                <p><a href="%s/api/admin">Admin Dashboard</a></p>
                <table border="1">
                    %s
                """.formatted(
                escape(title),
                base,
                slotBlock
                        ? "<tr><th>Floor</th><th>Slot</th><th>Start Time</th><th>End Time</th><th>Reason</th><th>Created By</th><th>Action</th></tr>"
                        : "<tr><th>Floor</th><th>Start Time</th><th>End Time</th><th>Reason</th><th>Created By</th><th>Action</th></tr>"));

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                content.append("<tr>");
                if (slotBlock) {
                    content.append("<td>").append(escape(result.getString("floor_name")))
                            .append("</td><td>").append(escape(result.getString("slot_name")))
                            .append("</td>");
                } else {
                    content.append("<td>").append(escape(result.getString("floor_name")))
                            .append("</td>");
                }
                content.append("<td>").append(escape(String.valueOf(result.getTimestamp("date_start"))))
                        .append("</td><td>").append(escape(String.valueOf(result.getTimestamp("date_end"))))
                        .append("</td><td>").append(escape(result.getString("reason")))
                        .append("</td><td>").append(result.getLong("user_id"))
                        .append("</td><td>");
                if (current) {
                    String path = slotBlock ? "slot-blocks" : "floor-blocks";
                    content.append("<form method=\"post\" action=\"")
                            .append(base)
                            .append("/api/admin/")
                            .append(path).append("/")
                            .append(result.getLong("block_id"))
                            .append("/unblock\"><button type=\"submit\">Unblock</button></form>");
                } else {
                    content.append("Completed");
                }
                content.append("</td></tr>");
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load block records.", exception);
            return error(base, "Unable to load block records.");
        }
        content.append("</table>");
        return page(base, title, content.toString());
    }

    private Response slotForm(
            String base,
            String title,
            String action,
            String slotName,
            Long selectedFloorId,
            boolean includeFloor) {
        List<FloorOption> floors = new ArrayList<>();
        String sql = selectedFloorId == null
                ? "SELECT floor_id, floor_name FROM floor WHERE status = TRUE ORDER BY floor_name"
                : """
                SELECT floor_id, floor_name
                FROM floor
                WHERE status = TRUE OR floor_id = ?
                ORDER BY floor_name
                """;

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = executeFloorQuery(statement, selectedFloorId)) {
            while (result.next()) {
                floors.add(new FloorOption(
                        result.getLong("floor_id"),
                        result.getString("floor_name")
                ));
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Unable to load floors for slot form.", exception);
            return error(base, "Unable to load available floors.");
        }

        StringBuilder floorOptions = new StringBuilder();
        for (FloorOption floor : floors) {
            String selected = selectedFloorId != null
                    && selectedFloorId == floor.id()
                    ? " selected"
                    : "";
            floorOptions.append("<option value=\"")
                    .append(floor.id())
                    .append("\"")
                    .append(selected)
                    .append(">")
                    .append(escape(floor.name()))
                    .append("</option>");
        }

        String floorField = includeFloor
                ? """
                    <label for="floorId">Floor</label><br>
                    <select id="floorId" name="floorId" required>
                        %s
                    </select><br><br>
                    """.formatted(floorOptions)
                : "";
        String content = """
                <h2>%s</h2>
                <form method="post" action="%s">
                    <label for="slotName">Slot Name</label><br>
                    <input type="text" id="slotName" name="slotName" maxlength="2" value="%s" required><br><br>
                    %s
                    <button type="submit">%s</button>
                </form>
                <p><a href="%s/api/admin/slots">Cancel</a></p>
                """.formatted(
                escape(title),
                action,
                slotName == null ? "" : escapeAttribute(slotName),
                floorField,
                title.startsWith("Create") ? "Create Slot" : "Update Slot",
                base
        );
        return page(base, title, content);
    }

    private ResultSet executeFloorQuery(
            PreparedStatement statement,
            Long selectedFloorId) throws SQLException {
        if (selectedFloorId != null) {
            statement.setLong(1, selectedFloorId);
        }
        return statement.executeQuery();
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
                        + "<p><a href=\"" + base + "/api/admin\">Admin Dashboard</a></p>"))
                .build();
    }

    private String pageHtml(String base, String title, String content) {
        return page(base, title, content).getEntity().toString();
    }

    private Response redirect(String path) {
        return Response.seeOther(URI.create(path)).build();
    }

    private long currentUserId(SecurityContext securityContext) {
        if (securityContext == null
                || securityContext.getUserPrincipal() == null) {
            throw new IllegalStateException("Authenticated admin user is required.");
        }
        return Long.parseLong(securityContext.getUserPrincipal().getName());
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

    private static String setting(String environmentName, String propertyName) {
        String systemValue = System.getProperty(propertyName);
        if (systemValue != null && !systemValue.isBlank()) {
            return systemValue;
        }

        String environmentValue = System.getenv(environmentName);
        return environmentValue == null ? null : environmentValue;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String databaseMessage(String operation, SQLException exception) {
        if ("23505".equals(exception.getSQLState())) {
            if (operation.contains("vehicle rate")) {
                return "Unable to " + operation
                        + ": that vehicle type already has a rate for this duration limit.";
            }
            return "Unable to " + operation + ": the name already exists for this scope.";
        }
        if ("23503".equals(exception.getSQLState())) {
            return "Unable to " + operation + ": the selected floor does not exist.";
        }
        return "Unable to " + operation + ".";
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

    private String escapeAttribute(String value) {
        return escape(value);
    }

    private record FloorOption(long id, String name) {
    }
}