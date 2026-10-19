package org.example.booking;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BookingResourceTest {
    @Test
    void validatesBothSupportedVehicleNumberFormatsAfterNormalization() {
        assertTrue(BookingResource.isValidVehicleNumber(
                BookingResource.normalizeVehicleNumber("ab01a1234")));
        assertTrue(BookingResource.isValidVehicleNumber(
                BookingResource.normalizeVehicleNumber("AB 01-CD 1234")));
    }

    @Test
    void rejectsVehicleNumbersOutsideTheSupportedFormats() {
        assertFalse(BookingResource.isValidVehicleNumber(
                BookingResource.normalizeVehicleNumber("TN01AB12123")));
        assertFalse(BookingResource.isValidVehicleNumber(
                BookingResource.normalizeVehicleNumber("AB1A1234")));
    }
}
