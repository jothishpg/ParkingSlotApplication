package org.example.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GmailAutoReplyServiceTest {
    private final ObjectMapper json=new ObjectMapper();
    private ObjectNode incoming(String from) {
        ObjectNode message=json.createObjectNode();
        message.putArray("labelIds").add("INBOX");
        var headers=message.putObject("payload").putArray("headers");
        headers.addObject().put("name","From").put("value",from);
        headers.addObject().put("name","Subject").put("value","Re: Password reset");
        headers.addObject().put("name","Message-ID").put("value","<incoming@example.com>");
        return message;
    }
    private ObjectNode header(ObjectNode message,String name,String value) {
        ((com.fasterxml.jackson.databind.node.ArrayNode)message.path("payload").path("headers"))
                .addObject().put("name",name).put("value",value);
        return message;
    }
    @Test void humanReplyUsesSenderAndReplyHeadersWithoutMessageBody() {
        var reply=GmailAutoReplyService.replyFor(incoming("John <john@example.com>"),"parking@example.com");
        assertNotNull(reply);assertEquals("john@example.com",reply.recipient());
        assertEquals("Re: Password reset",reply.subject());assertEquals("<incoming@example.com>",reply.messageId());
        assertTrue(GmailAutoReplyService.NOTICE.contains("mailbox is not monitored"));
        assertTrue(GmailAutoReplyService.NOTICE.contains("Never share your OTP"));
    }
    @Test void ignoresAutomatedResponsesAndMailingLists() {
        for(String[] pair:new String[][]{{"Auto-Submitted","auto-replied"},{"auto-submitted","auto-generated"},
                {"Precedence","bulk"},{"List-Id","customers.example.com"},{"List-Unsubscribe","<https://example.com>"},
                {"Return-Path","<>"},{"X-Auto-Response-Suppress","All"}})
            assertNull(GmailAutoReplyService.replyFor(header(incoming("john@example.com"),pair[0],pair[1]),"parking@example.com"));
        assertNotNull(GmailAutoReplyService.replyFor(header(incoming("john@example.com"),"Auto-Submitted","no"),"parking@example.com"));
    }
    @Test void ignoresSelfBouncesAndNoReplyAddresses() {
        for(String from:new String[]{"PARKING@example.com","no-reply@example.com","noreply@example.com",
                "mailer-daemon@example.com","postmaster@example.com","one@example.com, two@example.com","invalid"})
            assertNull(GmailAutoReplyService.replyFor(incoming(from),"parking@example.com"),from);
    }
    @Test void ignoresSentDraftSpamAndTrashMessages() {
        for(String label:new String[]{"SENT","DRAFT","SPAM","TRASH"}) {
            ObjectNode message=incoming("john@example.com");
            message.putArray("labelIds").add(label);
            assertNull(GmailAutoReplyService.replyFor(message,"parking@example.com"));
        }
    }
    @Test void stripsHeaderControlCharactersAndDiscardsInvalidMessageIds() {
        ObjectNode message=header(incoming("john@example.com"),"Subject","Hello\r\nInjected: value");
        header(message,"Message-ID","<bad@example.com>\r\nInjected: value");
        var reply=GmailAutoReplyService.replyFor(message,"parking@example.com");
        assertNotNull(reply);assertFalse(reply.subject().contains("\r"));assertFalse(reply.subject().contains("\n"));
        assertTrue(reply.subject().startsWith("Re: "));assertNull(reply.messageId());
    }
    @Test void doesNotUseUntrustedReplyToToRedirectAutomaticMail() {
        var reply=GmailAutoReplyService.replyFor(header(incoming("john@example.com"),"Reply-To","other@example.com"),"parking@example.com");
        assertEquals("john@example.com",reply.recipient());
    }
}
