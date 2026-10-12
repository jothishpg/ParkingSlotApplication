package org.example.payment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class MerchantConfigTest {
    @Test
    void detectsPaymentModeFromTheMerchantKey() {
        assertEquals("test", MerchantConfig.keyMode("rzp_test_example123"));
        assertEquals("live", MerchantConfig.keyMode("rzp_live_example123"));
    }

    @Test
    void rejectsMissingOrMalformedKeys() {
        for (String key : new String[]{null, "", "rzp_test_", "rzp_other_example", "rzp_test_example/extra"}) {
            assertThrows(IllegalArgumentException.class, () -> MerchantConfig.keyMode(key));
        }
    }

    @Test
    void usesTheSameMerchantSecretForBasicAuthentication() {
        MerchantConfig merchant = new MerchantConfig(1, "rzp_test_example", "example-secret", true);
        String authorization = merchant.authorization();
        assertTrue(authorization.startsWith("Basic "));
        assertEquals("rzp_test_example:example-secret", new String(
                Base64.getDecoder().decode(authorization.substring(6)), StandardCharsets.UTF_8));
    }
}
