package org.example.notification;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.mail.internet.InternetAddress;
import org.example.config.DatabaseConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;

import java.util.*;

/** Reads headers only. Claims before sending so ambiguous timeouts cannot cause reply loops. */
public final class GmailAutoReplyService {
    public static final String NOTICE = "This is an automated message. This mailbox is not monitored. "
            + "Please do not reply to this email. For assistance, contact your parking administrator. "
            + "Never share your OTP with anyone.";
    private GmailAutoReplyService() {}


    public record Reply(String recipient, String subject, String messageId) {}

    public static Reply replyFor(JsonNode message, String mailbox) {
        for (JsonNode label : message.path("labelIds"))
            if (Set.of("SENT", "DRAFT", "SPAM", "TRASH").contains(label.asText())) return null;
        Map<String,String> headers = new HashMap<>();
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
        } catch (Exception invalidAddress) { return null; }
    }

    public static void poll() throws Exception {
        if ("false".equalsIgnoreCase(System.getenv("GMAIL_AUTO_REPLY_ENABLED"))) return;
        try (Connection c=DatabaseConnection.getConnection()) {
            try (Statement s=c.createStatement(); ResultSet r=s.executeQuery("SELECT pg_try_advisory_lock(71420591)")) {
                r.next(); if(!r.getBoolean(1))return;
            }
            try {scan(c);}
            finally {try(Statement s=c.createStatement()){s.execute("SELECT pg_advisory_unlock(71420591)");}}
        }
    }
    private static void scan(Connection c) throws Exception {
        String sender,scopes,page; java.time.Instant start;
        try(Statement s=c.createStatement();ResultSet r=s.executeQuery(
                "SELECT sender_email,granted_scopes,auto_reply_started_at,auto_reply_page_token FROM email_sender_config WHERE config_id=1")) {
            if(!r.next())return;
            sender=r.getString(1);scopes=r.getString(2);start=r.getTimestamp(3).toInstant();page=r.getString(4);
        }
        if(scopes==null || !Arrays.asList(scopes.split("\\s+")).contains(GmailEmailService.METADATA_SCOPE))return;
        if(!sender.equalsIgnoreCase(GmailEmailService.connectedSender()))return;
        String path="messages?labelIds=INBOX&maxResults=50";
        if(page!=null)path+="&pageToken="+URLEncoder.encode(page,StandardCharsets.UTF_8);
        JsonNode list;
        try {list=GmailEmailService.inboxMetadata(sender,path);}
        catch(Exception e){savePage(c,sender,null);throw e;}
        for(JsonNode item:list.path("messages")) {
            String id=item.path("id").asText();
            if(!id.matches("[A-Za-z0-9_-]+"))continue;
            try(PreparedStatement s=c.prepareStatement("SELECT 1 FROM gmail_auto_reply WHERE sender_email=? AND gmail_message_id=?")) {
                s.setString(1,sender);s.setString(2,id);try(ResultSet r=s.executeQuery()){if(r.next())continue;}
            }
            JsonNode message;
            try {message=GmailEmailService.inboxMetadata(sender,"messages/"+id+"?format=metadata");}
            catch(GmailEmailService.EmailSetupException e) {
                if(e.getMessage().contains("HTTP 404"))continue;
                throw e;
            }
            Reply reply=message.path("internalDate").asLong(0)<start.toEpochMilli()?null:replyFor(message,sender);
            try(PreparedStatement s=c.prepareStatement("INSERT INTO gmail_auto_reply(sender_email,gmail_message_id,status) VALUES (?,?,?) ON CONFLICT DO NOTHING")) {
                s.setString(1,sender);s.setString(2,id);s.setString(3,reply==null?"SKIPPED":"SENDING");
                if(s.executeUpdate()!=1 || reply==null)continue;
            }
            String error=null;
            try {GmailEmailService.sendNoReplyNotice(sender,reply.recipient(),reply.subject(),reply.messageId());}
            catch(Exception e){error=e.getClass().getSimpleName();}
            try(PreparedStatement s=c.prepareStatement("UPDATE gmail_auto_reply SET status=?,submitted_at=CASE WHEN ? THEN CURRENT_TIMESTAMP ELSE NULL END,last_error=? WHERE sender_email=? AND gmail_message_id=?")) {
                s.setString(1,error==null?"SUBMITTED":"UNCERTAIN");s.setBoolean(2,error==null);s.setString(3,error);s.setString(4,sender);s.setString(5,id);s.executeUpdate();
            }
        }
        savePage(c,sender,list.has("nextPageToken")?list.path("nextPageToken").asText():null);
    }
    private static void savePage(Connection c,String sender,String page) throws SQLException {
        try(PreparedStatement s=c.prepareStatement("UPDATE email_sender_config SET auto_reply_page_token=? WHERE config_id=1 AND sender_email=?")) {
            s.setString(1,page);s.setString(2,sender);s.executeUpdate();
        }
    }
}