package org.example.payment;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class PaymentVerificationTest {
    private final Instant cutoff=Instant.parse("2026-10-10T10:00:00Z");
    private PaymentService.Checkout checkout(String status) {
        return new PaymentService.Checkout(UUID.randomUUID(),1,1,3400,60,cutoff,cutoff.plusSeconds(PaymentService.PAYMENT_WINDOW_SECONDS),"order_test",status);
    }
    private ObjectNode payment() {
        ObjectNode p=RazorpayClient.JSON.createObjectNode();
        p.put("id","pay_test").put("order_id","order_test").put("amount",3400).put("currency","INR")
                .put("status","captured").put("captured",true).put("amount_refunded",0).put("created_at",cutoff.getEpochSecond()+30);
        return p;
    }
    @Test void verifiesPublishedHmacVectorAndRejectsTampering() {
        // RFC 4231, test case 2: independent known HMAC-SHA256 result.
        byte[] data="what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String signature="5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843";
        assertTrue(PaymentCrypto.verify("Jefe",data,signature));
        assertFalse(PaymentCrypto.verify("wrong secret",data,signature));
        assertFalse(PaymentCrypto.verify("Jefe","tampered".getBytes(StandardCharsets.UTF_8),signature));
        assertFalse(PaymentCrypto.verify("Jefe",data,null));
        assertFalse(PaymentCrypto.verify("Jefe",data,"xyz"));
    }
    @Test void onlyCapturedMatchingPaymentCompletesCheckout() {
        assertEquals("COMPLETED",PaymentService.disposition(checkout("PENDING"),payment(),false));
        ObjectNode p=payment();p.put("status","authorized").put("captured",false);
        assertEquals("PENDING",PaymentService.disposition(checkout("PENDING"),p,false));
        p.put("status","failed");
        assertEquals("FAILED",PaymentService.disposition(checkout("PENDING"),p,false));
        p.put("status","captured");
        assertEquals("PENDING",PaymentService.disposition(checkout("PENDING"),p,false));
    }
    @Test void mismatchedOrderAmountCurrencyOrRefundNeedsReview() {
        for(String field:new String[]{"order_id","amount","currency","amount_refunded"}) {
            ObjectNode p=payment();
            switch(field) {
                case "order_id"->p.put(field,"order_other");
                case "amount"->p.put(field,3456);
                case "currency"->p.put(field,"USD");
                default->p.put(field,100);
            }
            assertEquals("REVIEW",PaymentService.disposition(checkout("PENDING"),p,false),field);
        }
    }
    @Test void fifteenMinuteWindowUsesProviderCreationTimeAndStrictExpiry() {
        PaymentService.Checkout c=checkout("EXPIRED");
        assertTrue(PaymentService.withinWindow(c,cutoff.getEpochSecond()));
        assertTrue(PaymentService.withinWindow(c,cutoff.getEpochSecond()+899));
        assertFalse(PaymentService.withinWindow(c,cutoff.getEpochSecond()-1));
        assertFalse(PaymentService.withinWindow(c,cutoff.getEpochSecond()+900));
        // Delayed webhook for a payment initiated in the valid window can still complete.
        assertEquals("COMPLETED",PaymentService.disposition(c,payment(),false));
        ObjectNode late=payment();late.put("created_at",cutoff.getEpochSecond()+900);
        assertEquals("REVIEW",PaymentService.disposition(c,late,false));
    }
    @Test void extraPaymentAfterCashOrOnlineCheckoutNeedsReview() {
        assertEquals("REVIEW",PaymentService.disposition(checkout("PENDING"),payment(),true));
        assertEquals("REVIEW",PaymentService.disposition(checkout("PAID"),payment(),false));
    }
}
