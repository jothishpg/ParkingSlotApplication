package org.example.payment;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PaymentLinkServiceTest {
    private final UUID request=UUID.randomUUID();
    private PaymentService.Checkout checkout() {
        Instant now=Instant.parse("2026-10-11T10:00:00Z");
        return new PaymentService.Checkout(request,1,1,3400,1,now,now.plusSeconds(900),null,"PENDING");
    }
    private ObjectNode link() {
        return RazorpayClient.JSON.createObjectNode().put("reference_id",request.toString()).put("amount",3400)
                .put("currency","INR").put("accept_partial",false);
    }
    @Test void linkMustBelongToThisRequestWithExactAmountAndCurrency() {
        assertTrue(PaymentLinkService.matches(checkout(),link()));
        assertFalse(PaymentLinkService.matches(checkout(),link().put("reference_id",UUID.randomUUID().toString())));
        assertFalse(PaymentLinkService.matches(checkout(),link().put("amount",3456)));
        assertFalse(PaymentLinkService.matches(checkout(),link().put("currency","USD")));
        assertFalse(PaymentLinkService.matches(checkout(),link().put("accept_partial",true)));
        ObjectNode missing=link();missing.remove("accept_partial");assertFalse(PaymentLinkService.matches(checkout(),missing));
    }
    @Test void callbackSignaturePayloadUsesRazorpayFieldOrder() {
        assertEquals("plink_123|reference|paid|pay_123",PaymentLinkService.callbackPayload("plink_123","reference","paid","pay_123"));
    }
    @Test void applicationWindowIsFifteenMinutes() {assertEquals(900,PaymentService.PAYMENT_WINDOW_SECONDS);}
    @Test void definitiveClientRejectionsDifferFromAmbiguousServerErrors() {
        assertTrue(new RazorpayClient.ApiException(400).definitiveRejection());
        assertTrue(new RazorpayClient.ApiException(401).definitiveRejection());
        assertFalse(new RazorpayClient.ApiException(429).definitiveRejection());
        assertFalse(new RazorpayClient.ApiException(500).definitiveRejection());
        assertFalse(new RazorpayClient.ApiException(408).definitiveRejection());
    }
    @Test void providerRejectionExplainsTheSpecificValidationProblem() {
        String response="{\"error\":{\"code\":\"BAD_REQUEST_ERROR\",\"description\":\"expire_by should be at least 15 minutes in the future\"}}";
        var error=new RazorpayClient.ApiException(400,response);
        assertTrue(error.definitiveRejection());
        assertTrue(error.getMessage().contains("expire_by should be at least 15 minutes"));
        assertFalse(error.getMessage().contains("account activation"));
        assertEquals("Razorpay request failed (HTTP 400).",new RazorpayClient.ApiException(400,"<html>Bad request</html>").getMessage());
    }
    @Test void errorMessagesDoNotExposeUrlsKeysOrPhoneNumbers() {
        String response="{\"error\":{\"description\":\"Rejected https://example.com/pay?token=secret rzp_test_example +919876543210\"}}";
        String message=new RazorpayClient.ApiException(400,response).getMessage();
        assertFalse(message.contains("token=secret"));assertFalse(message.contains("rzp_test_example"));
        assertFalse(message.contains("9876543210"));
    }
}
