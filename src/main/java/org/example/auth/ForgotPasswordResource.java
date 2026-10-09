package org.example.auth;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.example.config.DatabaseConnection;
import org.example.notification.GmailEmailService;
import org.example.notification.WhatsAppNotificationService;

import javax.imageio.ImageIO;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

@Path("forgot-password")
public class ForgotPasswordResource {

    private static final Logger LOGGER =
            Logger.getLogger(ForgotPasswordResource.class.getName());
    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String OTP_SECRET = setting("OTP_HMAC_SECRET");
    private static final String APP_BASE_URL = setting("APP_BASE_URL");

    private static final int SMS_OTP_MINUTES = 5;          // SMS arrives fast -> short life
    private static final int EMAIL_OTP_MINUTES = 10;       // email can be slow -> longer life
    private static final int RESEND_COOLDOWN_SECONDS = 60; // gap between two sends
    private static final int MAX_OTPS_PER_DAY = 3;         // first send + resends + new requests
    private static final int RESET_TOKEN_MINUTES = 10;     // time to type the new password
    private static final int ACTION_TOKEN_MINUTES = 10;
    private static final int REQUEST_LIFETIME_MINUTES = 60;// a request cannot be resent after this
    private static final int KEEP_ROWS_DAYS = 90;          // old rows are deleted after this
    private static final long CLEANUP_INTERVAL_MILLIS = 10 * 60 * 1000L;

    private static final ZoneId DAY_ZONE = ZoneId.of("Asia/Kolkata");

    private static final String SENT_MESSAGE =
            "If an account exists for this email, a verification code has been sent.";
    private static final String RESENT_MESSAGE =
            "If your request is still active, a new code has been sent.";
    private static final String INVALID_CODE_MESSAGE =
            "That code is invalid or has expired.";
    private static final String PASSWORD_RESET = "PASSWORD_RESET";
    private static final String TOTP_RECOVERY = "TOTP_RECOVERY";
    private static final String RECOVERY_SENT_MESSAGE =
            "If an eligible account exists for this email, a verification code has been sent.";

    private static volatile long lastCleanupMillis = 0;

    private static final ExecutorService SENDER =
            Executors.newFixedThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "otp-sender");
                thread.setDaemon(true);
                return thread;
            });

    @POST
    @Path("request")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response requestCode(
            @FormParam("email") String email,
            @FormParam("channel") String channel,
            @Context HttpServletRequest request) {

        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Password reset is not configured.", request);
        }
        if (blank(email)) {
            return errorPage(Response.Status.BAD_REQUEST, "Email is required.", request);
        }
        String chosenChannel = blank(channel) ? "" : channel.trim().toUpperCase();
        if (!chosenChannel.equals("SMS") && !chosenChannel.equals("EMAIL")) {
            return errorPage(Response.Status.BAD_REQUEST, "Choose Email or SMS.", request);
        }
        if (chosenChannel.equals("EMAIL")) {
            try {
                if (GmailEmailService.validateStoredAuthorization() == null) {
                    return errorPage(Response.Status.SERVICE_UNAVAILABLE,
                            "Email password reset is unavailable. Choose SMS or contact an administrator.",
                            request);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Gmail email setup validation was interrupted.", exception);
                return errorPage(Response.Status.SERVICE_UNAVAILABLE,
                        "Email password reset is temporarily unavailable. Choose SMS or try again later.",
                        request);
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Gmail email setup is unavailable.", exception);
                return errorPage(Response.Status.SERVICE_UNAVAILABLE,
                        "Email password reset is unavailable. Choose SMS or contact an administrator.",
                        request);
            }
        }

        cleanupIfDue();

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                RequestResult result = startOrResend(connection, email.trim(), chosenChannel,
                        PASSWORD_RESET);
                connection.commit();

                if (result.otp() != null) {
                    sendOtpAsync(chosenChannel, result.destination(), result.otp());
                }
                return otpPage(result.requestId(), SENT_MESSAGE, false, request);

            } catch (SQLException exception) {
                connection.rollback();

                // Two requests for the same user at the same instant: the unique
                // index allows only one open request. Answer normally.
                if ("23505".equals(exception.getSQLState())) {
                    return otpPage(UUID.randomUUID().toString(), SENT_MESSAGE, false, request);
                }
                throw exception;

            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while requesting a reset code.", exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to process the request.", request);
        }
    }

    @GET
    @Path("authenticator")
    @Produces(MediaType.TEXT_HTML)
    public Response authenticatorRecoveryPage(@Context HttpServletRequest request) {
        String base = base(request);
        String body = "<p>Verify your account to replace a lost authenticator and recovery codes.</p>"
                + "<form method=\"post\" action=\"" + escapeHtml(base)
                + "/api/forgot-password/authenticator/request\">"
                + "<label for=\"email\">Account email</label><br>"
                + "<input type=\"email\" id=\"email\" name=\"email\" required><br><br>"
                + "<fieldset><legend>Where should we send the verification code?</legend>"
                + "<label><input type=\"radio\" name=\"channel\" value=\"EMAIL\" checked>"
                + " Email</label><br>"
                + "<label><input type=\"radio\" name=\"channel\" value=\"SMS\">"
                + " WhatsApp to your saved phone number</label></fieldset><br>"
                + "<button type=\"submit\">Send verification code</button></form>"
                + "<p><a href=\"" + escapeHtml(base) + "/login.html\">Back to login</a></p>";
        return html(Response.Status.OK, page("Recover authenticator", body));
    }

    @POST
    @Path("authenticator/request")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response requestAuthenticatorRecovery(
            @FormParam("email") String email,
            @FormParam("channel") String channel,
            @Context HttpServletRequest request) {
        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Authenticator recovery is not configured.", request);
        }
        if (blank(email)) {
            return recoveryRequestPage(request, "Enter your account email.",
                    Response.Status.BAD_REQUEST);
        }
        String chosenChannel = blank(channel) ? "" : channel.trim().toUpperCase();
        if (!chosenChannel.equals("SMS") && !chosenChannel.equals("EMAIL")) {
            return recoveryRequestPage(request, "Choose Email or WhatsApp.",
                    Response.Status.BAD_REQUEST);
        }
        if (chosenChannel.equals("EMAIL")) {
            try {
                if (GmailEmailService.validateStoredAuthorization() == null) {
                    return recoveryRequestPage(request,
                            "Email verification is unavailable. Choose WhatsApp or contact an administrator.",
                            Response.Status.SERVICE_UNAVAILABLE);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.log(Level.WARNING, "Gmail authorization check was interrupted.", exception);
                return recoveryRequestPage(request,
                        "Email verification is temporarily unavailable. Choose WhatsApp or try later.",
                        Response.Status.SERVICE_UNAVAILABLE);
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Gmail authorization is unavailable.", exception);
                return recoveryRequestPage(request,
                        "Email verification is unavailable. Choose WhatsApp or contact an administrator.",
                        Response.Status.SERVICE_UNAVAILABLE);
            }
        }
        cleanupIfDue();
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                RequestResult result = startOrResend(connection, email.trim(), chosenChannel,
                        TOTP_RECOVERY);
                connection.commit();
                if (result.otp() != null) {
                    sendOtpAsync(chosenChannel, result.destination(), result.otp(), TOTP_RECOVERY);
                }
                return recoveryOtpPage(result.requestId(), RECOVERY_SENT_MESSAGE, false, request);
            } catch (SQLException exception) {
                connection.rollback();
                if ("23505".equals(exception.getSQLState())) {
                    return recoveryOtpPage(UUID.randomUUID().toString(),
                            RECOVERY_SENT_MESSAGE, false, request);
                }
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while requesting authenticator recovery.",
                    exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to process authenticator recovery.", request);
        }
    }

    /**
     * Decides what to do for this email + channel and returns what to show/send.
     * requestId is always filled. otp is null when nothing should be sent.
     */
    private RequestResult startOrResend(Connection connection, String email, String channel)
            throws SQLException {
        return startOrResend(connection, email, channel, PASSWORD_RESET);
    }

    private RequestResult startOrResend(Connection connection, String email, String channel,
                                        String purpose)
            throws SQLException {

        ResetUser user = findEligibleUser(connection, email, purpose);
        if (user == null) {
            // Unknown, Google-only or incomplete account: behave as if it worked.
            return new RequestResult(UUID.randomUUID().toString(), null, null);
        }

        String destination = channel.equals("SMS") ? user.phone() : user.email();
        if (!validDestination(channel, destination)) {
            return new RequestResult(UUID.randomUUID().toString(), null, null);
        }

        Instant now = Instant.now();

        // Close requests that ran out of time, then look for the one still open.
        expireStaleRequests(connection, user.userId(), now, purpose);
        OpenRequest open = findOpenRequest(connection, user.userId());
        if (open != null && !open.purpose().equals(purpose)) {
            cancelOpenRequests(connection, user.userId());
            open = null;
        }
        boolean allowed = canSendNow(connection, user.userId(), now);

        // Case 1: the user already has a live request on the same channel.
        // Starting again (for example after closing the page) becomes a RESEND,
        // so the resend counters cannot be dodged.
        if (open != null && open.status().equals("PENDING") && open.channel().equals(channel)) {
            if (allowed && open.resendCount() < open.maxResends()) {
                String otp = newOtp();
                saveResendOtp(connection, open.id(), otp, channel, now, purpose);
                return new RequestResult(open.id(), open.destination(), otp);
            }
            return new RequestResult(open.id(), null, null); // limit reached: send nothing
        }

        // Case 2: a limit is reached and there is nothing to reuse.
        if (!allowed) {
            return new RequestResult(
                    open != null ? open.id() : UUID.randomUUID().toString(), null, null);
        }

        // Case 3: create a brand-new request (cancel any other open one first).
        if (open != null) {
            cancelOpenRequests(connection, user.userId());
        }
        String requestId = UUID.randomUUID().toString();
        String otp = newOtp();
        insertRequest(connection, requestId, user.userId(), channel, destination, otp, now,
                purpose);
        return new RequestResult(requestId, destination, otp);
    }

    // ==================================================================
    // STEP 2 - resend
    // ==================================================================
    @POST
    @Path("resend")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response resend(
            @FormParam("requestId") String requestId,
            @Context HttpServletRequest request) {
        return resendCode(requestId, PASSWORD_RESET, request);
    }

    @POST
    @Path("authenticator/resend")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response resendAuthenticatorCode(
            @FormParam("requestId") String requestId,
            @Context HttpServletRequest request) {
        return resendCode(requestId, TOTP_RECOVERY, request);
    }

    private Response resendCode(String requestId, String purpose,
                                HttpServletRequest request) {
        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Verification code delivery is not configured.", request);
        }
        UUID id = parseUuid(requestId);
        if (id == null) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Invalid request. Please start again.", request);
        }

        boolean emailUnavailable = false;
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                String sql = """
                    SELECT user_id, channel, destination, status,
                           resend_count, max_resends, created_at
                    FROM password_reset_requests
                    WHERE request_id = ? AND purpose = ?
                    FOR UPDATE
                    """;

                String otp = null;
                String destination = null;
                String channel = null;

                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    statement.setObject(1, id);
                    statement.setString(2, purpose);

                    try (ResultSet row = statement.executeQuery()) {
                        if (row.next()) {
                            Instant now = Instant.now();
                            long userId = row.getLong("user_id");
                            Instant createdAt = row.getTimestamp("created_at").toInstant();
                            boolean stillAlive = createdAt.isAfter(
                                    now.minus(Duration.ofMinutes(REQUEST_LIFETIME_MINUTES)));

                            if (row.getString("status").equals("PENDING")
                                    && stillAlive
                                    && row.getInt("resend_count") < row.getInt("max_resends")
                                    && canSendNow(connection, userId, now)) {

                                channel = row.getString("channel");
                                destination = row.getString("destination");
                                if (channel.equals("EMAIL")) {
                                    try {
                                        emailUnavailable =
                                                GmailEmailService.validateStoredAuthorization() == null;
                                    } catch (SQLException exception) {
                                        throw exception;
                                    } catch (InterruptedException exception) {
                                        Thread.currentThread().interrupt();
                                        LOGGER.log(Level.WARNING,
                                                "Gmail email setup validation was interrupted for OTP resend.",
                                                exception);
                                        emailUnavailable = true;
                                    } catch (Exception exception) {
                                        LOGGER.log(Level.WARNING,
                                                "Gmail email setup is unavailable for OTP resend.", exception);
                                        emailUnavailable = true;
                                    }
                                }
                                if (!emailUnavailable) {
                                    otp = newOtp();
                                    saveResendOtp(connection, id.toString(), otp, channel, now,
                                            purpose);
                                }
                            }
                        }
                    }
                }

                connection.commit();

                if (emailUnavailable) {
                    String message = purpose.equals(TOTP_RECOVERY)
                            ? "Email verification is unavailable. Choose WhatsApp or contact an administrator."
                            : "Email password reset is unavailable. Choose SMS or contact an administrator.";
                    return errorPage(Response.Status.SERVICE_UNAVAILABLE,
                            message, request);
                }
                if (otp != null) {
                    sendOtpAsync(channel, destination, otp, purpose);
                }
                return purpose.equals(TOTP_RECOVERY)
                        ? recoveryOtpPage(id.toString(), RESENT_MESSAGE, false, request)
                        : otpPage(id.toString(), RESENT_MESSAGE, false, request);

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while resending a verification code.", exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to resend the code.", request);
        }
    }

    // ==================================================================
    // STEP 3 - verify the OTP
    // ==================================================================
    @POST
    @Path("verify")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response verify(
            @FormParam("requestId") String requestId,
            @FormParam("otp") String otp,
            @Context HttpServletRequest request) {

        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Password reset is not configured.", request);
        }
        UUID id = parseUuid(requestId);
        if (id == null) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Invalid request. Please start again.", request);
        }
        // Not 6 digits: no guess was made, so this does not count as an attempt.
        if (blank(otp) || !otp.trim().matches("\\d{6}")) {
            return otpPage(id.toString(), INVALID_CODE_MESSAGE, true, request);
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                String resetToken = checkOtp(connection, id, otp.trim(), PASSWORD_RESET);
                connection.commit();

                if (resetToken == null) {
                    return otpPage(id.toString(), INVALID_CODE_MESSAGE, true, request);
                }
                return resetFormPage(resetToken, null, request);

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while verifying a reset code.", exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to verify the code.", request);
        }
    }

    @POST
    @Path("authenticator/verify")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response verifyAuthenticatorRecovery(
            @FormParam("requestId") String requestId,
            @FormParam("otp") String otp,
            @Context HttpServletRequest request) {
        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Authenticator recovery is not configured.", request);
        }
        UUID id = parseUuid(requestId);
        if (id == null) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Invalid recovery request. Please start again.", request);
        }
        if (blank(otp) || !otp.trim().matches("\\d{6}")) {
            return recoveryOtpPage(id.toString(), INVALID_CODE_MESSAGE, true, request);
        }
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                String actionToken = checkOtp(connection, id, otp.trim(), TOTP_RECOVERY);
                connection.commit();
                if (actionToken == null) {
                    return recoveryOtpPage(id.toString(), INVALID_CODE_MESSAGE, true, request);
                }
                return authenticatorEnrollmentStartPage(actionToken, request);
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while verifying authenticator recovery.",
                    exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to verify the code.", request);
        }
    }

    /** Returns a new reset token if the OTP is correct, otherwise null. */
    private String checkOtp(Connection connection, UUID id, String otp, String purpose)
            throws SQLException {
        // FOR UPDATE locks this row until commit, so two parallel guesses are
        // handled one after the other and every wrong guess is really counted.
        String sql = """
            SELECT status, expires_at, attempt_count, max_attempts, otp_hash
            FROM password_reset_requests
            WHERE request_id = ? AND purpose = ?
            FOR UPDATE
            """;

        String status;
        Instant expiresAt;
        int attempts;
        int maxAttempts;
        String storedHash;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, purpose);

            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                status = row.getString("status");
                expiresAt = row.getTimestamp("expires_at").toInstant();
                attempts = row.getInt("attempt_count");
                maxAttempts = row.getInt("max_attempts");
                storedHash = row.getString("otp_hash");
            }
        }

        Instant now = Instant.now();
        if (!status.equals("PENDING") || !now.isBefore(expiresAt) || attempts >= maxAttempts) {
            return null;
        }

        boolean correct = MessageDigest.isEqual(
                hmacHex(id + ":" + otp).getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8));

        if (!correct) {
            int newAttempts = attempts + 1;
            String newStatus = newAttempts >= maxAttempts ? "LOCKED" : "PENDING";

            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE password_reset_requests "
                            + "SET attempt_count = ?, status = ? "
                            + "WHERE request_id = ? AND purpose = ?")) {
                statement.setInt(1, newAttempts);
                statement.setString(2, newStatus);
                statement.setObject(3, id);
                statement.setString(4, purpose);
                statement.executeUpdate();
            }
            return null;
        }

        // Correct OTP: issue a long random one-time token. Only its hash is stored.
        String actionToken = randomToken();

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE password_reset_requests "
                        + "SET status = 'VERIFIED', verified_at = ?, "
                        + "    action_token_hash = ?, action_token_expires_at = ? "
                        + "WHERE request_id = ? AND purpose = ? AND status = 'PENDING'")) {
            statement.setTimestamp(1, Timestamp.from(now));
            statement.setString(2, hmacHex(actionTokenPurpose(purpose) + actionToken));
            statement.setTimestamp(3, Timestamp.from(now.plus(Duration.ofMinutes(
                    purpose.equals(TOTP_RECOVERY) ? ACTION_TOKEN_MINUTES : RESET_TOKEN_MINUTES))));
            statement.setObject(4, id);
            statement.setString(5, purpose);
            statement.executeUpdate();
        }
        return actionToken;
    }

    // ==================================================================
    // STEP 4 - set the new password
    // ==================================================================
    @POST
    @Path("reset")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response reset(
            @FormParam("resetToken") String resetToken,
            @FormParam("newPassword") String newPassword,
            @FormParam("confirmPassword") String confirmPassword,
            @Context HttpServletRequest request) {

        if (!secretConfigured()) {
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Password reset is not configured.", request);
        }
        if (blank(resetToken)) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Reset session not found. Please start again.", request);
        }
        // Same checks as your signup: both fields filled and equal, no other rules.
        if (blank(newPassword) || blank(confirmPassword)) {
            return resetFormPage(resetToken, "Both password fields are required.", request);
        }
        if (!newPassword.equals(confirmPassword)) {
            return resetFormPage(resetToken,
                    "Password and confirm password do not match.", request);
        }

        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);

            try {
                if (matchesCurrentPassword(connection, resetToken, newPassword)) {
                    connection.rollback();
                    return resetFormPage(resetToken,
                            "Choose a new password that is different from your old password.",
                            request);
                }
                ResetUser user = applyNewPassword(connection, resetToken, newPassword);

                if (user == null) {
                    connection.rollback();
                    return errorPage(Response.Status.BAD_REQUEST,
                            "This reset session has expired. Please start again.", request);
                }

                connection.commit();
                sendPasswordChangedNoticeAsync(user.email(), user.phone());
                return successPage(request);

            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }

        } catch (SQLException exception) {
            LOGGER.log(Level.SEVERE, "Database error while resetting the password.", exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to reset the password.", request);
        }
    }

    private boolean matchesCurrentPassword(Connection connection, String resetToken,
                                           String newPassword) throws SQLException {
        String sql = """
            SELECT la.password
            FROM password_reset_requests r
            JOIN local_auth la ON la.user_id = r.user_id
            WHERE r.action_token_hash = ?
              AND r.status = 'VERIFIED'
              AND r.purpose = 'PASSWORD_RESET'
              AND r.action_token_expires_at > CURRENT_TIMESTAMP
            FOR UPDATE OF r, la
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hmacHex(actionTokenPurpose(PASSWORD_RESET)
                    + resetToken.trim()));
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && PasswordHasher.matches(newPassword,
                        result.getString("password"));
            }
        }
    }

    @POST
    @Path("authenticator/setup")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response setupRecoveredAuthenticator(
            @FormParam("actionToken") String actionToken,
            @Context HttpServletRequest request) {
        if (!secretConfigured() || blank(actionToken)) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Authenticator recovery session is invalid or expired. Start again.",
                    request);
        }
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                RecoveryUser user = verifiedRecoveryUser(connection, actionToken);
                if (user == null || !TwoFactorService.isEnabled(connection, user.userId())) {
                    connection.rollback();
                    return errorPage(Response.Status.BAD_REQUEST,
                            "Authenticator recovery session is invalid or expired. Start again.",
                            request);
                }
                String secret = TwoFactorService.beginEnrollment(connection, user.userId());
                connection.commit();
                return recoveryEnrollmentPage(actionToken, user.email(), secret, null,
                        Response.Status.OK, request);
            } catch (SQLException | GeneralSecurityException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | GeneralSecurityException | WriterException | IOException exception) {
            LOGGER.log(Level.SEVERE, "Unable to begin recovered authenticator enrollment.",
                    exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to set up a new authenticator.", request);
        }
    }

    @POST
    @Path("authenticator/confirm")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.TEXT_HTML)
    public Response confirmRecoveredAuthenticator(
            @FormParam("actionToken") String actionToken,
            @FormParam("code") String code,
            @Context HttpServletRequest request) {
        if (!secretConfigured() || blank(actionToken)) {
            return errorPage(Response.Status.BAD_REQUEST,
                    "Authenticator recovery session is invalid or expired. Start again.",
                    request);
        }
        try (Connection connection = DatabaseConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                RecoveryUser user = verifiedRecoveryUser(connection, actionToken);
                if (user == null || !TwoFactorService.isEnabled(connection, user.userId())) {
                    connection.rollback();
                    return errorPage(Response.Status.BAD_REQUEST,
                            "Authenticator recovery session is invalid or expired. Start again.",
                            request);
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM two_factor_login_challenges WHERE user_id = ?")) {
                    statement.setLong(1, user.userId());
                    statement.executeUpdate();
                }
                List<String> recoveryCodes = TwoFactorService.confirmRecoveryEnrollment(
                        connection, user.userId(), code);
                if (recoveryCodes == null) {
                    connection.rollback();
                    return recoveryEnrollmentRetryPage(actionToken, request);
                }
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE password_reset_requests
                        SET status = 'USED', used_at = CURRENT_TIMESTAMP
                        WHERE action_token_hash = ? AND purpose = 'TOTP_RECOVERY'
                          AND status = 'VERIFIED'
                          AND action_token_expires_at > CURRENT_TIMESTAMP
                        """)) {
                    statement.setString(1, hmacHex(actionTokenPurpose(TOTP_RECOVERY)
                            + actionToken.trim()));
                    if (statement.executeUpdate() != 1) {
                        connection.rollback();
                        return errorPage(Response.Status.BAD_REQUEST,
                                "Authenticator recovery session is invalid or expired. Start again.",
                                request);
                    }
                }
                connection.commit();
                return recoveryCodesAndLoginPage(request, recoveryCodes);
            } catch (SQLException | GeneralSecurityException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException | GeneralSecurityException exception) {
            LOGGER.log(Level.SEVERE, "Unable to confirm recovered authenticator.", exception);
            return errorPage(Response.Status.INTERNAL_SERVER_ERROR,
                    "Unable to update authenticator settings.", request);
        }
    }

    private RecoveryUser verifiedRecoveryUser(Connection connection, String actionToken)
            throws SQLException {
        String sql = """
            SELECT r.user_id, u.email
            FROM password_reset_requests r
            JOIN users u ON u.user_id = r.user_id
            WHERE r.action_token_hash = ?
              AND r.purpose = 'TOTP_RECOVERY'
              AND r.status = 'VERIFIED'
              AND r.action_token_expires_at > CURRENT_TIMESTAMP
            FOR UPDATE OF r
            """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hmacHex(actionTokenPurpose(TOTP_RECOVERY)
                    + actionToken.trim()));
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new RecoveryUser(result.getLong("user_id"), result.getString("email"));
            }
        }
    }

    /** Returns the user (for the notification) or null if the token is not valid. */
    private ResetUser applyNewPassword(Connection connection, String resetToken, String newPassword)
            throws SQLException {

        String findSql = """
            SELECT request_id, user_id
            FROM password_reset_requests
            WHERE action_token_hash = ?
              AND status = 'VERIFIED'
              AND purpose = 'PASSWORD_RESET'
              AND action_token_expires_at > ?
            FOR UPDATE
            """;

        UUID requestId;
        long userId;

        try (PreparedStatement statement = connection.prepareStatement(findSql)) {
            statement.setString(1, hmacHex(actionTokenPurpose(PASSWORD_RESET)
                    + resetToken.trim()));
            statement.setTimestamp(2, Timestamp.from(Instant.now()));

            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                requestId = row.getObject("request_id", UUID.class);
                userId = row.getLong("user_id");
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE local_auth SET password = ? WHERE user_id = ?")) {
            statement.setString(1, PasswordHasher.hash(newPassword));
            statement.setLong(2, userId);
            if (statement.executeUpdate() != 1) {
                return null;
            }
        }

        // Mark USED. The status check makes sure a token works only once.
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE password_reset_requests SET status = 'USED', used_at = ? "
                        + "WHERE request_id = ? AND purpose = 'PASSWORD_RESET' "
                        + "AND status = 'VERIFIED'")) {
            statement.setTimestamp(1, Timestamp.from(Instant.now()));
            statement.setObject(2, requestId);
            if (statement.executeUpdate() != 1) {
                return null;
            }
        }

        cancelOpenRequests(connection, userId); // safety: nothing else stays open

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT user_id, email, phone_number FROM users WHERE user_id = ?")) {
            statement.setLong(1, userId);

            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return new ResetUser(row.getLong("user_id"),
                        row.getString("email"), row.getString("phone_number"));
            }
        }
    }

    // ==================================================================
    // Database helpers
    // ==================================================================

    /**
     * Only accounts with a password (local_auth row) and a finished profile can
     * reset. Google-only accounts and incomplete profiles count as "not found".
     */
    private ResetUser findEligibleUser(Connection connection, String email) throws SQLException {
        return findEligibleUser(connection, email, PASSWORD_RESET);
    }

    private ResetUser findEligibleUser(Connection connection, String email, String purpose)
            throws SQLException {
        String sql = """
            SELECT u.user_id, u.email, u.phone_number
            FROM users u
            LEFT JOIN local_auth la ON la.user_id = u.user_id
            WHERE lower(u.email) = lower(?)
              AND u.profile_completed = TRUE
              AND (? = 'TOTP_RECOVERY'
                   OR la.user_id IS NOT NULL)
              AND (? <> 'TOTP_RECOVERY' OR EXISTS (
                    SELECT 1 FROM user_two_factor tf
                    WHERE tf.user_id = u.user_id AND tf.secret_ciphertext IS NOT NULL
              ))
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, email);
            statement.setString(2, purpose);
            statement.setString(3, purpose);

            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                ResetUser user = new ResetUser(row.getLong("user_id"),
                        row.getString("email"), row.getString("phone_number"));

                // Two accounts whose emails differ only in letter case: do not guess.
                if (row.next()) {
                    return null;
                }
                return user;
            }
        }
    }

    /** Sets old requests to EXPIRED once their time is over (keeps the table honest). */
    private void expireStaleRequests(Connection connection, long userId, Instant now,
                                     String purpose)
            throws SQLException {

        String sql = """
            UPDATE password_reset_requests
            SET status = 'EXPIRED'
            WHERE user_id = ? AND purpose = ?
              AND ((status = 'PENDING'  AND created_at <= ?)
                OR (status = 'VERIFIED' AND action_token_expires_at <= ?))
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setString(2, purpose);
            statement.setTimestamp(3, Timestamp.from(now.minus(Duration.ofMinutes(REQUEST_LIFETIME_MINUTES))));
            statement.setTimestamp(4, Timestamp.from(now));
            statement.executeUpdate();
        }
    }

    /** The user's single open request (the unique index allows at most one). */
    private OpenRequest findOpenRequest(Connection connection, long userId) throws SQLException {
        String sql = """
            SELECT request_id, status, channel, destination, resend_count, max_resends, purpose
            FROM password_reset_requests
            WHERE user_id = ? AND status IN ('PENDING', 'VERIFIED')
            FOR UPDATE
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);

            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) {
                    return null;
                }
                return new OpenRequest(
                        row.getString("request_id"),
                        row.getString("status"),
                        row.getString("channel"),
                        row.getString("destination"),
                        row.getInt("resend_count"),
                        row.getInt("max_resends"),
                        row.getString("purpose"));
            }
        }
    }

    /**
     * Daily limit + cooldown, counted from the table across ALL of the user's
     * requests today. Every request counts as 1 send, plus its resends.
     * (A resend is counted on the day its request was created - close enough.)
     */
    private boolean canSendNow(Connection connection, long userId, Instant now)
            throws SQLException {

        Instant startOfToday = LocalDate.now(DAY_ZONE).atStartOfDay(DAY_ZONE).toInstant();

        String sql = """
            SELECT COALESCE(SUM(1 + resend_count), 0) AS sends_today,
                   MAX(last_sent_at) AS last_sent
            FROM password_reset_requests
            WHERE user_id = ? AND created_at >= ?
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, userId);
            statement.setTimestamp(2, Timestamp.from(startOfToday));

            try (ResultSet row = statement.executeQuery()) {
                row.next();
                long sendsToday = row.getLong("sends_today");
                Timestamp lastSent = row.getTimestamp("last_sent");

                if (sendsToday >= MAX_OTPS_PER_DAY) {
                    return false;
                }
                if (lastSent != null
                        && lastSent.toInstant().plusSeconds(RESEND_COOLDOWN_SECONDS).isAfter(now)) {
                    return false;
                }
                return true;
            }
        }
    }

    private void insertRequest(Connection connection, String requestId, long userId,
                               String channel, String destination, String otp, Instant now,
                               String purpose)
            throws SQLException {

        String sql = """
            INSERT INTO password_reset_requests
                (request_id, user_id, channel, destination, otp_hash,
                 status, created_at, expires_at, last_sent_at, purpose)
            VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?)
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, UUID.fromString(requestId));
            statement.setLong(2, userId);
            statement.setString(3, channel);
            statement.setString(4, destination);
            statement.setString(5, hmacHex(requestId + ":" + otp));
            statement.setTimestamp(6, Timestamp.from(now));
            statement.setTimestamp(7, Timestamp.from(now.plus(Duration.ofMinutes(otpMinutes(channel)))));
            statement.setTimestamp(8, Timestamp.from(now));
            statement.setString(9, purpose);
            statement.executeUpdate();
        }
    }

    /** New OTP for an existing request: old code stops working, attempts start again. */
    private void saveResendOtp(Connection connection, String requestId, String otp,
                               String channel, Instant now, String purpose) throws SQLException {

        String sql = """
            UPDATE password_reset_requests
            SET otp_hash = ?, expires_at = ?, attempt_count = 0,
                resend_count = resend_count + 1, last_sent_at = ?
            WHERE request_id = ? AND purpose = ? AND status = 'PENDING'
            """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, hmacHex(requestId + ":" + otp));
            statement.setTimestamp(2, Timestamp.from(now.plus(Duration.ofMinutes(otpMinutes(channel)))));
            statement.setTimestamp(3, Timestamp.from(now));
            statement.setObject(4, UUID.fromString(requestId));
            statement.setString(5, purpose);
            statement.executeUpdate();
        }
    }

    /**
     * Cancels the user's open (PENDING / VERIFIED) reset requests.
     * CALL THIS:
     *  - right after a SUCCESSFUL password login (never after a failed one)
     *  - when the user changes email, phone or password from the profile page
     *  - when an account is disabled or deleted
     * It uses the caller's connection, so it joins the caller's transaction.
     */
    public static int cancelOpenRequests(Connection connection, long userId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE password_reset_requests SET status = 'CANCELLED' "
                        + "WHERE user_id = ? AND status IN ('PENDING', 'VERIFIED')")) {
            statement.setLong(1, userId);
            return statement.executeUpdate();
        }
    }

    /**
     * Housekeeping, run at most every 10 minutes by whichever request arrives first
     * (so you do not need a scheduler yet). Security never depends on this:
     * every check above looks at the expiry times directly.
     */
    private static void cleanupIfDue() {
        long nowMillis = System.currentTimeMillis();
        if (nowMillis - lastCleanupMillis < CLEANUP_INTERVAL_MILLIS) {
            return;
        }
        lastCleanupMillis = nowMillis;

        Instant now = Instant.now();

        try (Connection connection = DatabaseConnection.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "UPDATE password_reset_requests SET status = 'EXPIRED' "
                            + "WHERE (status = 'PENDING' AND created_at <= ?) "
                            + "   OR (status = 'VERIFIED' AND action_token_expires_at <= ?)")) {
                statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofMinutes(REQUEST_LIFETIME_MINUTES))));
                statement.setTimestamp(2, Timestamp.from(now));
                statement.executeUpdate();
            }

            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM password_reset_requests WHERE created_at < ?")) {
                statement.setTimestamp(1, Timestamp.from(now.minus(Duration.ofDays(KEEP_ROWS_DAYS))));
                statement.executeUpdate();
            }
        } catch (SQLException exception) {
            LOGGER.log(Level.WARNING, "Reset request cleanup failed.", exception);
        }
    }

    // ==================================================================
    // Sending - YOU FILL THESE TWO IN
    // ==================================================================

    private void sendOtpAsync(String channel, String destination, String otp) {
        sendOtpAsync(channel, destination, otp, PASSWORD_RESET);
    }

    private void sendOtpAsync(String channel, String destination, String otp, String purpose) {
        SENDER.submit(() -> {
            try {
                sendOtp(channel, destination, otp, purpose);
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Could not send the verification code.", exception);
            }
        });
    }

    /**
     * channel     "SMS" or "EMAIL"
     * destination the 10-digit phone number (add your country code, e.g. "+91"
     *             + destination) or the email address
     * otp         the 6-digit code to put in the message
     *
     * Suggested text: "Your Parking Management System code is 123456. It is valid
     * for 5 minutes. Do not share it with anyone."
     */
    private void sendOtp(String channel, String destination, String otp, String purpose) {
        String text = purpose.equals(TOTP_RECOVERY)
                ? "Your Parking Management System authenticator recovery code is " + otp
                + ". It is valid for " + otpMinutes(channel) + " minutes. "
                + "Do not share it with anyone."
                : "Your Parking Management System verification code is " + otp
                + ". It is valid for " + otpMinutes(channel) + " minutes. "
                + "Do not share it with anyone.";

        if (channel.equals("EMAIL")) {
            String subject = purpose.equals(TOTP_RECOVERY)
                    ? "Authenticator recovery code"
                    : "Your Parking Management System verification code";
            sendEmail(destination, subject, text);
        } else {
            sendSms(destination, otp);
        }
    }

    private void sendSms(String phoneNumber, String otp) {
        if (blank(phoneNumber)) {
            LOGGER.warning("SMS OTP failed: phone number is blank.");
            return;
        }

        try {
            WhatsAppNotificationService.sendResetOtpCode(phoneNumber, otp);
        } catch (Exception exception) {
            LOGGER.log(Level.WARNING, "Failed to send OTP via WhatsApp SMS to " + phoneNumber + ".",
                    exception);
        }
    }

    private void sendEmail(String toEmail, String subject, String body) {
        try {
            GmailEmailService.sendEmail(toEmail, subject, body);
        } catch (GmailEmailService.EmailSetupException exception) {
            LOGGER.log(Level.WARNING, "Failed to send OTP email to " + toEmail + ".", exception);
        }
    }


    private void sendPasswordChangedNoticeAsync(String email, String phone) {
        SENDER.submit(() -> {
            try {
                sendPasswordChangedNotice(email, phone);
            } catch (Exception exception) {
                LOGGER.log(Level.WARNING, "Could not send the password-changed notice.", exception);
            }
        });
    }

    /** Tell the real owner the password changed (in case somebody else did it). */
    private void sendPasswordChangedNotice(String email, String phone) {
        // TODO: send "Your password was changed. If this was not you, contact support."
        // to the email and/or phone using your own API.
    }

    // ==================================================================
    // Small helpers
    // ==================================================================

    private String newOtp() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String actionTokenPurpose(String purpose) {
        return purpose.equals(TOTP_RECOVERY) ? "totp-recovery:" : "reset:";
    }

    /** One-way hash with a server secret. The OTP / token itself is never stored. */
    private static String hmacHex(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(OTP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash value.", exception);
        }
    }

    private int otpMinutes(String channel) {
        return channel.equals("SMS") ? SMS_OTP_MINUTES : EMAIL_OTP_MINUTES;
    }

    /** Must match the CHECK constraint chk_prr_destination in the table. */
    private boolean validDestination(String channel, String destination) {
        if (destination == null) {
            return false;
        }
        return channel.equals("SMS")
                ? destination.matches("\\d{10}")
                : destination.matches("[^@\\s]+@[^@\\s]+");
    }

    private UUID parseUuid(String value) {
        try {
            return blank(value) ? null : UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private boolean secretConfigured() {
        return !blank(OTP_SECRET);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String setting(String environmentName) {
        return System.getenv(environmentName);
    }

    private String base(HttpServletRequest request) {
        if (!blank(APP_BASE_URL)) {
            return APP_BASE_URL;
        }
        return request.getScheme() + "://" + request.getServerName() + request.getContextPath();
    }

    private String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // ==================================================================
    // HTML pages (plain HTML, no JavaScript)
    // ==================================================================

    private String page(String heading, String body) {
        return "<!doctype html>"
                + "<html lang=\"en\">"
                + "<head><meta charset=\"UTF-8\"><title>" + heading + "</title></head>"
                + "<body>"
                + "<h1>Parking Management System</h1>"
                + "<h2>" + heading + "</h2>"
                + body
                + "</body></html>";
    }

    private Response html(Response.Status status, String html) {
        return Response.status(status).type(MediaType.TEXT_HTML).entity(html).build();
    }

    private Response noStoreHtml(Response.Status status, String html) {
        return Response.status(status).type(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .entity(html).build();
    }

    private Response recoveryRequestPage(HttpServletRequest request, String message,
                                         Response.Status status) {
        String base = base(request);
        String content = "<p role=\"alert\">" + escapeHtml(message) + "</p>"
                + "<p><a href=\"" + escapeHtml(base)
                + "/api/forgot-password/authenticator\">Try again</a> | "
                + "<a href=\"" + escapeHtml(base) + "/login.html\">Back to login</a></p>";
        return noStoreHtml(status, page("Recover authenticator", content));
    }

    private Response recoveryOtpPage(String requestId, String message, boolean isError,
                                     HttpServletRequest request) {
        String base = base(request);
        String id = escapeHtml(requestId);
        String content = "<p role=\"" + (isError ? "alert" : "status") + "\">"
                + escapeHtml(message) + "</p>"
                + "<form method=\"post\" action=\"" + escapeHtml(base)
                + "/api/forgot-password/authenticator/verify\">"
                + "<input type=\"hidden\" name=\"requestId\" value=\"" + id + "\">"
                + "<label for=\"otp\">6-digit verification code</label><br>"
                + "<input id=\"otp\" name=\"otp\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" "
                + "maxlength=\"6\" autocomplete=\"one-time-code\" required><br><br>"
                + "<button type=\"submit\">Verify code</button></form>"
                + "<form method=\"post\" action=\"" + escapeHtml(base)
                + "/api/forgot-password/authenticator/resend\">"
                + "<input type=\"hidden\" name=\"requestId\" value=\"" + id + "\">"
                + "<button type=\"submit\">Resend code</button></form>"
                + "<p>Codes expire after a short time. Up to " + MAX_OTPS_PER_DAY
                + " codes may be sent per day.</p>"
                + "<p><a href=\"" + escapeHtml(base)
                + "/api/forgot-password/authenticator\">Start again</a> | "
                + "<a href=\"" + escapeHtml(base) + "/login.html\">Back to login</a></p>";
        return noStoreHtml(isError ? Response.Status.BAD_REQUEST : Response.Status.OK,
                page("Verify authenticator recovery", content));
    }

    private Response authenticatorEnrollmentStartPage(String actionToken,
                                                      HttpServletRequest request) {
        String content = "<p>Your verification was successful. Continue to connect a new authenticator.</p>"
                + "<form method=\"post\" action=\"" + escapeHtml(base(request))
                + "/api/forgot-password/authenticator/setup\">"
                + "<input type=\"hidden\" name=\"actionToken\" value=\""
                + escapeHtml(actionToken) + "\">"
                + "<button type=\"submit\">Set up new authenticator</button></form>";
        return noStoreHtml(Response.Status.OK, page("Set up new authenticator", content));
    }

    private Response recoveryEnrollmentPage(String actionToken, String email, String secret,
                                            String error, Response.Status status,
                                            HttpServletRequest request)
            throws WriterException, IOException {
        String qrCode = qrCodeDataUri(TwoFactorService.otpAuthUri(secret, email));
        String content = (blank(error) ? "" : "<p role=\"alert\">" + escapeHtml(error) + "</p>")
                + "<p>Scan this QR code with your authenticator app, or enter the setup key manually.</p>"
                + "<p><img alt=\"New authenticator setup QR code\" src=\"" + qrCode
                + "\" width=\"240\" height=\"240\"></p>"
                + "<p>Setup key: <strong>" + escapeHtml(secret) + "</strong></p>"
                + "<p>Account: " + escapeHtml(email)
                + ". Use a 6-digit time-based code with a 30-second interval.</p>"
                + "<form method=\"post\" action=\"" + escapeHtml(base(request))
                + "/api/forgot-password/authenticator/confirm\">"
                + "<input type=\"hidden\" name=\"actionToken\" value=\""
                + escapeHtml(actionToken) + "\">"
                + "<label for=\"code\">Enter the current code to confirm the new authenticator</label><br>"
                + "<input id=\"code\" name=\"code\" inputmode=\"numeric\" pattern=\"[0-9]{6}\" "
                + "autocomplete=\"one-time-code\" required><br><br>"
                + "<button type=\"submit\">Replace authenticator</button></form>";
        return noStoreHtml(status, page("Set up new authenticator", content));
    }

    private Response recoveryEnrollmentRetryPage(String actionToken,
                                                 HttpServletRequest request) {
        String content = "<p role=\"alert\">The code is invalid or setup expired. Continue to generate "
                + "a fresh authenticator setup QR code, then try again.</p>"
                + "<form method=\"post\" action=\"" + escapeHtml(base(request))
                + "/api/forgot-password/authenticator/setup\">"
                + "<input type=\"hidden\" name=\"actionToken\" value=\""
                + escapeHtml(actionToken) + "\">"
                + "<button type=\"submit\">Restart authenticator setup</button></form>";
        return noStoreHtml(Response.Status.BAD_REQUEST,
                page("Confirm new authenticator", content));
    }

    private Response recoveryCodesAndLoginPage(HttpServletRequest request,
                                               List<String> recoveryCodes) {
        StringBuilder codes = new StringBuilder("<ul>");
        for (String code : recoveryCodes) {
            codes.append("<li><code>").append(escapeHtml(code)).append("</code></li>");
        }
        codes.append("</ul>");
        String content = "<p>Your authenticator has been replaced. Your previous recovery codes "
                + "are no longer valid.</p><p>Save these new recovery codes securely. Each works once "
                + "and they will not be shown again.</p>" + codes
                + "<p><a href=\"" + escapeHtml(base(request))
                + "/login.html\">Continue to login</a></p>";
        return noStoreHtml(Response.Status.OK, page("Authenticator recovered", content));
    }

    private String qrCodeDataUri(String value) throws WriterException, IOException {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 240, 240);
        BufferedImage image = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++) {
            for (int x = 0; x < 240; x++) {
                image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
            }
        }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    /** The "enter the code" page, with a Resend button. */
    private Response otpPage(String requestId, String message, boolean isError,
                             HttpServletRequest request) {
        String base = base(request);
        String id = escapeHtml(requestId);

        String body =
                "<p>" + escapeHtml(message) + "</p>"
                        + "<form method=\"post\" action=\"" + base + "/api/forgot-password/verify\">"
                        + "<input type=\"hidden\" name=\"requestId\" value=\"" + id + "\">"
                        + "<label for=\"otp\">Verification code</label><br>"
                        + "<input type=\"text\" id=\"otp\" name=\"otp\" inputmode=\"numeric\" "
                        + "pattern=\"[0-9]{6}\" maxlength=\"6\" autocomplete=\"one-time-code\" "
                        + "title=\"Enter the 6-digit code\" required>"
                        + "<br><br>"
                        + "<button type=\"submit\">Verify</button>"
                        + "</form>"
                        + "<br>"
                        + "<form method=\"post\" action=\"" + base + "/api/forgot-password/resend\">"
                        + "<input type=\"hidden\" name=\"requestId\" value=\"" + id + "\">"
                        + "<button type=\"submit\">Resend code</button>"
                        + "</form>"
                        + "<p>Did not get a code? Wait about a minute, then press Resend. "
                        + "You can receive up to " + MAX_OTPS_PER_DAY + " codes per day.</p>"
                        + "<p><a href=\"" + base + "/forgot-password.html\">Start again</a></p>";

        return html(isError ? Response.Status.BAD_REQUEST : Response.Status.OK,
                page("Enter Verification Code", body));
    }

    /** The "type your new password" page. The reset token travels in a hidden field. */
    private Response resetFormPage(String resetToken, String error, HttpServletRequest request) {
        String base = base(request);
        String notice = blank(error) ? "" : "<p>" + escapeHtml(error) + "</p>";

        String body =
                notice
                        + "<form method=\"post\" action=\"" + base + "/api/forgot-password/reset\">"
                        + "<input type=\"hidden\" name=\"resetToken\" value=\"" + escapeHtml(resetToken) + "\">"
                        + "<label for=\"newPassword\">New password</label><br>"
                        + "<input type=\"password\" id=\"newPassword\" name=\"newPassword\" required>"
                        + "<br><br>"
                        + "<label for=\"confirmPassword\">Confirm new password</label><br>"
                        + "<input type=\"password\" id=\"confirmPassword\" name=\"confirmPassword\" required>"
                        + "<br><br>"
                        + "<button type=\"submit\">Change Password</button>"
                        + "</form>";

        return html(blank(error) ? Response.Status.OK : Response.Status.BAD_REQUEST,
                page("Set New Password", body));
    }

    private Response successPage(HttpServletRequest request) {
        String body =
                "<p>Your password has been changed.</p>"
                        + "<p><a href=\"" + base(request) + "/login.html\">Go to login</a></p>";
        return html(Response.Status.OK, page("Password Changed", body));
    }

    private Response errorPage(Response.Status status, String message, HttpServletRequest request) {
        String base = base(request);
        String body =
                "<p>" + escapeHtml(message) + "</p>"
                        + "<p><a href=\"" + base + "/forgot-password.html\">Start again</a> | "
                        + "<a href=\"" + base + "/login.html\">Return to login</a></p>";
        return html(status, page("Error", body));
    }

    // ==================================================================
    // Small data holders
    // ==================================================================
    private record ResetUser(long userId, String email, String phone) { }

    private record OpenRequest(String id, String status, String channel,
                               String destination, int resendCount, int maxResends,
                               String purpose) { }

    /** otp == null means "nothing was sent" (unknown account, limit reached, ...). */
    private record RequestResult(String requestId, String destination, String otp) { }

    private record RecoveryUser(long userId, String email) { }
}

/*
 * ===========================================================================
 * CHANGE 1 - your existing AuthResource.login(...)
 * ===========================================================================
 * Right after this existing line:
 *
 *     AuthenticatedUser user = userFromResult(result);
 *
 * add:
 *
 *     // A successful password login closes any open forgot-password request.
 *     try {
 *         ForgotPasswordResource.cancelOpenRequests(connection, user.userId());
 *     } catch (SQLException exception) {
 *         LOGGER.log(Level.WARNING, "Could not cancel reset requests.", exception);
 *     }
 *
 * (Only here, after the password was verified. A failed login must never
 * cancel anything, or an attacker could cancel a real user's reset.)
 *
 * ===========================================================================
 * CHANGE 2 - your future "change all my details" feature
 * ===========================================================================
 * Inside the same transaction that updates users / local_auth, call:
 *
 *     ForgotPasswordResource.cancelOpenRequests(connection, userId);
 *
 * when the email, phone number or password changes. The OTP was sent to the
 * OLD email/phone, so any open request must be closed.
 * ===========================================================================
 */