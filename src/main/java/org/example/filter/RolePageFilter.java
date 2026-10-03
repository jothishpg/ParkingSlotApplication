package org.example.filter;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import org.example.auth.JwtUtil;

import java.io.IOException;
import java.security.Principal;
import java.util.Map;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class RolePageFilter implements ContainerRequestFilter {

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
            }
        }

        context.setSecurityContext(new TokenSecurityContext(userId, role, request));
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