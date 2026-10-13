package org.example.admin;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.example.config.DatabaseConnection;
import org.example.payment.*;

import java.sql.*;
import java.util.UUID;

@Path("admin/payment-reconciliation")
@RolesAllowed("ADMIN")
@Produces(MediaType.TEXT_HTML)
public class PaymentReconciliationResource {
    @GET
    public Response list(@Context HttpServletRequest request) {
        String base = RazorpaySetupResource.base(request);
        StringBuilder html = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><h1>Payment reconciliation</h1>"
                + "<p>Reconcile checks Razorpay and completes a verified captured payment. REVIEW payments require manual investigation; do not collect again until resolved.</p>"
                + "<table border=\"1\"><tr><th>Booking</th><th>Request</th><th>Status</th><th>Amount INR</th><th>Expires</th><th>Action</th></tr>");
        try (Connection c = DatabaseConnection.getConnection(); Statement s = c.createStatement(); ResultSet r = s.executeQuery(
                "SELECT pr.*, (SELECT COUNT(*) FROM payment_attempt pa WHERE pa.request_id=pr.request_id AND pa.disposition='REVIEW') AS review_count "
                        + "FROM payment_request pr ORDER BY pr.pricing_cutoff DESC LIMIT 100")) {
            while (r.next()) {
                PaymentService.Checkout checkout = PaymentService.read(r);
                html.append("<tr><td>").append(checkout.booking()).append("</td><td>").append(checkout.id())
                        .append("</td><td>").append(checkout.status())
                        .append(r.getLong("review_count") > 0 ? " (" + r.getLong("review_count") + " payment(s) need review)" : "").append("</td><td>")
                        .append(java.math.BigDecimal.valueOf(checkout.paise(), 2)).append("</td><td>").append(checkout.expires())
                        .append("</td><td><form method=\"post\" action=\"").append(RazorpaySetupResource.html(base))
                        .append("/api/admin/payment-reconciliation/").append(checkout.id()).append("\">")
                        .append("<button>Reconcile</button></form></td></tr>");
            }
        } catch (Exception e) {
            return Response.status(503).entity("<p>Unable to load payment requests. Apply payment migrations through V14 and check the database.</p>").build();
        }
        html.append("</table>");
        return Response.ok(html.toString()).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("{id}")
    @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response reconcile(@PathParam("id") UUID id, @Context HttpServletRequest request) {
        try {
            String result = RazorpayPaymentResource.reconcile(id, true);
            return Response.ok("<p>Payment status: " + RazorpaySetupResource.html(result) + "</p><a href=\""
                    + RazorpaySetupResource.html(RazorpaySetupResource.base(request)) + "/api/admin/payment-reconciliation\">Back</a>").build();
        } catch (Exception e) {
            String message = e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage() : "Unable to reconcile. Check Razorpay before collecting again.";
            return Response.status(400).entity("<p>" + RazorpaySetupResource.html(message) + "</p>").build();
        }
    }
}
