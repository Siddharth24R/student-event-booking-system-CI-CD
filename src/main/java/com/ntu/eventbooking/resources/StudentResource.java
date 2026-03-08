package com.ntu.eventbooking.resources;

import com.ntu.eventbooking.models.Event;
import com.ntu.eventbooking.models.Student;
import com.ntu.eventbooking.services.FirebaseService;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST resource for student registration.
 *
 * Endpoint: POST /api/students/subscribe
 *
 * Students must subscribe before they can register for or rate events.
 * Email is used as the unique key — duplicate registrations are rejected with 409 Conflict.
 */
@Path("students")
public class StudentResource {

    private final FirebaseService firebase = FirebaseService.getInstance();

    /**
     * Register a new student.
     *
     * Request body (JSON):
     * {
     *   "studentId": "N0123456",
     *   "name": "Sidd",
     *   "email": "sidd@ntu.ac.uk"
     * }
     *
     * Responses:
     *   201 Created  — student saved successfully
     *   400 Bad Request — missing or blank required fields
     *   409 Conflict — email already registered
     *   500 Internal Server Error — database write failed
     */
    @POST
    @Path("subscribe")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response subscribe(Student student) {
        // Validate required fields are present and not blank
        if (student == null
                || ResourceUtil.isBlank(student.getName())
                || ResourceUtil.isBlank(student.getEmail())
                || ResourceUtil.isBlank(student.getStudentId())) {
            return Response.status(400)
                    .entity(ResourceUtil.error("name, email, and studentId are all required"))
                    .build();
        }

        // Normalise email to lowercase to avoid case-sensitivity duplicates
        student.setEmail(student.getEmail().toLowerCase().trim());

        // Reject if this email is already in use
        if (firebase.studentExists(student.getEmail())) {
            return Response.status(409)
                    .entity(ResourceUtil.error("Email already registered: " + student.getEmail()))
                    .build();
        }

        // Persist the student record
        boolean saved = firebase.saveStudent(student);
        if (!saved) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Failed to save student — please try again"))
                    .build();
        }

        // Build a success response
        JSONObject result = new JSONObject();
        result.put("status", "registered");
        result.put("studentId", student.getStudentId());
        result.put("name", student.getName());
        result.put("email", student.getEmail());

        return Response.status(201).entity(result.toString()).build();
    }

    /**
     * GET /api/students/{studentId}/registrations
     *
     * Returns all events the given student has registered for.
     * Returns the full event objects so the frontend can render them directly.
     *
     * Responses:
     *   200 OK   — JSON array of Event objects (may be empty)
     *   404      — studentId path param missing (caught by JAX-RS)
     */
    @GET
    @Path("{studentId}/registrations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMyRegistrations(@PathParam("studentId") String studentId) {
        List<String> eventIds      = firebase.getRegisteredEventIds(studentId);
        Map<String, Integer> myRatings = firebase.getAllStudentRatings(studentId);
        Set<String> cancelled      = firebase.getCancelledEventIds(studentId);

        // Also include cancelled events (so they appear in Past as "Cancelled")
        // Merge cancelled event IDs that are not already in registrations
        Set<String> allEventIds = new java.util.LinkedHashSet<>(eventIds);
        allEventIds.addAll(cancelled);

        JSONArray arr = new JSONArray();
        for (String eventId : allEventIds) {
            Event ev = firebase.getEventById(eventId);
            if (ev != null) {
                JSONObject obj = new JSONObject();
                obj.put("eventId",         ev.getEventId());
                obj.put("title",           ev.getTitle() != null ? ev.getTitle() : "");
                obj.put("type",            ev.getType()  != null ? ev.getType()  : "other");
                obj.put("venue",           ev.getVenue() != null ? ev.getVenue() : "");
                obj.put("date",            ev.getDate()  != null ? ev.getDate()  : "");
                obj.put("time",            ev.getTime()  != null ? ev.getTime()  : "");
                obj.put("cost",            ev.getCost());
                obj.put("maxParticipants", ev.getMaxParticipants());
                obj.put("currentAttendees",ev.getCurrentAttendees());
                obj.put("spotsRemaining",  Math.max(0, ev.getMaxParticipants() - ev.getCurrentAttendees()));
                obj.put("averageRating",   ev.getAverageRating());
                obj.put("ratingCount",     ev.getRatingCount());
                obj.put("source",          ev.getSource() != null ? ev.getSource() : "local");
                obj.put("publisherID",     ev.getPublisherID() != null ? ev.getPublisherID() : "");
                // Personal rating fields
                int userRating = myRatings.getOrDefault(eventId, 0);
                obj.put("userRating",      userRating);
                obj.put("hasRated",        userRating > 0);
                // Cancellation flag
                boolean isCancelled = cancelled.contains(eventId);
                obj.put("isCancelled",     isCancelled);
                arr.put(obj);
            }
        }
        return Response.ok(arr.toString()).build();
    }

}
