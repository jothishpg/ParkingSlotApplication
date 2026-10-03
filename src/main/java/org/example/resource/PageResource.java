package org.example.resource;

import jakarta.annotation.security.RolesAllowed;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

@Path("/pages")
public class PageResource {
    private static final String APP_BASE_URL =
            setting("APP_BASE_URL");

    @GET
    @Path("/admin")
    @RolesAllowed("ADMIN")
    @Produces(MediaType.TEXT_HTML)
    public Response adminPage(@Context HttpServletRequest request) {
        return renderPage("pages/admin.html", request);
    }

    @GET
    @Path("/user")
    @RolesAllowed("USER")
    @Produces(MediaType.TEXT_HTML)
    public Response driverPage(@Context HttpServletRequest request) {
        return renderPage("pages/user.html", request);
    }

    @GET
    @Path("/ticket-assigner")
    @RolesAllowed("TICKET_ASSIGNER")
    @Produces(MediaType.TEXT_HTML)
    public Response ticketAssignerPage(@Context HttpServletRequest request) {
        return renderPage("pages/ticket-assigner.html", request);
    }

    private Response renderPage(String resourcePath, HttpServletRequest request) {
        String html;

        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(resourcePath + " not found")
                        .build();
            }
            html = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity("Unable to load " + resourcePath)
                    .build();
        }

        String rendered = html.replace("{{BASE_URL}}", base(request));

        return Response.ok(rendered).build();
    }

    private String base(HttpServletRequest request) {
        if (APP_BASE_URL != null && !APP_BASE_URL.isBlank()) {
            return APP_BASE_URL;
        }
        return request.getScheme() + "://" + request.getServerName() + request.getContextPath();
    }

    private static String setting(String environmentName) {
        String environmentValue = System.getenv(environmentName);
        return environmentValue;
    }
}