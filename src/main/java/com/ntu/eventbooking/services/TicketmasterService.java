package com.ntu.eventbooking.services;

import com.ntu.eventbooking.models.Event;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Integrates with the Ticketmaster Discovery API to fetch real public events
 * near a given city and merge them into the GET /api/events response.
 *
 * API documentation: https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
 *
 * Design decision: All failures (network errors, API errors, missing API key)
 * are caught and logged silently — this method always returns a list (possibly
 * empty) so that a Ticketmaster outage never breaks the main GET /events endpoint.
 *
 * Configuration:
 *   Set the environment variable TICKETMASTER_API_KEY to your Ticketmaster API key.
 *   Register for a free key at https://developer.ticketmaster.com/
 */
public class TicketmasterService {

    private static final String TM_BASE_URL = "https://app.ticketmaster.com/discovery/v2/events.json";

    /**
     * Fetches up to {@code limit} public events from Ticketmaster in the given city.
     *
     * @param city  City name to search in (e.g. "Nottingham")
     * @param limit Maximum number of events to return
     * @return List of Event objects with source="ticketmaster"; empty list on any error
     */
    public static List<Event> fetchEvents(String city, int limit) {
        List<Event> events = new ArrayList<>();

        String apiKey = System.getenv("TICKETMASTER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[TicketmasterService] TICKETMASTER_API_KEY not set — skipping Ticketmaster events");
            return events;
        }

        // No classificationName filter — omitting it returns ALL event types (concerts,
        // sports, theatre, comedy, etc.) including paid events.
        // includeTest=no  → exclude internal Ticketmaster test events
        // sort=date,asc   → upcoming events first
        String url = TM_BASE_URL
                + "?city=" + city
                + "&countryCode=GB"
                + "&size=" + limit
                + "&includeTest=no"   // real events only, no test/dummy entries
                + "&sort=date,asc"    // soonest first
                + "&apikey=" + apiKey;

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Accept", "application/json");

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (statusCode != 200) {
                    System.out.println("[TicketmasterService] API returned status " + statusCode);
                    return events;
                }

                JSONObject root = new JSONObject(body);

                // Ticketmaster wraps results inside "_embedded" → "events"
                JSONObject embedded = root.optJSONObject("_embedded");
                if (embedded == null) {
                    System.out.println("[TicketmasterService] No '_embedded' in response (no events found)");
                    return events;
                }

                JSONArray results = embedded.optJSONArray("events");
                if (results == null) {
                    System.out.println("[TicketmasterService] No 'events' array in response");
                    return events;
                }

                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    Event e = parseTicketmasterEvent(item);
                    if (e != null) {
                        events.add(e);
                    }
                }

                System.out.println("[TicketmasterService] Fetched " + events.size() + " events from Ticketmaster");

            }
        } catch (Exception ex) {
            // Network failure, timeout, JSON parse error — log and return empty list
            System.out.println("[TicketmasterService] Request failed: " + ex.getMessage());
        }

        return events;
    }

    /**
     * Maps a single Ticketmaster API result object to an Event model.
     * Returns null if the result is missing essential fields.
     */
    private static Event parseTicketmasterEvent(JSONObject item) {
        try {
            Event e = new Event();

            // Prefix ID to avoid collisions with local and Skiddle event IDs.
            // Use slugified title as document ID for readability in Firestore console.
            String eventName = item.optString("name", "Ticketmaster Event");
            e.setEventId("ticketmaster-" + FirebaseService.slugify(eventName));
            e.setTitle(eventName);

            // Date and time live inside "dates" → "start" → "localDate" / "localTime"
            // localTime is optional — Ticketmaster omits it for TBD events
            String tmTime = "";
            JSONObject dates = item.optJSONObject("dates");
            if (dates != null) {
                JSONObject start = dates.optJSONObject("start");
                if (start != null) {
                    e.setDate(start.optString("localDate", ""));
                    // localTime is "HH:mm:ss" — trim to "HH:mm" for our formatTime() function
                    String localTime = start.optString("localTime", "");
                    if (localTime.length() >= 5) {
                        tmTime = localTime.substring(0, 5);
                    }
                } else {
                    e.setDate("");
                }
            } else {
                e.setDate("");
            }
            // tmTime is set later after type is resolved (so defaultTimeForType can use it)

            // Venue name and coordinates are inside "_embedded" → "venues[0]"
            JSONObject embeddedVenue = item.optJSONObject("_embedded");
            if (embeddedVenue != null) {
                JSONArray venues = embeddedVenue.optJSONArray("venues");
                if (venues != null && venues.length() > 0) {
                    JSONObject venue = venues.getJSONObject(0);
                    e.setVenue(venue.optString("name", "Unknown Venue"));

                    JSONObject location = venue.optJSONObject("location");
                    if (location != null) {
                        e.setLatitude(parseCoord(location.opt("latitude")));
                        e.setLongitude(parseCoord(location.opt("longitude")));
                    } else {
                        e.setLatitude(52.9548);
                        e.setLongitude(-1.1581);
                    }
                } else {
                    e.setVenue("Unknown Venue");
                    e.setLatitude(52.9548);
                    e.setLongitude(-1.1581);
                }
            } else {
                e.setVenue("Unknown Venue");
                e.setLatitude(52.9548);
                e.setLongitude(-1.1581);
            }

            // Entry price — Ticketmaster provides a "priceRanges" array when price is known.
            // When absent it does NOT mean the event is free — price info just isn't in the API.
            // Use -1.0 to signal "price unknown / see website" so the UI doesn't show £0.
            JSONArray priceRanges = item.optJSONArray("priceRanges");
            if (priceRanges != null && priceRanges.length() > 0) {
                e.setCost(priceRanges.getJSONObject(0).optDouble("min", 0.0));
            } else {
                e.setCost(-1.0); // price not provided by API — show "See website" in UI
            }

            // Event type — Ticketmaster uses "classifications[0].segment.name"
            JSONArray classifications = item.optJSONArray("classifications");
            if (classifications != null && classifications.length() > 0) {
                JSONObject segment = classifications.getJSONObject(0).optJSONObject("segment");
                String segmentName = segment != null ? segment.optString("name", "other") : "other";
                e.setType(mapTicketmasterType(segmentName));
            } else {
                e.setType("other");
            }

            // Apply time — use API value if present, otherwise fall back to type-based default
            if (tmTime.isEmpty()) {
                tmTime = defaultTimeForType(e.getType());
            }
            e.setTime(tmTime);

            e.setMaxParticipants(200);
            e.setCurrentAttendees(0);
            e.setAverageRating(0.0);

            e.setRatingCount(0);

            e.setSource("ticketmaster");
            e.setPublisherID("ticketmaster");

            return e;
        } catch (Exception ex) {
            System.out.println("[TicketmasterService] Failed to parse event: " + ex.getMessage());
            return null;
        }
    }

    /** Converts a Ticketmaster segment name to our local type vocabulary. */
    private static String mapTicketmasterType(String segment) {
        switch (segment) {
            case "Music":           return "concert";
            case "Sports":          return "sport";
            case "Arts & Theatre":  return "social";
            default:                return "other";
        }
    }

    /**
     * Returns a realistic default start time (HH:mm, 24h) when the API provides none.
     * Concerts and social events are evening; sport and study are afternoon.
     */
    private static String defaultTimeForType(String type) {
        if (type == null) return "19:00";
        switch (type) {
            case "concert": return "19:30";
            case "social":  return "19:00";
            case "gaming":  return "20:00";
            case "sport":   return "14:00";
            case "study":   return "13:00";
            default:        return "19:00";
        }
    }

    /** Safely parses a coordinate value that may arrive as String or Number. */
    private static double parseCoord(Object val) {
        if (val == null) return 0.0;
        if (val instanceof Number) return ((Number) val).doubleValue();
        try { return Double.parseDouble(val.toString()); }
        catch (NumberFormatException ex) { return 0.0; }
    }
}
