package com.ntu.eventbooking.resources;

import com.ntu.eventbooking.auth.Secured;
import com.ntu.eventbooking.models.Event;
import com.ntu.eventbooking.models.Student;
import com.ntu.eventbooking.scheduler.EventSyncScheduler;
import com.ntu.eventbooking.services.FirebaseService;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;

/**
 * Admin-only REST resource.
 *
 * All endpoints require a valid JWT with role "admin".
 *
 * Endpoints:
 *   GET  /api/admin/students           — list all registered students
 *   GET  /api/admin/analytics          — aggregated system analytics
 *   POST /api/admin/sync               — manually trigger external event sync
 */
@Path("admin")
@Secured("ADMIN")
public class AdminResource {

    private final FirebaseService firebase = FirebaseService.getInstance();

    // =========================================================================
    // GET /api/admin/students — List all registered students
    // =========================================================================

    /**
     * Returns every student account in the system (password field excluded).
     * Used by the Admin Panel student management table.
     */
    @GET
    @Path("students")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllStudents() {
        List<Student> students = firebase.getAllStudents();

        JSONArray arr = new JSONArray();
        for (Student s : students) {
            JSONObject obj = new JSONObject();
            obj.put("studentId", s.getStudentId());
            obj.put("name",      s.getName());
            obj.put("email",     s.getEmail());
            obj.put("role",      s.getRole() != null ? s.getRole() : "student");
            arr.put(obj);
        }

        return Response.ok(arr.toString()).build();
    }

    // =========================================================================
    // DELETE /api/admin/students/{studentId} — Delete individual student
    // =========================================================================

    @DELETE
    @Path("students/{studentId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteStudent(@PathParam("studentId") String studentId) {
        Student s = firebase.getStudentById(studentId);
        if (s == null) {
            return Response.status(404).entity(ResourceUtil.error("Student not found")).build();
        }
        if ("admin".equals(s.getRole())) {
            return Response.status(403).entity(ResourceUtil.error("Cannot delete admin accounts")).build();
        }
        firebase.deleteStudent(studentId);
        return Response.ok(new JSONObject().put("deleted", studentId).toString()).build();
    }

    // =========================================================================
    // DELETE /api/admin/students/bulk — Delete multiple students
    // =========================================================================

    @DELETE
    @Path("students/bulk")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteStudentsBulk(String bodyStr) {
        JSONObject body;
        try {
            body = new JSONObject(bodyStr != null ? bodyStr : "{}");
        } catch (Exception e) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON")).build();
        }
        JSONArray ids = body.optJSONArray("studentIds");
        if (ids == null || ids.length() == 0) {
            return Response.status(400).entity(ResourceUtil.error("studentIds array required")).build();
        }
        int deleted = 0;
        for (int i = 0; i < ids.length(); i++) {
            String id = ids.getString(i);
            Student s = firebase.getStudentById(id);
            if (s != null && !"admin".equals(s.getRole())) {
                firebase.deleteStudent(id);
                deleted++;
            }
        }
        return Response.ok(new JSONObject().put("deleted", deleted).toString()).build();
    }

    // =========================================================================
    // GET /api/admin/analytics — Aggregated analytics
    // =========================================================================

    /**
     * Returns high-level analytics about the system:
     *   - totalEvents, totalStudents
     *   - totalRegistrations (sum of currentAttendees across all events)
     *   - totalRevenue (sum of cost × currentAttendees for paid events)
     *   - averageRating across all rated events
     *   - eventsByType breakdown map
     *   - topEvents (top 5 by currentAttendees)
     *
     * Designed for the Admin Dashboard analytics panel.
     */
    @GET
    @Path("analytics")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAnalytics() {
        List<Event>   events   = firebase.getAllEvents();
        List<Student> students = firebase.getAllStudents();

        int    totalEvents        = events.size();
        int    totalStudents      = students.size();
        int    totalRegistrations = 0;
        double totalRevenue       = 0.0;
        double ratingSum          = 0.0;
        int    ratedCount         = 0;

        JSONObject eventsByType = new JSONObject();

        for (Event e : events) {
            totalRegistrations += e.getCurrentAttendees();
            totalRevenue       += e.getCost() * e.getCurrentAttendees();

            if (e.getRatingCount() > 0) {
                ratingSum  += e.getAverageRating();
                ratedCount += 1;
            }

            String type = e.getType() != null ? e.getType() : "other";
            eventsByType.put(type, eventsByType.optInt(type, 0) + 1);
        }

        double overallAvgRating = ratedCount > 0
                ? Math.round((ratingSum / ratedCount) * 100.0) / 100.0
                : 0.0;

        // Top 5 events by current attendees
        JSONArray topEvents = new JSONArray();
        events.stream()
                .sorted((a, b) -> b.getCurrentAttendees() - a.getCurrentAttendees())
                .limit(5)
                .forEach(e -> {
                    JSONObject obj = new JSONObject();
                    obj.put("eventId",          e.getEventId());
                    obj.put("title",            e.getTitle());
                    obj.put("currentAttendees", e.getCurrentAttendees());
                    obj.put("maxParticipants",  e.getMaxParticipants());
                    obj.put("type",             e.getType());
                    topEvents.put(obj);
                });

        JSONObject result = new JSONObject();
        result.put("totalEvents",        totalEvents);
        result.put("totalStudents",      totalStudents);
        result.put("totalRegistrations", totalRegistrations);
        result.put("totalRevenue",       Math.round(totalRevenue * 100.0) / 100.0);
        result.put("overallAvgRating",   overallAvgRating);
        result.put("eventsByType",       eventsByType);
        result.put("topEvents",          topEvents);
        result.put("firebaseAvailable",  firebase.isFirebaseAvailable());

        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // POST /api/admin/sync — Manually trigger external event sync
    // =========================================================================

    /**
     * Forces an immediate synchronisation of external events (Skiddle / Ticketmaster).
     * Normally this runs on a scheduled timer; this endpoint lets an admin trigger
     * it on demand from the Admin Panel.
     *
     * Returns a JSON summary of how many events were synced.
     */
    @POST
    @Path("sync")
    @Produces(MediaType.APPLICATION_JSON)
    public Response forceSync() {
        try {
            int count = EventSyncScheduler.syncNow();
            JSONObject result = new JSONObject();
            result.put("status", "synced");
            result.put("eventsImported", count);
            result.put("message", "External events synced successfully");
            return Response.ok(result.toString()).build();
        } catch (Exception e) {
            return Response.status(500)
                    .entity(new JSONObject()
                            .put("error", "Sync failed: " + e.getMessage())
                            .toString())
                    .build();
        }
    }
}
