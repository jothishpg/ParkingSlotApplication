package org.example.filter;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.example.auth.JwtUtil;
import org.example.config.DatabaseConnection;

import java.io.IOException;
import java.security.Principal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RolePageFilter implements ContainerRequestFilter {

    private static final Logger LOGGER = Logger.getLogger(RolePageFilter.class.getName());
    private static final String AUTH_COOKIE = "AUTH_TOKEN";

    @Context
    private HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        Long userId = null;
        String role = null;

        Cookie cookie = context.getCookies().get(AUTH_COOKIE);
        if (cookie != null) {
            Map<String, Object> claims = JwtUtil.verify(cookie.getValue());
            if (claims != null) {
                Object userIdClaim = claims.get("user_id");
                Object roleClaim = claims.get("role");

                if (userIdClaim instanceof Number number) {
                    userId = number.longValue();
                }
                if (roleClaim != null) {
                    role = String.valueOf(roleClaim);
                }

                if (userId != null && userId > 0 && role != null && !role.isBlank()) {
                    try (Connection connection = DatabaseConnection.getConnection();
                         PreparedStatement statement = connection.prepareStatement("""
                                 SELECT r.role_name
                                 FROM users u
                                 JOIN roles r ON r.role_id = u.role_id
                                 WHERE u.user_id = ?
                                 """)) {
                        statement.setLong(1, userId);
                        try (ResultSet result = statement.executeQuery()) {
                            if (!result.next()
                                    || !role.equalsIgnoreCase(result.getString("role_name"))) {
                                context.abortWith(Response.status(Response.Status.UNAUTHORIZED)
                                        .type(MediaType.TEXT_PLAIN)
                                        .entity("Your account role has changed. Please sign in again.")
                                        .cookie(clearAuthCookie())
                                        .build());
                                return;
                            }
                        }
                    } catch (SQLException exception) {
                        LOGGER.log(Level.SEVERE, "Unable to verify the authenticated user's role.",
                                exception);
                        context.abortWith(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                                .type(MediaType.TEXT_PLAIN)
                                .entity("Unable to verify your account. Please try again.")
                                .build());
                        return;
                    }
                }
            }
        }

        context.setSecurityContext(new TokenSecurityContext(userId, role, request));
    }

    private NewCookie clearAuthCookie() {
        return new NewCookie.Builder(AUTH_COOKIE)
                .value("")
                .path("/")
                .httpOnly(true)
                .secure(true)
                .sameSite(NewCookie.SameSite.LAX)
                .maxAge(0)
                .build();
    }

    private record TokenSecurityContext(
            Long userId,
            String role,
            HttpServletRequest request) implements SecurityContext {

        @Override
        public Principal getUserPrincipal() {
            return userId == null
                    ? null
                    : () -> String.valueOf(userId);
        }

        @Override
        public boolean isUserInRole(String requiredRole) {
            if (role == null) {
                return false;
            }
            return role.equalsIgnoreCase(requiredRole);
        }

        @Override
        public boolean isSecure() {
            return request.isSecure();
        }

        @Override
        public String getAuthenticationScheme() {
            return userId == null ? null : "BEARER";
        }
    }
}