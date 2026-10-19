package org.example.cliq;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CliqWebhookResourceTest {
    @Test
    void parsesBookingRequestCaseInsensitivelyAndIgnoresTrailingText() {
        var request = CliqWebhookResource.parseBookingRequest(
                "BookingID: 19, Vehicle_Number: TN28MA1829 give me booking");

        assertEquals(19, request.bookingId());
        assertEquals("TN28MA1829", request.vehicleNumber());
    }

    @Test
    void parsesVehicleNumbersAndNormalizesSeparators() {
        var oneLetterSeries = CliqWebhookResource.parseBookingRequest(
                "booking_id : 19, vehicle_number : TN29C1903");
        var twoLetterSeries = CliqWebhookResource.parseBookingRequest(
                "BOOKING ID:20; VEHICLE NUMBER: Tn 38-MD 2839 give me booking");

        assertEquals("TN29C1903", oneLetterSeries.vehicleNumber());
        assertEquals(20, twoLetterSeries.bookingId());
        assertEquals("TN38MD2839", twoLetterSeries.vehicleNumber());
    }

    @Test
    void rejectsMissingOrNonPositiveBookingIdentifiers() {
        assertNull(CliqWebhookResource.parseBookingRequest("hello"));
        assertNull(CliqWebhookResource.parseBookingRequest(
                "BookingID:0, Vehicle_Number:TN28MA1829"));
    }

    @Test
    void detectsFeeRateAndChargeQuestionsCaseInsensitively() {
        org.junit.jupiter.api.Assertions.assertTrue(
                CliqWebhookResource.isFeeDetailsRequest("Show me the TWO WHEELER fees"));
        org.junit.jupiter.api.Assertions.assertTrue(
                CliqWebhookResource.isFeeDetailsRequest("What is the parking rate?"));
        org.junit.jupiter.api.Assertions.assertFalse(
                CliqWebhookResource.isFeeDetailsRequest("BookingID:19, Vehicle_Number:TN28MA1829"));
    }
}
