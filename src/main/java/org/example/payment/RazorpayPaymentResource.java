package org.example.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.example.admin.RazorpaySetupResource;
import org.example.config.DatabaseConnection;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

@Path("payments/razorpay")
public class RazorpayPaymentResource {
    private static final Logger LOG = Logger.getLogger(RazorpayPaymentResource.class.getName());

    @GET
    @Path("checkout/{id}")
    @RolesAllowed("TICKET_ASSIGNER")
    @Produces(MediaType.TEXT_HTML)
    public Response checkout(@PathParam("id") UUID id, @Context HttpServletRequest request) {
        return renderCheckout(id, request);
    }

    private Response renderCheckout(UUID id, HttpServletRequest request) {
        try {
            PaymentService.Checkout c = PaymentService.get(id);
            return nativeLinkPage(c, request);
        } catch (Exception e) {
            return error(e);
        }
    }

    private Response nativeLinkPage(PaymentService.Checkout checkout, HttpServletRequest request) throws Exception {
        String link;
        try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("SELECT payment_link_url FROM payment_request WHERE request_id=?")) {
            s.setObject(1, checkout.id());
            try (ResultSet r = s.executeQuery()) {
                r.next();
                link = r.getString(1);
            }
        }
        String base = RazorpaySetupResource.base(request);
        String endpoint = RazorpayClient.JSON.writeValueAsString(base + "/api/payments/razorpay/checkout/" + checkout.id()).replace("<", "\\u003c");
        boolean linkAvailable = checkout.status().equals("PENDING") && checkout.expires().isAfter(Instant.now())
                && link != null && !link.isBlank();
        String paymentLink = linkAvailable
                ? "<p>Payment link: <a href=\"" + RazorpaySetupResource.html(link)
                + "\" target=\"_blank\" rel=\"noopener noreferrer\">" + RazorpaySetupResource.html(link) + "</a></p>"
                : "This payment window is closed.";
        String paymentQr = linkAvailable
                ? "<p>Or scan this QR code to open the payment link:</p><img width=\"240\" height=\"240\" alt=\"QR code for the Razorpay payment link\" src=\""
                + qrCodeDataUri(link) + "\">"
                : "";
        return Response.ok("""
                        <!doctype html><meta charset="utf-8"><title>Parking payment</title>
                        <h1>Parking payment</h1><p>Amount: INR %s</p><p>Payment link expires: %s (UTC). The charge is frozen until this deadline.</p>
                        %s%s<p>Razorpay SMS and WhatsApp link messages are queued automatically. Status updates use WhatsApp.</p>
                        <p id="status">%s</p><button id="check">Check payment status</button><p><a href="%s/api/booking/checkout">Back to checkout</a></p>
                        <script>
                        const endpoint=%s;let completed=false;
                        async function check(){if(completed)return;try{const r=await fetch(endpoint+'/status',{cache:'no-store'});const d=await r.json();
                          if(!r.ok)throw Error(d.error||'Payment status is unconfirmed. Ask parking staff to check before paying again.');
                          completed=d.status==='COMPLETED'||d.status==='PAID';
                          document.getElementById('status').textContent=completed?'Payment successful. Checkout completed.':
                            d.status==='REVIEW'?'Payment needs administrator review. Do not pay again.':
                            d.status==='EXPIRED'?'Payment link expired. Checkout is not completed.':'Payment is pending. Check status before collecting again.';
                        }catch(e){document.getElementById('status').textContent=e.message;
                          try{await fetch(endpoint+'/event',{method:'POST',body:new URLSearchParams({event:'CLIENT_ERROR'})});}catch(ignored){}
                        }}
                        document.getElementById('check').onclick=check;const timer=setInterval(()=>{if(completed)clearInterval(timer);else check();},15000);
                        </script>
                        """.formatted(java.math.BigDecimal.valueOf(checkout.paise(), 2), checkout.expires(),
                        paymentLink, paymentQr, checkout.status(), RazorpaySetupResource.html(base), endpoint))
                .header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
    }

    static String qrCodeDataUri(String value) throws WriterException, IOException {
        BitMatrix matrix = new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, 240, 240);
        BufferedImage image = new BufferedImage(240, 240, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 240; y++)
            for (int x = 0; x < 240; x++)
                image.setRGB(x, y, matrix.get(x, y) ? 0xff000000 : 0xffffffff);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "PNG", output);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray());
        }
    }

    @GET
    @Path("payment-link/{id}/callback")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public Response linkCallback(@PathParam("id") UUID id, @QueryParam("razorpay_payment_link_id") String link,
                                 @QueryParam("razorpay_payment_link_reference_id") String reference, @QueryParam("razorpay_payment_link_status") String status,
                                 @QueryParam("razorpay_payment_id") String payment, @QueryParam("razorpay_signature") String signature) {
        try {
            String verified = PaymentLinkService.verifyCallback(id, link, reference, status, payment, signature);
            String message = verified.equals("COMPLETED") || verified.equals("PAID") ? "Payment successful. Parking checkout completed." :
                    verified.equals("REVIEW") ? "Payment needs administrator review. Do not pay again." : "Payment is still being verified. Please check with parking staff before paying again.";
            return Response.ok("<!doctype html><meta charset=\"utf-8\"><h1>Parking payment</h1><p>" + message + "</p>").header("Cache-Control", "no-store").header("Referrer-Policy", "no-referrer").build();
        } catch (Exception e) {
            if (!(e instanceof IllegalArgumentException)) PaymentMessaging.serverError(id);
            return Response.status(400).entity("<p>Payment could not be confirmed. Ask parking staff to check the status before paying again.</p>").header("Cache-Control", "no-store").build();
        }
    }

    @GET
    @Path("checkout/{id}/status")
    @RolesAllowed({"TICKET_ASSIGNER", "ADMIN"})
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") UUID id) {
        try {
            return Response.ok(Map.of("status", reconcile(id, false))).header("Cache-Control", "no-store").build();
        } catch (Exception e) {
            return error(e);
        }
    }

    @POST
    @Path("checkout/{id}/event")
    @RolesAllowed("TICKET_ASSIGNER")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response event(@PathParam("id") UUID id, @FormParam("event") String event) {
        try {
            PaymentMessaging.browserEvent(id, event);
            return Response.ok(Map.of("received", true)).build();
        } catch (Exception e) {
            return error(e);
        }
    }

    public static String reconcile(UUID id, boolean recover) throws Exception {
        return PaymentLinkService.reconcile(id, recover);
    }

    @POST
    @Path("webhook/{configId}")
    @PermitAll
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response webhook(@PathParam("configId") long configId, byte[] raw,
                            @HeaderParam("X-Razorpay-Signature") String signature, @HeaderParam("X-Razorpay-Event-Id") String eventId) {
        try {
            if (raw == null || raw.length > 262144) return Response.status(413).build();
            try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("SELECT config_id FROM razorpay_config WHERE config_id=?")) {
                s.setLong(1, configId);
                try (ResultSet r = s.executeQuery()) {
                    if (!r.next()) return Response.status(404).build();
                }
            }
            if (!PaymentCrypto.verify(RazorpayClient.webhookSecret(), raw, signature))
                return Response.status(401).build();
            JsonNode event = RazorpayClient.JSON.readTree(raw);
            String type = event.path("event").asText();
            String eventKey = eventId == null
                    ? PaymentCrypto.hash(new String(raw, StandardCharsets.UTF_8))
                    : eventId;
            if (type.equals("payment.failed")) {
                String result = PaymentLinkService.processFailedPaymentEvent(
                        configId, event, eventKey);
                if (result.equals("IGNORED")) {
                    LOG.warning("Ignored an unassociated or mismatched Razorpay payment.failed event.");
                }
                return Response.ok(Map.of("received", true)).build();
            }
            if (Set.of("payment_link.paid", "payment_link.expired", "payment_link.cancelled").contains(type)) {
                PaymentLinkService.signedEvent(configId, event, eventKey);
                return Response.ok(Map.of("received", true)).build();
            }
            return Response.ok(Map.of("received", true)).build();
        } catch (Exception e) {
            LOG.warning("Razorpay webhook processing failed: " + e.getClass().getSimpleName());
            return Response.status(503).entity(Map.of("error", "Payment reconciliation will be retried.")).build();
        }
    }

    private static Response error(Exception e) {
        LOG.warning("Razorpay checkout operation failed: " + e.getClass().getSimpleName());
        String message = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : "Unable to contact payment service. Check status before collecting again.";
        return Response.status(400).type(MediaType.APPLICATION_JSON).entity(Map.of("error", message)).header("Cache-Control", "no-store").build();
    }
}
