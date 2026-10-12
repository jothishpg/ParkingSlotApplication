package org.example.payment;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RazorpayPaymentResourceTest {
    @Test
    void paymentLinkQrCodeIsAnEmbeddedPng() throws Exception {
        String dataUri=RazorpayPaymentResource.qrCodeDataUri(
                "https://rzp.io/example-payment-link?token=abc123");

        assertTrue(dataUri.startsWith("data:image/png;base64,"));
        byte[] png=Base64.getDecoder().decode(dataUri.substring(dataUri.indexOf(',')+1));
        var image=ImageIO.read(new ByteArrayInputStream(png));
        assertEquals(240,image.getWidth());
        assertEquals(240,image.getHeight());
    }
}
