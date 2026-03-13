package com.ntu.eventbooking.resources;

import com.ntu.eventbooking.auth.JwtUtil;
import com.ntu.eventbooking.models.Student;
import com.ntu.eventbooking.services.FirebaseService;
import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.*;
import javax.ws.rs.core.*;

/**
 * Authentication resource — login, logout, and registration.
 *
 * Endpoints:
 *   POST /api/auth/login    — student or admin login → returns JWT
 *   POST /api/auth/logout   — invalidates the HTTP session
 *   POST /api/auth/register — create a new student account
 */
@Path("auth")
public class AuthResource {

    private final FirebaseService firebase = FirebaseService.getInstance();

    @Context
    private HttpServletRequest httpRequest;

    // =========================================================================
    // POST /api/auth/login
    // =========================================================================

    /**
     * Authenticate a student or admin and return a JWT.
     *
     * Student body:  { "email": "x@ntu.ac.uk", "password": "pass123" }
     * Admin body:    { "email": "admin@ntu.ac.uk", "password": "pass123", "adminCode": "NTU-ADMIN-2026" }
     *
     * Returns: { "token": "...", "role": "student"|"admin", "name": "...", "studentId": "..." }
     */
    @POST
    @Path("login")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response login(String bodyStr) {
        JSONObject body;
        try {
            body = new JSONObject(bodyStr != null ? bodyStr : "{}");
        } catch (Exception e) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON")).build();
        }

        String email    = body.optString("email", "").toLowerCase().trim();
        String password = body.optString("password", "");
        String adminCode = body.optString("adminCode", "");

        if (ResourceUtil.isBlank(email) || ResourceUtil.isBlank(password)) {
            return Response.status(400).entity(ResourceUtil.error("email and password are required")).build();
        }

        // Look up by email in Firestore
        Student student = firebase.getStudentByEmail(email);
        if (student == null) {
            return Response.status(401).entity(ResourceUtil.error("Invalid credentials")).build();
        }

        // Verify password (BCrypt)
        if (student.getPassword() == null || !BCrypt.checkpw(password, student.getPassword())) {
            return Response.status(401).entity(ResourceUtil.error("Invalid credentials")).build();
        }

        // Reject non-admin accounts trying to use the admin login form (adminCode was supplied)
        if (!ResourceUtil.isBlank(adminCode) && !"admin".equals(student.getRole())) {
            return Response.status(403)
                    .entity(ResourceUtil.error("Admin login is for admin accounts only"))
                    .build();
        }

        // Admin: also verify adminCode
        if ("admin".equals(student.getRole())) {
            if (ResourceUtil.isBlank(adminCode) || !adminCode.equals(student.getAdminCode())) {
                return Response.status(401).entity(ResourceUtil.error("Invalid admin access code")).build();
            }
        }

        // Generate JWT
        String token = JwtUtil.generateToken(
                student.getEmail(),
                student.getStudentId(),
                student.getName(),
                student.getRole() != null ? student.getRole() : "student"
        );

        // Store in HTTP session as fallback for same-origin requests
        HttpSession session = httpRequest.getSession(true);
        session.setAttribute("jwt_token", token);
        session.setAttribute("student_email", student.getEmail());

        JSONObject result = new JSONObject();
        result.put("token",     token);
        result.put("role",      student.getRole() != null ? student.getRole() : "student");
        result.put("name",      student.getName());
        result.put("studentId", student.getStudentId());
        result.put("email",     student.getEmail());

        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // POST /api/auth/logout
    // =========================================================================

    /**
     * Invalidates the current HTTP session.
     * The client should also discard its in-memory token.
     */
    @POST
    @Path("logout")
    @Produces(MediaType.APPLICATION_JSON)
    public Response logout() {
        HttpSession session = httpRequest.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        return Response.ok(new JSONObject().put("message", "Logged out successfully").toString()).build();
    }

    // =========================================================================
    // POST /api/auth/register
    // =========================================================================

    /**
     * Creates a new student account.
     *
     * Body: { "studentId": "N0123456", "name": "Thaha", "email": "x@ntu.ac.uk", "password": "pass123" }
     *
     * Returns 201 with student info (no password field).
     */
    @POST
    @Path("register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(String bodyStr) {
        JSONObject body;
        try {
            body = new JSONObject(bodyStr != null ? bodyStr : "{}");
        } catch (Exception e) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON")).build();
        }

        String studentId = body.optString("studentId", "").trim();
        String name      = body.optString("name", "").trim();
        String email     = body.optString("email", "").toLowerCase().trim();
        String password  = body.optString("password", "");

        if (ResourceUtil.isBlank(studentId) || ResourceUtil.isBlank(name) || ResourceUtil.isBlank(email) || ResourceUtil.isBlank(password)) {
            return Response.status(400)
                    .entity(ResourceUtil.error("studentId, name, email, and password are all required"))
                    .build();
        }

        if (password.length() < 6) {
            return Response.status(400)
                    .entity(ResourceUtil.error("Password must be at least 6 characters"))
                    .build();
        }

        // Check duplicate email
        if (firebase.studentExists(email)) {
            return Response.status(409)
                    .entity(ResourceUtil.error("Email already registered: " + email))
                    .build();
        }

        // Register in Firebase Authentication (so user appears in Auth console)
        firebase.createAuthUser(email, password, name);

        // Build student and hash password
        Student student = new Student(studentId, name, email);
        student.setPassword(BCrypt.hashpw(password, BCrypt.gensalt(12)));
        student.setRole("student");

        boolean saved = firebase.saveStudentWithAuth(student);
        if (!saved) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Failed to create account — please try again"))
                    .build();
        }

        // Return without password
        JSONObject result = new JSONObject();
        result.put("status",    "registered");
        result.put("studentId", student.getStudentId());
        result.put("name",      student.getName());
        result.put("email",     student.getEmail());
        result.put("role",      student.getRole());

        return Response.status(201).entity(result.toString()).build();
    }

}
