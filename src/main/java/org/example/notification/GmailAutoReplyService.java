package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.internet.InternetAddress;
import org.example.config.DatabaseConnection;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

import java.util.*;

/**
 * Reads headers only. Claims before sending so ambiguous timeouts cannot cause reply loops.
 */
public final class GmailAutoReplyService {
    public static final String NOTICE = "This is an automated message. This mailbox is not monitored. "
            + "Please do not reply to this email. For assistance, contact your parking administrator. "
            + "Never share your OTP with anyone.";

    private GmailAutoReplyService() {
    }


    public record Reply(String recipient, String subject, String messageId) {
    }

    public static Reply replyFor(JsonNode message, String mailbox) {
        for (JsonNode label : message.path("labelIds"))
            if (Set.of("SENT", "DRAFT", "SPAM", "TRASH").contains(label.asText())) return null;
        Map<String, String> headers = new HashMap<>();
        for (JsonNode header : message.path("payload").path("headers"))
            headers.put(header.path("name").asText().toLowerCase(Locale.ROOT), header.path("value").asText());
        String automated = headers.getOrDefault("auto-submitted", "no").trim();
        if (!automated.equalsIgnoreCase("no") || headers.containsKey("list-id")
                || headers.containsKey("list-unsubscribe") || headers.containsKey("x-auto-response-suppress")
                || headers.getOrDefault("return-path", "").trim().equals("<>")) return null;
        String precedence = headers.getOrDefault("precedence", "").trim().toLowerCase(Locale.ROOT);
        if (Set.of("bulk", "list", "junk").contains(precedence)) return null;
        try {
            InternetAddress[] addresses = InternetAddress.parse(headers.getOrDefault("from", ""), true);
            if (addresses.length != 1) return null;
            addresses[0].validate();
            String recipient = addresses[0].getAddress();
            if (recipient == null || !recipient.contains("@") || recipient.equalsIgnoreCase(mailbox)) return null;
            String local = recipient.substring(0, recipient.indexOf('@')).toLowerCase(Locale.ROOT);
            if (local.matches(".*(no[._-]?reply|do[._-]?not[._-]?reply|mailer-daemon|postmaster).*")) return null;
            String subject = headers.getOrDefault("subject", "Your email").replaceAll("[\\p{Cntrl}]", " ");
            if (subject.length() > 200) subject = subject.substring(0, 200);
            if (!subject.regionMatches(true, 0, "Re:", 0, 3)) subject = "Re: " + subject;
            String id = headers.get("message-id");
            if (id != null && !id.matches("<[^<>\\s]+@[^<>\\s]+>")) id = null;
            return new Reply(recipient, subject, id);
        } catch (Exception invalidAddress) {
            return null;
        }
    }

    public static void poll() throws Exception {
        // The listener runs one scanner. Each message is claimed in the database before sending.
        try (Connection connection = DatabaseConnection.getConnection()) {
            scan(connection);
        }
    }

    private record Mailbox(String sender, String scopes, java.time.Instant startedAt, String pageToken) {
    }

    private static Mailbox loadMailbox(Connection connection) throws SQLException {
        String sql = "SELECT sender_email, granted_scopes, auto_reply_started_at, auto_reply_page_token "
                + "FROM email_sender_config WHERE config_id = 1";
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            if (!result.next()) return null;
            return new Mailbox(result.getString("sender_email"), result.getString("granted_scopes"),
                    result.getTimestamp("auto_reply_started_at").toInstant(), result.getString("auto_reply_page_token"));
        }
    }

    private static void scan(Connection connection) throws Exception {
        Mailbox mailbox = loadMailbox(connection);
        if (mailbox == null || mailbox.scopes() == null
                || !Arrays.asList(mailbox.scopes().split("\\s+")).contains(GmailEmailService.METADATA_SCOPE)) return;
        if (!mailbox.sender().equalsIgnoreCase(GmailEmailService.connectedSender())) return;

        String path = "messages?labelIds=INBOX&maxResults=50";
        if (mailbox.pageToken() != null) {
            path += "&pageToken=" + URLEncoder.encode(mailbox.pageToken(), StandardCharsets.UTF_8);
        }
        JsonNode inbox;
        try {
            inbox = GmailEmailService.inboxMetadata(mailbox.sender(), path);
        } catch (Exception exception) {
            savePage(connection, mailbox.sender(), null);
            throw exception;
        }

        for (JsonNode item : inbox.path("messages")) {
            processMessage(connection, mailbox, item.path("id").asText());
        }
        savePage(connection, mailbox.sender(), inbox.has("nextPageToken") ? inbox.path("nextPageToken").asText() : null);
    }

    private static void processMessage(Connection connection, Mailbox mailbox, String messageId) throws Exception {
        if (!messageId.matches("[A-Za-z0-9_-]+") || alreadyProcessed(connection, mailbox.sender(), messageId)) return;
        JsonNode message;
        try {
            message = GmailEmailService.inboxMetadata(mailbox.sender(), "messages/" + messageId + "?format=metadata");
        } catch (GmailEmailService.EmailSetupException exception) {
            if (exception.getMessage().contains("HTTP 404")) return;
            throw exception;
        }
        Reply reply = message.path("internalDate").asLong(0) < mailbox.startedAt().toEpochMilli()
                ? null : replyFor(message, mailbox.sender());
        if (!claimMessage(connection, mailbox.sender(), messageId, reply == null ? "SKIPPED" : "SENDING")) return;
        if (reply == null) return;

        String error = null;
        try {
            GmailEmailService.sendNoReplyNotice(mailbox.sender(), reply.recipient(), reply.subject(), reply.messageId());
        } catch (Exception exception) {
            // A timeout can occur after Gmail accepted the mail. Record uncertainty rather than sending twice.
            error = exception.getClass().getSimpleName();
        }
        recordResult(connection, mailbox.sender(), messageId, error);
    }

    private static boolean alreadyProcessed(Connection connection, String sender, String messageId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM gmail_auto_reply WHERE sender_email = ? AND gmail_message_id = ?")) {
            statement.setString(1, sender);
            statement.setString(2, messageId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static boolean claimMessage(Connection connection, String sender, String messageId, String status) throws SQLException {
        String sql = "INSERT INTO gmail_auto_reply (sender_email, gmail_message_id, status) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sender);
            statement.setString(2, messageId);
            statement.setString(3, status);
            return statement.executeUpdate() == 1;
        }
    }

    private static void recordResult(Connection connection, String sender, String messageId, String error) throws SQLException {
        String sql = "UPDATE gmail_auto_reply SET status = ?, "
                + "submitted_at = CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END, last_error = ? "
                + "WHERE sender_email = ? AND gmail_message_id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, error == null ? "SUBMITTED" : "UNCERTAIN");
            statement.setBoolean(2, error == null);
            statement.setString(3, error);
            statement.setString(4, sender);
            statement.setString(5, messageId);
            statement.executeUpdate();
        }
    }

    private static void savePage(Connection connection, String sender, String pageToken) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE email_sender_config SET auto_reply_page_token = ? WHERE config_id = 1 AND sender_email = ?")) {
            statement.setString(1, pageToken);
            statement.setString(2, sender);
            statement.executeUpdate();
        }
    }
}
