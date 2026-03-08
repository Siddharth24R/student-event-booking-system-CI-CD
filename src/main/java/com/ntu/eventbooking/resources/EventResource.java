package com.ntu.eventbooking.resources;

import com.ntu.eventbooking.auth.Secured;
import com.ntu.eventbooking.cache.EventCache;
import com.ntu.eventbooking.models.Event;
import com.ntu.eventbooking.models.Rating;
import com.ntu.eventbooking.services.FirebaseService;
import com.ntu.eventbooking.services.WeatherService;
import org.json.JSONArray;
import org.json.JSONObject;

import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST resource for all event-related operations.
 *
 * Public endpoints (no auth required):
 *   GET    /api/events                      — list all events (with optional filters)
 *   POST   /api/events/{eventId}/register   — register a student for an event
 *   POST   /api/events/{eventId}/rate       — submit a post-event rating (1–5 stars)
 *
 * Protected endpoints:
 *   POST   /api/events                      — create a new event          @Secured("STUDENT")
 *   POST   /api/events/{id}/edit            — update an existing event     @Secured("OWN_OR_ADMIN")
 *   DELETE /api/events/{id}                 — delete an event              @Secured("OWN_OR_ADMIN")
 *   DELETE /api/events/{id}/registrations/{studentId} — remove a booking  @Secured("ADMIN")
 */
@Path("events")
public class EventResource {

    private final FirebaseService firebase = FirebaseService.getInstance();

    @Context
    private ContainerRequestContext requestContext;

    // =========================================================================
    // POST /api/events — Create a new event  [STUDENT or ADMIN]
    // =========================================================================

    /**
     * Creates a new student event and persists it to Firestore.
     *
     * Request body (JSON):
     * {
     *   "publisherID": "N0123456",
     *   "title": "Gaming Night",
     *   "type": "gaming",
     *   "date": "2026-04-15",
     *   "venue": "NTU Clifton Campus",
     *   "cost": 0,
     *   "maxParticipants": 20,
     *   "latitude": 52.9160,    (optional — defaults to Nottingham centre)
     *   "longitude": -1.1770    (optional)
     * }
     */
    @POST
    @Secured("STUDENT")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response createEvent(Event event) {
        // Validate required fields
        if (event == null
                || ResourceUtil.isBlank(event.getTitle())
                || ResourceUtil.isBlank(event.getType())
                || ResourceUtil.isBlank(event.getDate())
                || ResourceUtil.isBlank(event.getVenue())
                || ResourceUtil.isBlank(event.getPublisherID())) {
            return Response.status(400)
                    .entity(ResourceUtil.error("publisherID, title, type, date, and venue are required"))
                    .build();
        }
        if (event.getMaxParticipants() <= 0) {
            return Response.status(400)
                    .entity(ResourceUtil.error("maxParticipants must be greater than 0"))
                    .build();
        }

        // Default to Nottingham city centre if no coordinates provided
        if (event.getLatitude() == 0.0 && event.getLongitude() == 0.0) {
            event.setLatitude(52.9548);
            event.setLongitude(-1.1581);
        }

        event.setCurrentAttendees(0);
        event.setAverageRating(0.0);
        event.setRatingCount(0);
        event.setSource("local");

        String generatedId = firebase.saveEvent(event);
        if (generatedId == null) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Failed to save event — please try again"))
                    .build();
        }

        event.setEventId(generatedId);

        // Invalidate the GET /events cache so the new event appears immediately
        EventCache.invalidate("all_events");

        return Response.status(201).entity(eventToJson(event).toString()).build();
    }

    // =========================================================================
    // GET /api/events — List all events (with optional filters)  [PUBLIC]
    // =========================================================================

    /**
     * Returns all events, merging local Firestore events with live Skiddle public events.
     * Weather is attached to each event from OpenWeatherMap.
     *
     * Optional query parameters (all case-insensitive):
     *   type   — filter by event type (e.g. "sport", "gaming")
     *   venue  — filter by venue name (partial match)
     *   date   — filter by exact date in YYYY-MM-DD format
     *
     * Design decision: Only the unfiltered result is cached. Filtered results are not
     * cached because the combination of filter values is too large to key efficiently,
     * and most filter queries are exploratory one-offs.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAllEvents(
            @QueryParam("type") String type,
            @QueryParam("venue") String venue,
            @QueryParam("date") String date) {

        boolean isFiltered = !ResourceUtil.isBlank(type) || !ResourceUtil.isBlank(venue) || !ResourceUtil.isBlank(date);
        String cacheKey = "all_events";

        // Check in-memory cache first (only for unfiltered requests)
        if (!isFiltered) {
            String cached = EventCache.get(cacheKey);
            if (cached != null) {
                // Cache hit — return immediately without touching the database
                return Response.ok(cached).build();
            }
        }

        // Load all events from Firestore — includes local student events plus any
        // external events (Skiddle, Ticketmaster) pre-synced by EventSyncScheduler
        List<Event> events = firebase.getAllEvents();

        // Attach live weather to each event from OpenWeatherMap
        for (Event e : events) {
            String weather = WeatherService.getWeather(e.getLatitude(), e.getLongitude());
            e.setWeather(weather);
        }

        // Apply optional filters
        List<Event> filtered = events.stream()
                .filter(e -> ResourceUtil.isBlank(type) || type.equalsIgnoreCase(e.getType()))
                .filter(e -> ResourceUtil.isBlank(venue) || e.getVenue().toLowerCase().contains(venue.toLowerCase()))
                .filter(e -> ResourceUtil.isBlank(date) || date.equals(e.getDate()))
                .collect(Collectors.toList());

        // Serialise to JSON array
        JSONArray arr = new JSONArray();
        for (Event e : filtered) {
            arr.put(eventToJson(e));
        }
        String responseJson = arr.toString();

        // Cache the result (only when no filters — cached result is the full unfiltered list)
        if (!isFiltered) {
            EventCache.put(cacheKey, responseJson);
        }

        return Response.ok(responseJson).build();
    }

    // =========================================================================
    // POST /api/events/{eventId}/register — Register a student for an event  [PUBLIC]
    // =========================================================================

    /**
     * Registers a student for an event, subject to capacity limits.
     *
     * Request body (JSON):
     * { "studentId": "N0123456" }
     *
     * Responses:
     *   200 OK   — registration successful, returns updated event
     *   400      — missing studentId
     *   404      — event not found
     *   409      — event is full (currentAttendees >= maxParticipants)
     *   500      — database update failed
     */
    @POST
    @Path("{eventId}/register")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response register(@PathParam("eventId") String eventId, String bodyStr) {
        // Parse the raw JSON string manually using org.json (Jersey passes the body as String)
        String studentId = null;
        try {
            JSONObject body = new JSONObject(bodyStr != null ? bodyStr : "{}");
            studentId = body.optString("studentId", null);
        } catch (Exception ex) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON body")).build();
        }
        if (ResourceUtil.isBlank(studentId)) {
            return Response.status(400)
                    .entity(ResourceUtil.error("studentId is required"))
                    .build();
        }
        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        // Enforce capacity limit
        if (event.getCurrentAttendees() >= event.getMaxParticipants()) {
            return Response.status(409)
                    .entity(ResourceUtil.error("Event is full — no places remaining"))
                    .build();
        }

        // Block re-registration after self-cancellation
        if (firebase.hasCancelled(eventId, studentId)) {
            return Response.status(409)
                    .entity(ResourceUtil.error("You have cancelled your registration for this event and cannot re-register"))
                    .build();
        }

        // Check if student already registered
        if (firebase.isStudentRegistered(eventId, studentId)) {
            return Response.status(409)
                    .entity(ResourceUtil.error("Already registered for this event"))
                    .build();
        }

        // Increment attendee count, record registration, and persist
        event.setCurrentAttendees(event.getCurrentAttendees() + 1);
        boolean updated = firebase.updateEvent(event);
        if (!updated) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Registration failed — please try again"))
                    .build();
        }

        // Record the registration (student → event mapping)
        firebase.addRegistration(eventId, studentId);

        // Invalidate cache so the updated attendee count is reflected in GET /events
        EventCache.invalidate("all_events");

        JSONObject result = new JSONObject();
        result.put("status", "registered");
        result.put("eventId", eventId);
        result.put("studentId", studentId);
        result.put("currentAttendees", event.getCurrentAttendees());
        result.put("maxParticipants", event.getMaxParticipants());
        result.put("spotsRemaining", event.getMaxParticipants() - event.getCurrentAttendees());

        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // POST /api/events/{eventId}/rate — Submit a rating for an event  [PUBLIC]
    // =========================================================================

    /**
     * Submits a post-event rating (1–5 stars) and updates the running average.
     *
     * Request body (JSON):
     * { "studentId": "N0123456", "stars": 4 }
     *
     * The new average is calculated as an incremental running average to avoid
     * needing to store individual ratings: newAvg = (oldAvg * oldCount + stars) / newCount
     */
    @POST
    @Path("{eventId}/rate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response rate(@PathParam("eventId") String eventId, Rating rating) {
        if (rating == null || ResourceUtil.isBlank(rating.getStudentId())) {
            return Response.status(400)
                    .entity(ResourceUtil.error("studentId is required"))
                    .build();
        }
        if (rating.getStars() < 1 || rating.getStars() > 5) {
            return Response.status(400)
                    .entity(ResourceUtil.error("stars must be between 1 and 5"))
                    .build();
        }

        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        // Enforce one rating per student per event
        if (firebase.hasStudentRated(eventId, rating.getStudentId())) {
            return Response.status(409)
                    .entity(ResourceUtil.error("You have already rated this event"))
                    .build();
        }

        // Recalculate average using exact ratingTotal sum (avoids floating-point drift)
        double newTotal = event.getRatingTotal() + rating.getStars();
        int newCount = event.getRatingCount() + 1;
        double newAvg = Math.round((newTotal / newCount) * 100.0) / 100.0;

        boolean updated = firebase.addRating(eventId, newAvg, newCount, newTotal);
        if (!updated) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Rating submission failed — please try again"))
                    .build();
        }

        // Persist the student's individual rating
        firebase.saveStudentRating(eventId, rating.getStudentId(), rating.getStars());

        JSONObject result = new JSONObject();
        result.put("status", "rated");
        result.put("eventId", eventId);
        result.put("yourRating", rating.getStars());
        result.put("averageRating", newAvg);
        result.put("totalRatings", newCount);

        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // POST /api/events/{eventId}/edit — Edit an event  [OWN_OR_ADMIN]
    // =========================================================================

    /**
     * Updates an existing event. Only the event's publisher or an admin may edit.
     *
     * Request body: same fields as POST /api/events (partial update — only provided
     * fields are applied; eventId, currentAttendees, and source are preserved).
     *
     * Ownership check: If the caller's role is "student", their studentId must match
     * the event's publisherID. Admins may edit any event.
     */
    @POST
    @Path("{eventId}/edit")
    @Secured("OWN_OR_ADMIN")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response editEvent(@PathParam("eventId") String eventId, String bodyStr) {
        String callerStudentId = (String) requestContext.getProperty("callerStudentId");
        String callerRole      = (String) requestContext.getProperty("callerRole");

        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        // Ownership check for students
        if ("student".equals(callerRole) && !callerStudentId.equals(event.getPublisherID())) {
            return Response.status(403)
                    .entity(ResourceUtil.error("You can only edit your own events"))
                    .build();
        }

        // Parse updates
        JSONObject body;
        try {
            body = new JSONObject(bodyStr != null ? bodyStr : "{}");
        } catch (Exception e) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON")).build();
        }

        // Apply only provided fields (partial update)
        if (body.has("title"))           event.setTitle(body.optString("title"));
        if (body.has("type"))            event.setType(body.optString("type"));
        if (body.has("date"))            event.setDate(body.optString("date"));
        if (body.has("time"))            event.setTime(body.optString("time"));
        if (body.has("venue"))           event.setVenue(body.optString("venue"));
        if (body.has("cost"))            event.setCost(body.optDouble("cost", event.getCost()));
        if (body.has("maxParticipants")) event.setMaxParticipants(body.optInt("maxParticipants", event.getMaxParticipants()));
        if (body.has("latitude"))        event.setLatitude(body.optDouble("latitude", event.getLatitude()));
        if (body.has("longitude"))       event.setLongitude(body.optDouble("longitude", event.getLongitude()));

        // Validate after applying updates
        if (ResourceUtil.isBlank(event.getTitle()) || ResourceUtil.isBlank(event.getType())
                || ResourceUtil.isBlank(event.getDate()) || ResourceUtil.isBlank(event.getVenue())) {
            return Response.status(400)
                    .entity(ResourceUtil.error("title, type, date, and venue cannot be blank"))
                    .build();
        }
        if (event.getMaxParticipants() <= 0) {
            return Response.status(400)
                    .entity(ResourceUtil.error("maxParticipants must be greater than 0"))
                    .build();
        }

        boolean saved = firebase.updateEvent(event);
        if (!saved) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Failed to update event — please try again"))
                    .build();
        }

        EventCache.invalidate("all_events");

        return Response.ok(eventToJson(event).toString()).build();
    }

    // =========================================================================
    // DELETE /api/events/{eventId} — Delete an event  [OWN_OR_ADMIN]
    // =========================================================================

    /**
     * Permanently deletes an event and all its registration records.
     * Only the event's publisher or an admin may delete.
     */
    @DELETE
    @Path("{eventId}")
    @Secured("OWN_OR_ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response deleteEvent(@PathParam("eventId") String eventId) {
        String callerStudentId = (String) requestContext.getProperty("callerStudentId");
        String callerRole      = (String) requestContext.getProperty("callerRole");

        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        // Ownership check for students
        if ("student".equals(callerRole) && !callerStudentId.equals(event.getPublisherID())) {
            return Response.status(403)
                    .entity(ResourceUtil.error("You can only delete your own events"))
                    .build();
        }

        boolean deleted = firebase.deleteEvent(eventId);
        if (!deleted) {
            return Response.status(500)
                    .entity(ResourceUtil.error("Failed to delete event — please try again"))
                    .build();
        }

        EventCache.invalidate("all_events");

        return Response.ok(new JSONObject()
                .put("status", "deleted")
                .put("eventId", eventId)
                .toString()).build();
    }

    // =========================================================================
    // DELETE /api/events/{eventId}/registrations/{studentId} — Remove registration [ADMIN]
    // =========================================================================

    /**
     * Removes a student's registration from an event and decrements the attendee count.
     * Admin-only operation (used from the Admin Panel).
     */
    @DELETE
    @Path("{eventId}/registrations/{studentId}")
    @Secured("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response removeRegistration(
            @PathParam("eventId") String eventId,
            @PathParam("studentId") String studentId) {

        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        if (!firebase.isStudentRegistered(eventId, studentId)) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Student " + studentId + " is not registered for this event"))
                    .build();
        }

        // Decrement attendee count
        int newCount = Math.max(0, event.getCurrentAttendees() - 1);
        event.setCurrentAttendees(newCount);
        firebase.updateEvent(event);

        // Remove registration record
        firebase.removeRegistration(eventId, studentId);

        EventCache.invalidate("all_events");

        return Response.ok(new JSONObject()
                .put("status", "removed")
                .put("eventId", eventId)
                .put("studentId", studentId)
                .put("currentAttendees", newCount)
                .toString()).build();
    }

    // =========================================================================
    // POST /api/events/{eventId}/cancel — Student self-cancels registration  [PUBLIC]
    // =========================================================================

    /**
     * Allows a student to self-cancel their registration for an event.
     * - Decrements the attendee count
     * - Removes the registration record
     * - Records a cancellation so re-registration is blocked and the UI shows "Cancelled"
     *
     * Request body (JSON):
     * { "studentId": "N0123456" }
     *
     * Responses:
     *   200 OK  — cancellation recorded
     *   400     — missing studentId
     *   404     — event not found, or student not registered
     *   409     — already cancelled
     */
    @POST
    @Path("{eventId}/cancel")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response cancelRegistration(@PathParam("eventId") String eventId, String bodyStr) {
        String studentId = null;
        try {
            JSONObject body = new JSONObject(bodyStr != null ? bodyStr : "{}");
            studentId = body.optString("studentId", null);
        } catch (Exception ex) {
            return Response.status(400).entity(ResourceUtil.error("Invalid JSON body")).build();
        }
        if (ResourceUtil.isBlank(studentId)) {
            return Response.status(400).entity(ResourceUtil.error("studentId is required")).build();
        }

        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404).entity(ResourceUtil.error("Event not found: " + eventId)).build();
        }

        // Already cancelled?
        if (firebase.hasCancelled(eventId, studentId)) {
            return Response.status(409).entity(ResourceUtil.error("Registration already cancelled")).build();
        }

        // Not registered?
        if (!firebase.isStudentRegistered(eventId, studentId)) {
            return Response.status(404).entity(ResourceUtil.error("Student is not registered for this event")).build();
        }

        // Decrement attendee count
        int newCount = Math.max(0, event.getCurrentAttendees() - 1);
        event.setCurrentAttendees(newCount);
        firebase.updateEvent(event);

        // Remove registration record
        firebase.removeRegistration(eventId, studentId);

        // Record the cancellation (prevents re-registration)
        firebase.saveCancellation(eventId, studentId);

        EventCache.invalidate("all_events");

        JSONObject result = new JSONObject();
        result.put("status", "cancelled");
        result.put("eventId", eventId);
        result.put("studentId", studentId);
        result.put("currentAttendees", newCount);
        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // GET /api/events/{eventId}/registrations — List registrations  [ADMIN]
    // =========================================================================

    /**
     * Returns the list of studentIds registered for a given event.
     * Used by the Admin Panel to show bookings.
     */
    @GET
    @Path("{eventId}/registrations")
    @Secured("ADMIN")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getRegistrations(@PathParam("eventId") String eventId) {
        Event event = firebase.getEventById(eventId);
        if (event == null) {
            return Response.status(404)
                    .entity(ResourceUtil.error("Event not found: " + eventId))
                    .build();
        }

        List<String> registrations = firebase.getRegistrations(eventId);

        JSONObject result = new JSONObject();
        result.put("eventId", eventId);
        result.put("registrations", new JSONArray(registrations));
        result.put("count", registrations.size());
        return Response.ok(result.toString()).build();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Converts an Event POJO to a JSONObject for the response body. */
    private JSONObject eventToJson(Event e) {
        JSONObject obj = new JSONObject();
        obj.put("eventId", e.getEventId());
        obj.put("publisherID", e.getPublisherID() != null ? e.getPublisherID() : "");
        obj.put("title", e.getTitle());
        obj.put("type", e.getType());
        obj.put("date", e.getDate());
        obj.put("time", e.getTime() != null ? e.getTime() : "");
        obj.put("venue", e.getVenue());
        obj.put("cost", e.getCost());
        obj.put("maxParticipants", e.getMaxParticipants());
        obj.put("currentAttendees", e.getCurrentAttendees());
        obj.put("spotsRemaining", e.getMaxParticipants() - e.getCurrentAttendees());
        obj.put("latitude", e.getLatitude());
        obj.put("longitude", e.getLongitude());
        obj.put("averageRating", e.getAverageRating());
        obj.put("ratingCount", e.getRatingCount());
        obj.put("source", e.getSource() != null ? e.getSource() : "local");
        obj.put("weather", e.getWeather() != null ? e.getWeather() : "");
        return obj;
    }

}
