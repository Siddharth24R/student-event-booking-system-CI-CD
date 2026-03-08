package com.ntu.eventbooking.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import org.json.JSONObject;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.ext.Provider;
import java.io.IOException;
import java.lang.reflect.Method;
import java.security.Principal;

/**
 * JAX-RS name-binding request filter that enforces JWT authentication on
 * endpoints annotated with @Secured.
 *
 * Flow:
 *  1. Determine required role from @Secured annotation (via ResourceInfo injection)
 *  2. Extract Bearer token from Authorization header (or fall back to HttpSession)
 *  3. Validate token via JwtUtil
 *  4. Check caller's role against the required role
 *  5. On success: inject caller properties and SecurityContext for downstream use
 *  6. On failure: abort with 401 or 403
 *
 * Name-binding: Only runs on endpoints that carry the @Secured annotation.
 * This avoids intercepting public endpoints like GET /api/events.
 *
 * NOTE: getResourceMethod() / getResourceClass() are NOT on ContainerRequestContext
 * in JAX-RS 2.0 (Jersey 2.x). Use @Context ResourceInfo instead.
 */
@Secured
@Provider
public class AuthFilter implements ContainerRequestFilter {

    /** Injected by Jersey — provides the matched resource class and method. */
    @Context
    private ResourceInfo resourceInfo;

    @Context
    private HttpServletRequest httpRequest;

    @Override
    public void filter(ContainerRequestContext requestContext) throws IOException {
        // 1. Get required role from @Secured — check method first, then class
        String requiredRole = "STUDENT";
        Method method = resourceInfo.getResourceMethod();
        if (method != null && method.isAnnotationPresent(Secured.class)) {
            requiredRole = method.getAnnotation(Secured.class).value();
        } else if (resourceInfo.getResourceClass() != null
                && resourceInfo.getResourceClass().isAnnotationPresent(Secured.class)) {
            requiredRole = resourceInfo.getResourceClass().getAnnotation(Secured.class).value();
        }

        // 2. Extract token: Authorization header first, then HttpSession fallback
        String token = extractToken(requestContext);
        if (token == null && httpRequest != null) {
            javax.servlet.http.HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                Object sessionToken = session.getAttribute("jwt_token");
                if (sessionToken instanceof String) {
                    token = (String) sessionToken;
                }
            }
        }

        if (token == null) {
            abort(requestContext, 401, "No authentication token provided. Please log in.");
            return;
        }

        // 3. Validate token
        Claims claims;
        try {
            claims = JwtUtil.validateToken(token);
        } catch (JwtException e) {
            abort(requestContext, 401, "Invalid or expired token. Please log in again.");
            return;
        }

        String callerRole      = (String) claims.get("role");
        String callerEmail     = claims.getSubject();
        String callerStudentId = (String) claims.get("studentId");
        String callerName      = (String) claims.get("name");

        // 4. Role check
        boolean allowed;
        switch (requiredRole) {
            case "STUDENT":
                // Any logged-in user (student or admin) may access
                allowed = "student".equals(callerRole) || "admin".equals(callerRole);
                break;
            case "ADMIN":
                allowed = "admin".equals(callerRole);
                break;
            case "OWN_OR_ADMIN":
                // Ownership check happens inside the endpoint using callerStudentId
                allowed = "student".equals(callerRole) || "admin".equals(callerRole);
                break;
            default:
                allowed = false;
        }

        if (!allowed) {
            abort(requestContext, 403,
                    "Access denied. Required role: " + requiredRole + ", your role: " + callerRole);
            return;
        }

        // 5. Inject caller info into request properties for downstream endpoint use
        requestContext.setProperty("callerEmail",     callerEmail);
        requestContext.setProperty("callerStudentId", callerStudentId);
        requestContext.setProperty("callerRole",      callerRole);
        requestContext.setProperty("callerName",      callerName);

        // Inject SecurityContext so getPrincipal().getName() returns the email
        final String finalEmail = callerEmail;
        final String finalRole  = callerRole;
        requestContext.setSecurityContext(new SecurityContext() {
            @Override public Principal getUserPrincipal()         { return () -> finalEmail; }
            @Override public boolean   isUserInRole(String role)  { return finalRole.equals(role); }
            @Override public boolean   isSecure()                 { return false; }
            @Override public String    getAuthenticationScheme()  { return "Bearer"; }
        });
    }

    /** Extracts the raw token from the Authorization header ("Bearer <token>"). */
    private String extractToken(ContainerRequestContext ctx) {
        String authHeader = ctx.getHeaderString("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String t = authHeader.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        return null;
    }

    /** Aborts the request with a JSON error response. */
    private void abort(ContainerRequestContext ctx, int status, String message) {
        String body = new JSONObject().put("error", message).toString();
        ctx.abortWith(Response.status(status)
                .type("application/json")
                .entity(body)
                .build());
    }
}
