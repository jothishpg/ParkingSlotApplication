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
        return renderCheckout(id, request, null);
    }

    @GET
    @Path("customer/{id}")
    @PermitAll
    @Produces(MediaType.TEXT_HTML)
    public Response customerCheckout(@PathParam("id") UUID id, @QueryParam("token") String token, @Context HttpServletRequest request) {
        try {
            PaymentMessaging.authorize(id, token);
            return renderCheckout(id, request, token);
        } catch (Exception e) {
            return Response.status(404).entity("<p>Payment link not found.</p>").build();
        }
    }

    private Response renderCheckout(UUID id, HttpServletRequest request, String customerToken) {
        try {
            PaymentService.Checkout c = PaymentService.get(id);
            MerchantConfig config = MerchantConfig.load(c.config());
            if (PaymentLinkService.linkId(id) != null) return nativeLinkPage(c, request);
            if (c.order() == null)
                return Response.status(409).entity("<p>This payment window is closed. Check payment status or search the vehicle again.</p>").build();
            String base = RazorpaySetupResource.base(request);
            String customerLink = "";
            if (customerToken == null) {
                try (Connection db = DatabaseConnection.getConnection(); PreparedStatement s = db.prepareStatement("SELECT payment_link_url FROM payment_request WHERE request_id=?")) {
                    s.setObject(1, id);
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) customerLink = Objects.toString(r.getString(1), "");
                    }
                }
            }
            Map<String, Object> options = new LinkedHashMap<>();
            options.put("key", config.publicKey());
            options.put("amount", c.paise());
            options.put("currency", "INR");
            options.put("name", "Parking payment");
            options.put("description", "Booking #" + c.booking());
            options.put("order_id", c.order());
            options.put("timeout", Math.max(1, c.expires().getEpochSecond() - Instant.now().getEpochSecond()));
            String json = RazorpayClient.JSON.writeValueAsString(options).replace("<", "\\u003c");
            String endpoint = RazorpayClient.JSON.writeValueAsString(base + "/api/payments/razorpay/" + (customerToken == null ? "checkout/" : "customer/") + id).replace("<", "\\u003c");
            String access = customerToken == null ? "" : "?token=" + customerToken;
            return Response.ok("""
                            <!doctype html><meta charset="utf-8"><title>Parking payment</title>
                            <h1>Parking payment</h1><p>Amount: INR %s</p><p id="message">Payment is valid until the displayed request deadline.</p>
                            <button id="pay">Pay with Razorpay</button><button id="check">Check payment status</button>
                            %s
                            <script src="https://checkout.razorpay.com/v1/checkout.js"></script>
                            <script>
                            const options=%s, endpoint=%s, expires=%d, access='%s', initial='%s';
                            const message=document.getElementById('message'), button=document.getElementById('pay');
                            let busy=false, gateway;
                            function show(s) {
                              message.textContent=s==='COMPLETED'||s==='PAID'?'Payment successful. Checkout completed.':
                                s==='REVIEW'?'Payment needs review. Ask the administrator to check before collecting again.':
                                s==='FAILED'?'Payment failed. Retry within the remaining payment window.':
                                s==='EXPIRED'?'Payment window expired. Check status before starting again.':'Payment is pending. Check status if you have already paid.';
                              if(s==='COMPLETED'||s==='PAID'||s==='REVIEW') button.disabled=true;
                            }
                            async function check() {
                              try { const response=await fetch(endpoint+'/status'+access,{cache:'no-store'});const data=await response.json();
                                if(!response.ok) throw Error(data.error||'Unable to check payment status.');show(data.status);
                              } catch(e) { message.textContent=e.message; }
                            }
                            options.handler=async function(result) {
                              try {
                                const data=new URLSearchParams(result);
                                const response=await fetch(endpoint+'/verify'+access,{method:'POST',body:data});
                                const status=await response.json();if(!response.ok) throw Error(status.error||'Unable to verify payment. Use Check payment status.');
                                show(status.status);
                              } catch(e) {message.textContent=e.message;signal('CLIENT_ERROR');} finally {busy=false;}
                            };
                            async function signal(event) {
                              try {await fetch(endpoint+'/event'+access,{method:'POST',body:new URLSearchParams({event})});}catch(e){}
                            }
                            options.modal={ondismiss:function(){busy=false;check().then(()=>signal('CLOSED'));}};
                            button.onclick=function(){if(busy||Date.now()>=expires)return;busy=true;
                              try {gateway=new Razorpay(options);gateway.on('payment.failed',function(){busy=false;check();});gateway.open();}
                              catch(e){busy=false;message.textContent='Unable to open Razorpay. Check your connection and retry.';signal('CLIENT_ERROR');}};
                            document.getElementById('check').onclick=check;
                            const timer=setInterval(function(){if(Date.now()>=expires){button.disabled=true;if(gateway)gateway.close();clearInterval(timer);check();}},1000);
                            if(initial!=='PENDING'||Date.now()>=expires) button.disabled=true;
                            show(initial);
                            </script>
                            """.formatted(java.math.BigDecimal.valueOf(c.paise(), 2), customerToken == null
                                    ? (customerLink.isBlank() ? "" : "<p>Customer payment link: <a href=\"" + RazorpaySetupResource.html(customerLink) + "\">" + RazorpaySetupResource.html(customerLink) + "</a></p><p>SMS and WhatsApp link messages are queued automatically.</p>")
                                    + "<a href=\"" + RazorpaySetupResource.html(base) + "/api/booking/checkout\">Back to checkout</a>" : "",
                            json, endpoint, c.expires().toEpochMilli(), access, c.status()))
                    .header("Cache-Control", "no-store")
                    .header("Referrer-Policy", "no-referrer").header("X-Robots-Tag", "noindex, nofollow").build();
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

    @POST
    @Path("checkout/{id}/verify")
    @RolesAllowed("TICKET_ASSIGNER")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response verify(@PathParam("id") UUID id, @FormParam("razorpay_order_id") String order,
                           @FormParam("razorpay_payment_id") String payment, @FormParam("razorpay_signature") String signature) {
        try {
            return Response.ok(Map.of("status", PaymentService.verify(id, order, payment, signature))).build();
        } catch (Exception e) {
            return error(e);
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

    @GET
    @Path("customer/{id}/status")
    @PermitAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response customerStatus(@PathParam("id") UUID id, @QueryParam("token") String token) {
        try {
            PaymentMessaging.authorize(id, token);
            return Response.ok(Map.of("status", reconcile(id, false))).header("Cache-Control", "no-store").build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        } catch (Exception e) {
            return error(e);
        }
    }

    @POST
    @Path("customer/{id}/verify")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response customerVerify(@PathParam("id") UUID id, @QueryParam("token") String token,
                                   @FormParam("razorpay_order_id") String order, @FormParam("razorpay_payment_id") String payment,
                                   @FormParam("razorpay_signature") String signature) {
        try {
            PaymentMessaging.authorize(id, token);
            return Response.ok(Map.of("status", PaymentService.verify(id, order, payment, signature))).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        } catch (Exception e) {
            return error(e);
        }
    }

    @POST
    @Path("customer/{id}/event")
    @PermitAll
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    @Produces(MediaType.APPLICATION_JSON)
    public Response customerEvent(@PathParam("id") UUID id, @QueryParam("token") String token, @FormParam("event") String event) {
        try {
            PaymentMessaging.authorize(id, token);
            PaymentMessaging.browserEvent(id, event);
            return Response.ok(Map.of("received", true)).build();
        } catch (jakarta.ws.rs.NotFoundException e) {
            return Response.status(404).build();
        } catch (Exception e) {
            return error(e);
        }
    }

    public static String reconcile(UUID id, boolean recover) throws Exception {
        PaymentService.Checkout c = PaymentService.get(id);
        if (c.status().equals("PAID")) return "PAID";
        if (PaymentLinkService.linkId(id) != null || (c.order() == null && c.status().equals("CREATING")))
            return PaymentLinkService.reconcile(id, recover);
        MerchantConfig config = MerchantConfig.load(c.config());
        if (c.order() == null) {
            if (!recover) return "PENDING";
            JsonNode orders = RazorpayClient.api("orders?receipt=" + id + "&count=100", null, config.authorization());
            String order = null;
            for (JsonNode o : orders.path("items"))
                if (id.toString().equals(o.path("receipt").asText()) && o.path("amount").asLong() == c.paise()) {
                    if (order != null)
                        throw new IllegalStateException("Multiple matching orders require manual review.");
                    order = RazorpayClient.text(o, "id");
                }
            if (order == null)
                throw new IllegalStateException("No matching order found. Confirm in Razorpay before resolving this request manually.");
            try (Connection db = DatabaseConnection.getConnection(); PreparedStatement s = db.prepareStatement("UPDATE payment_request SET gateway_order_id=?,status='PENDING' WHERE request_id=? AND gateway_order_id IS NULL")) {
                s.setString(1, order);
                s.setObject(2, id);
                s.executeUpdate();
            }
            c = PaymentService.get(id);
        }
        JsonNode payments = RazorpayClient.api("orders/" + c.order() + "/payments", null, config.authorization());
        boolean authorized = false;
        String result = c.status();
        for (JsonNode payment : payments.path("items")) {
            if (payment.path("status").asText().equals("authorized")) authorized = true;
            String processed = PaymentService.process(c, payment, null, null);
            if (processed.equals("COMPLETED") || processed.equals("REVIEW")) result = processed;
        }
        if (result.equals("COMPLETED") || result.equals("REVIEW")) return result;
        if (!authorized && c.expires().isBefore(Instant.now())) {
            try (Connection db = DatabaseConnection.getConnection(); PreparedStatement s = db.prepareStatement("UPDATE payment_request SET status='EXPIRED' WHERE request_id=? AND status='PENDING'")) {
                s.setObject(1, id);
                s.executeUpdate();
            }
            return PaymentService.get(id).status();
        }
        return result;
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
            if (Set.of("payment_link.paid", "payment_link.partially_paid", "payment_link.expired", "payment_link.cancelled").contains(type)) {
                PaymentLinkService.signedEvent(configId, event, eventId == null ? PaymentCrypto.hash(new String(raw, StandardCharsets.UTF_8)) : eventId);
                return Response.ok(Map.of("received", true)).build();
            }
            if (!Set.of("payment.captured", "payment.failed", "order.paid").contains(type))
                return Response.ok(Map.of("received", true)).build();
            JsonNode p = event.path("payload").path("payment").path("entity");
            String order = p.path("order_id").asText();
            if (order.isBlank()) return Response.status(400).build();
            PaymentService.Checkout checkout = null;
            try (Connection c = DatabaseConnection.getConnection(); PreparedStatement s = c.prepareStatement("SELECT * FROM payment_request WHERE config_id=? AND gateway_order_id=?")) {
                s.setLong(1, configId);
                s.setString(2, order);
                try (ResultSet r = s.executeQuery()) {
                    if (r.next()) checkout = PaymentService.read(r);
                }
            }
            if (checkout == null) {
                // A native link's order may not be known until the next link fetch; the background reconciler handles it.
                return Response.ok(Map.of("received", true, "reconciliation", "pending")).build();
            }
            String payment = RazorpayClient.text(p, "id");
            if (!payment.matches("pay_[A-Za-z0-9]+")) return Response.status(400).build();
            // Raw-body HMAC authenticates the provider payload. Avoid HTTP calls in the webhook's five-second window.
            PaymentService.process(checkout, p, configId, eventId == null ? PaymentCrypto.hash(new String(raw, StandardCharsets.UTF_8)) : eventId);
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
