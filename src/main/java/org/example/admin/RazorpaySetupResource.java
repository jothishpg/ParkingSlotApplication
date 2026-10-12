package org.example.admin;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import org.example.config.DatabaseConnection;
import org.example.payment.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.Map;

@Path("admin/razorpay-setup")
@RolesAllowed("ADMIN")
@Produces(MediaType.TEXT_HTML)
public class RazorpaySetupResource {
    private static final String COOKIE = "RAZORPAY_OAUTH_STATE";
    @GET public Response page(@Context HttpServletRequest request) {
        String status="No merchant connected.";
        try (Connection c=DatabaseConnection.getConnection(); Statement s=c.createStatement();
             ResultSet r=s.executeQuery("SELECT config_id,auth_type,mode,account_id FROM razorpay_config WHERE active")) {
            if(r.next()) status="Connected: " + r.getString("auth_type") + " / " + r.getString("mode")
                    + ". Webhook URL: " + base(request) + "/api/payments/razorpay/webhook/" + r.getLong("config_id");
        } catch(Exception e) { status="Apply database migration V5 before setup."; }
        String csrf=PaymentCrypto.random();
        return Response.ok("""
                <!doctype html><meta charset="utf-8"><title>Razorpay Setup</title>
                <h1>Razorpay Setup</h1><p>%s</p>
                <form method="post" action="%s/api/admin/razorpay-setup/authorize">
                <input type="hidden" name="csrf" value="%s">
                <button>Connect your Razorpay account</button></form>
                <details><summary>API-key setup while OAuth is pending</summary>
                <form method="post" action="%s/api/admin/razorpay-setup/keys">
                <input type="hidden" name="csrf" value="%s">
                <label>Key ID <input name="keyId" required></label>
                <label>Key Secret <input type="password" name="keySecret" required autocomplete="new-password"></label>
                <button>Connect merchant keys</button></form></details>
                <p>Configure automatic payment capture in Razorpay. Set up payment.captured, payment.failed,
                order.paid, payment_link.paid, payment_link.expired, payment_link.cancelled and
                account.app.authorization_revoked webhooks with the signing secret supplied by your server administrator.</p>
                <a href="%s/api/admin">Admin Dashboard</a>
                """.formatted(html(status),html(base(request)),csrf,html(base(request)),csrf,html(base(request))))
                .cookie(cookie("RAZORPAY_SETUP_CSRF",csrf,600)).header("Cache-Control","no-store").build();
    }
    @POST @Path("authorize") @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response authorize(@FormParam("csrf") String csrf,
                              @CookieParam("RAZORPAY_SETUP_CSRF") String csrfCookie,
                              @Context SecurityContext security) {
        try {
            csrf(csrf,csrfCookie);
            RazorpayClient.webhookSecret();
            String state=PaymentCrypto.random(); String mode=RazorpayClient.mode();
            String redirect=RazorpayClient.required("RAZORPAY_REDIRECT_URI");
            try(Connection c=DatabaseConnection.getConnection(); PreparedStatement s=c.prepareStatement(
                    "INSERT INTO razorpay_oauth_state(state_hash,admin_id,mode) VALUES (?,?,?)")) {
                s.setString(1,PaymentCrypto.hash(state)); s.setLong(2,user(security)); s.setString(3,mode);
                s.executeUpdate();
            }
            return Response.seeOther(URI.create("https://auth.razorpay.com/authorize?response_type=code&scope=read_write&client_id="
                    +enc(RazorpayClient.required("RAZORPAY_CLIENT_ID"))+"&redirect_uri="+enc(redirect)+"&state="+enc(state)))
                    .cookie(cookie(COOKIE,state,600)).build();
        } catch(Exception e) { return failure(e); }
    }
    @GET @Path("callback")
    public Response callback(@QueryParam("code") String code,@QueryParam("state") String state,
                             @QueryParam("error") String error,@CookieParam(COOKIE) String saved,
                             @Context SecurityContext security,@Context HttpServletRequest request) {
        try {
            csrf(state,saved); String mode;
            RazorpayClient.webhookSecret();
            try(Connection c=DatabaseConnection.getConnection(); PreparedStatement s=c.prepareStatement(
                    "DELETE FROM razorpay_oauth_state WHERE state_hash=? AND admin_id=? AND expires_at>CURRENT_TIMESTAMP RETURNING mode")) {
                s.setString(1,PaymentCrypto.hash(state));s.setLong(2,user(security));
                try(ResultSet r=s.executeQuery()) {
                    if(!r.next()) throw new IllegalArgumentException("Authorization session expired. Connect again.");
                    mode=r.getString(1);
                }
            }
            if(error!=null || code==null || code.isBlank()) throw new IllegalArgumentException("Razorpay authorization was not completed.");
            Map<String,Object> body=MerchantConfig.tokenRequest("authorization_code");
            body.put("code",code);body.put("mode",mode);body.put("redirect_uri",RazorpayClient.required("RAZORPAY_REDIRECT_URI"));
            JsonNode token=RazorpayClient.oauth(body);
            MerchantConfig.save("OAUTH",mode,RazorpayClient.text(token,"razorpay_account_id"),RazorpayClient.text(token,"public_token"),
                    RazorpayClient.text(token,"access_token"),RazorpayClient.text(token,"refresh_token"),token.path("expires_in").asLong(),
                    RazorpayClient.required("RAZORPAY_CLIENT_SECRET"),user(security));
            return Response.seeOther(URI.create(base(request)+"/api/admin/razorpay-setup")).cookie(cookie(COOKIE,"",0)).build();
        } catch(Exception e) { return Response.fromResponse(failure(e)).cookie(cookie(COOKIE,"",0)).build(); }
    }
    @POST @Path("keys") @Consumes(MediaType.APPLICATION_FORM_URLENCODED)
    public Response keys(@FormParam("keyId") String key,@FormParam("keySecret") String secret,
                         @FormParam("csrf") String csrf,
                         @CookieParam("RAZORPAY_SETUP_CSRF") String saved,@Context SecurityContext security,
                         @Context HttpServletRequest request) {
        try {
            csrf(csrf,saved);String mode=RazorpayClient.mode();
            RazorpayClient.webhookSecret();
            if(key==null || !key.startsWith("rzp_"+mode+"_") || secret==null || secret.isBlank())
                throw new IllegalArgumentException("Enter merchant keys matching server payment mode.");
            String auth="Basic "+java.util.Base64.getEncoder().encodeToString((key+":"+secret).getBytes(StandardCharsets.UTF_8));
            RazorpayClient.api("orders?count=1",null,auth);
            MerchantConfig.save("KEYS",mode,null,key,secret,null,null,secret,user(security));
            return Response.seeOther(URI.create(base(request)+"/api/admin/razorpay-setup")).build();
        } catch(Exception e) { return failure(e); }
    }
    public static void csrf(String value,String saved) {
        if(value==null || saved==null || !java.security.MessageDigest.isEqual(value.getBytes(StandardCharsets.UTF_8),saved.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Invalid form session. Reload and try again.");
    }
    private static long user(SecurityContext security) { return Long.parseLong(security.getUserPrincipal().getName()); }
    private static NewCookie cookie(String name,String value,int age) {
        return new NewCookie.Builder(name).value(value).path("/").secure(true).httpOnly(true)
                .sameSite(NewCookie.SameSite.LAX).maxAge(age).build();
    }
    private static String enc(String value) { return URLEncoder.encode(value,StandardCharsets.UTF_8); }
    public static String html(String value) { return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;"); }
    public static String base(HttpServletRequest request) {
        String base=System.getenv("APP_BASE_URL");return base==null || base.isBlank()?request.getContextPath():base.replaceAll("/$","");
    }
    private static Response failure(Exception e) {
        String message=e instanceof IllegalArgumentException || e instanceof IllegalStateException ? e.getMessage():"Unable to connect Razorpay. Check server configuration and retry.";
        return Response.status(400).entity("<p>"+html(message)+"</p><p><a href=\"javascript:history.back()\">Back</a></p>").build();
    }
}
