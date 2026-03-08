package com.ntu.eventbooking.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.util.Date;

/**
 * Utility class for generating and validating JWT tokens.
 *
 * Secret key is read from the JWT_SECRET environment variable, defaulting to a
 * development key if not set. In production the environment variable must be set.
 *
 * Token payload (claims):
 *   sub       — student's email (unique identifier)
 *   studentId — NTU student ID or admin username
 *   name      — display name
 *   role      — "student" or "admin"
 *   iat / exp — issued-at and expiry (24 hours)
 */
public class JwtUtil {

    private static final long EXPIRY_MS = 24 * 60 * 60 * 1000L; // 24 hours

    /** Derives a signing key from the secret string (padded to 256 bits). */
    private static Key getKey() {
        String secret = System.getenv("JWT_SECRET");
        if (secret == null || secret.isBlank()) {
            secret = "NTU-ISYS40061-SECRET-KEY-PADDING-32B";
        }
        // Pad or truncate to exactly 32 bytes for HMAC-SHA256
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        byte[] padded = new byte[32];
        System.arraycopy(keyBytes, 0, padded, 0, Math.min(keyBytes.length, 32));
        return Keys.hmacShaKeyFor(padded);
    }

    /**
     * Generates a signed JWT for the given user.
     *
     * @param email     user's email (subject claim)
     * @param studentId NTU student ID or admin username
     * @param name      display name
     * @param role      "student" or "admin"
     * @return signed JWT string
     */
    public static String generateToken(String email, String studentId, String name, String role) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);

        return Jwts.builder()
                .setSubject(email)
                .claim("studentId", studentId)
                .claim("name", name)
                .claim("role", role)
                .setIssuedAt(now)
                .setExpiration(expiry)
                .signWith(getKey(), SignatureAlgorithm.HS256)
                .compact();
    }

    /**
     * Validates a JWT and returns its claims.
     *
     * @param token the JWT string (without "Bearer " prefix)
     * @return parsed Claims if valid
     * @throws JwtException if the token is invalid, expired, or tampered
     */
    public static Claims validateToken(String token) throws JwtException {
        return Jwts.parserBuilder()
                .setSigningKey(getKey())
                .build()
                .parseClaimsJws(token)
                .getBody();
    }

    /** Extracts the email (subject) from a token without full validation. */
    public static String getEmail(String token) {
        return validateToken(token).getSubject();
    }

    /** Extracts the role claim from a validated token. */
    public static String getRole(String token) {
        return (String) validateToken(token).get("role");
    }

    /** Extracts the studentId claim from a validated token. */
    public static String getStudentId(String token) {
        return (String) validateToken(token).get("studentId");
    }
}
