package org.example.payment;

import org.example.notification.WhatsAppNotificationService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class PaymentMessagingTest {
    @Test void paymentSuccessUsesCheckoutValuesInTheExistingTemplateOrder() {
        var values=WhatsAppNotificationService.checkoutTemplateParameters("TN123","A1",
                new java.math.BigDecimal("65"),new java.math.BigDecimal("34.00"));
        assertEquals(java.util.List.of("driver_name","slot_name","total_duration","amount"),
                new java.util.ArrayList<>(values.keySet()));
        assertEquals(java.util.List.of("TN123","A1","65 min","34.00"),
                new java.util.ArrayList<>(values.values()));
        assertFalse(values.containsKey("start_time"));
    }
    @Test void confirmedFailureResendsOnlyAStillValidLink() {
        String link="https://rzp.io/example";
        for(String event : new String[]{"FAILED","FAILED_NETWORK","FAILED_SERVER"}) {
            String message=PaymentMessaging.text(event,1,3400,link,Instant.now().plusSeconds(900));
            assertTrue(message.contains("resending the same payment link: " + link));
            assertTrue(message.contains("expiry has not been extended"));
            String expired=PaymentMessaging.text(event,1,3400,link,Instant.now().minusSeconds(1));
            assertFalse(expired.contains(link));assertTrue(expired.contains("fresh link"));
        }
        assertTrue(PaymentMessaging.smsLinkEvent("FAILED:pay_123"));
        assertFalse(PaymentMessaging.smsLinkEvent("LINK"));
        assertFalse(PaymentMessaging.smsLinkEvent("SERVER_ERROR"));
        assertFalse(PaymentMessaging.smsLinkEvent("CLIENT_ERROR"));
    }
    @Test void razorpaySendsTheInitialPaymentLinkBySmsWithoutEmail() {
        assertEquals(Map.of("sms",true,"email",false),PaymentService.paymentLinkNotifications());
    }
    @Test void uncertainErrorsNeverOfferAPaymentRetry() {
        for(String event : new String[]{"CLIENT_ERROR","SERVER_ERROR"}) {
            String message=PaymentMessaging.text(event,1,3400,"https://rzp.io/example",Instant.now().plusSeconds(900));
            assertTrue(message.contains("unconfirmed"));
            assertFalse(message.contains("resending"));assertFalse(message.contains("https://rzp.io/example"));
        }
    }
    @Test void closingCheckoutDoesNotClaimPaymentFailedOrSucceeded() {
        String message=PaymentMessaging.text("CLOSED",1,3400,null,Instant.now());
        assertTrue(message.contains("may still be processing"));
        assertFalse(message.contains("Payment successful"));
        assertFalse(message.contains("payment attempt failed"));
    }
    @Test void linkMessageContainsExactAmountAndExpiry() {
        Instant expiry=Instant.parse("2026-10-11T10:05:00Z");
        String message=PaymentMessaging.text("LINK",12,3400,"https://example.com/pay?token=secret",expiry);
        assertTrue(message.contains("INR 34.00"));assertTrue(message.contains("https://example.com/pay?token=secret"));
        assertTrue(message.contains(expiry.toString()));
    }
    @Test void bookingTemplateKeepsFiveValuesAndAppendsPaymentDetails() {
        Map<String,String> parameters=WhatsAppNotificationService.paymentTemplateParameters(19,"TN123","First","A1","10:00","Payment pending; https://example.com/pay");
        assertEquals(5,parameters.size());assertEquals("19",parameters.get("driver_name"));
        assertEquals("TN123",parameters.get("vehicle_number"));
        assertEquals("First",parameters.get("floor_name"));assertEquals("A1",parameters.get("slot_name"));
        assertEquals("10:00 | Payment pending; https://example.com/pay",parameters.get("start_time"));
    }
    @Test void alertTemplateUsesFillerValuesAndIncludesTheAdminMessage() {
        Map<String,String> parameters=WhatsAppNotificationService.alertTemplateParameters(
                "Parking closes at 6 PM", "First", "Alert");
        assertEquals(java.util.List.of("driver_name","vehicle_number","floor_name","slot_name","start_time"),
                new java.util.ArrayList<>(parameters.keySet()));
        assertEquals(java.util.List.of("Parking Alert","ALERT","First","Alert",
                        "Parking alert | Parking closes at 6 PM"),
                new java.util.ArrayList<>(parameters.values()));
    }
    @Test void messageEventsCannotAcceptArbitraryStatusClaims() {
        assertThrows(IllegalArgumentException.class,()->PaymentMessaging.text("UNKNOWN",1,3400,null,Instant.now()));
    }
    @Test void providerConfirmedNetworkAndServerFailuresHaveDistinctMessages() {
        var payment=RazorpayClient.JSON.createObjectNode().put("error_reason","network_error");
        assertEquals("FAILED_NETWORK",PaymentMessaging.failureEvent(payment));
        payment.put("error_reason","request_timed_out");assertEquals("FAILED_NETWORK",PaymentMessaging.failureEvent(payment));
        payment.put("error_reason","").put("error_code","SERVER_ERROR");
        assertEquals("FAILED_SERVER",PaymentMessaging.failureEvent(payment));
        payment.put("error_code","BAD_REQUEST_ERROR");assertEquals("FAILED",PaymentMessaging.failureEvent(payment));
        assertTrue(PaymentMessaging.text("FAILED_SERVER",1,3400,null,Instant.now()).contains("Checkout is not confirmed"));
    }
}
