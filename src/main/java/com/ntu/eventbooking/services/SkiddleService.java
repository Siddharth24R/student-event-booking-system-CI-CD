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
 * Integrates with the Skiddle Public Events API to fetch real public events
 * near a given location and merge them into the GET /api/events response.
 *
 * API documentation: https://www.skiddle.com/api/v1/events/search/
 *
 * Design decision: All failures (network errors, API errors, missing API key)
 * are caught and logged silently — this method always returns a list (possibly
 * empty) so that a Skiddle outage never breaks the main GET /events endpoint.
 *
 * Configuration:
 *   Set the environment variable SKIDDLE_API_KEY to your Skiddle API key.
 *   Register for a free key at https://www.skiddle.com/api/
 *
 * Important: Skiddle uses "latitude" and "longitude" as parameter names —
 * NOT "lat"/"lng" — as shown in the official API documentation.
 */
public class SkiddleService {

    private static final String SKIDDLE_BASE_URL = "https://www.skiddle.com/api/v1/events/search/";

    /**
     * Fetches up to 5 public events from Skiddle near the given coordinates.
     *
     * @param latitude  Decimal latitude of the search centre (e.g. 52.9548 for Nottingham)
     * @param longitude Decimal longitude of the search centre (e.g. -1.1581 for Nottingham)
     * @return List of Event objects with source="skiddle"; empty list on any error
     */
    public static List<Event> fetchNearbyEvents(double latitude, double longitude) {
        List<Event> events = new ArrayList<>();

        String apiKey = System.getenv("SKIDDLE_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.out.println("[SkiddleService] SKIDDLE_API_KEY not set — skipping external events");
            return events;
        }

        // Today's date in YYYY-MM-DD format — used as minDate so only upcoming events are returned
        String today = java.time.LocalDate.now().toString();

        // Build the request URL — note: Skiddle uses "latitude"/"longitude" not "lat"/"lng"
        // No ticketed filter — omitting it returns ALL events (free + paid).
        // b=0      → include non-featured events (broader result set)
        // minDate  → only upcoming events
        // order=date → soonest first
        String url = SKIDDLE_BASE_URL
                + "?api_key=" + apiKey
                + "&latitude=" + latitude
                + "&longitude=" + longitude
                + "&radius=10"          // 10-mile radius around Nottingham
                + "&limit=12"           // fetch 12 events
                + "&b=0"                // include non-featured events
                + "&minDate=" + today   // only upcoming events
                + "&order=date";        // soonest first

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Accept", "application/json");

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (statusCode != 200) {
                    System.out.println("[SkiddleService] API returned status " + statusCode);
                    return events;
                }

                JSONObject root = new JSONObject(body);

                // Skiddle wraps results in a "results" array at the top level
                JSONArray results = root.optJSONArray("results");
                if (results == null) {
                    System.out.println("[SkiddleService] No 'results' array in response");
                    return events;
                }

                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.getJSONObject(i);
                    Event e = parseSkiddleEvent(item);
                    if (e != null) {
                        events.add(e);
                    }
                }

                System.out.println("[SkiddleService] Fetched " + events.size() + " events from Skiddle");

            }
        } catch (Exception ex) {
            // Network failure, timeout, JSON parse error — log and return empty list
            System.out.println("[SkiddleService] Request failed: " + ex.getMessage());
        }

        return events;
    }

    /**
     * Maps a single Skiddle API result object to an Event model.
     * Returns null if the result is missing essential fields.
     */
    private static Event parseSkiddleEvent(JSONObject item) {
        try {
            Event e = new Event();

            String eventName = item.optString("eventname", "Skiddle Event");
            e.setEventId("skiddle-" + FirebaseService.slugify(eventName));
            e.setTitle(eventName);
            e.setDate(item.optString("date", ""));

            // Extract start time. Skiddle returns two usable sources (in priority order):
            //
            // 1. openingtimes.doorsopen — an OBJECT (not array) with field "doorsopen": "HH:mm"
            //    e.g. {"doorsopen":"18:30","doorsclose":"21:45","lastentry":"19:30"}
            //
            // 2. startdate — a top-level ISO datetime string: "2026-03-21T18:30:00+00:00"
            //    Extract the HH:mm portion after the "T".
            //
            // NOTE: There is no "starttime" or "openingtimes" array in real Skiddle responses.
            String skiddleTime = "";

            // Priority 1: openingtimes object → doorsopen
            JSONObject openingTimesObj = item.optJSONObject("openingtimes");
            if (openingTimesObj != null) {
                String doorsOpen = openingTimesObj.optString("doorsopen", "");
                // doorsopen is already "HH:mm" — trim to 5 chars to be safe
                if (doorsOpen.length() >= 5) {
                    skiddleTime = doorsOpen.substring(0, 5);
                }
            }

            // Priority 2: startdate ISO datetime — "2026-03-21T18:30:00+00:00"
            if (skiddleTime.isEmpty()) {
                String startdate = item.optString("startdate", "");
                if (startdate.contains("T")) {
                    String timePart = startdate.substring(startdate.indexOf('T') + 1);
                    if (timePart.length() >= 5) {
                        skiddleTime = timePart.substring(0, 5);
                    }
                }
            }
            // Skiddle event type → map to our type vocabulary where possible
            String skiddleType = item.optString("EventCode", "other").toLowerCase();
            e.setType(mapSkiddleType(skiddleType));

            // If no time was extracted from the API, fall back to a sensible default by type
            if (skiddleTime.isEmpty()) {
                skiddleTime = defaultTimeForType(e.getType());
            }
            e.setTime(skiddleTime);

            // Extract entry price. Skiddle returns entryprice as a number, "0", or null.
            // JSONObject.optDouble() treats JSON null as the default (0.0), which would
            // make paid events look free. Use opt() first to distinguish null from 0.
            Object rawPrice = item.opt("entryprice");
            if (rawPrice == null || rawPrice == JSONObject.NULL) {
                // Price not provided by API — mark as -1 so the UI can show "See website"
                e.setCost(-1.0);
            } else {
                e.setCost(item.optDouble("entryprice", 0.0));
            }

            // Skiddle capacity is not always provided
            e.setMaxParticipants(item.optInt("capacity", 200));

            e.setCurrentAttendees(item.optInt("goingcount", 0));
            e.setAverageRating(0.0);
            e.setRatingCount(0);

            // venue is a JSON object — extract name, town, and coordinates from it.
            // Using optString("venue") would return the raw JSON string (a bug), so
            // we must use optJSONObject("venue") and read individual fields.
            JSONObject venue = item.optJSONObject("venue");
            if (venue != null) {
                String venueName = venue.optString("name", "").trim();
                String town      = venue.optString("town", "").trim();
                // Append town only if it is not already part of the venue name
                if (!venueName.isEmpty() && !town.isEmpty()
                        && !venueName.toLowerCase().contains(town.toLowerCase())) {
                    e.setVenue(venueName + ", " + town);
                } else if (!venueName.isEmpty()) {
                    e.setVenue(venueName);
                } else {
                    e.setVenue("Unknown Venue");
                }
                e.setLatitude(parseCoord(venue.opt("latitude")));
                e.setLongitude(parseCoord(venue.opt("longitude")));
            } else {
                e.setVenue("Unknown Venue");
                e.setLatitude(52.9548);
                e.setLongitude(-1.1581);
            }

            e.setSource("skiddle");
            e.setPublisherID("skiddle");

            return e;
        } catch (Exception ex) {
            System.out.println("[SkiddleService] Failed to parse event: " + ex.getMessage());
            return null;
        }
    }

    /** Converts a Skiddle EventCode to our local type vocabulary. */
    private static String mapSkiddleType(String skiddleCode) {
        switch (skiddleCode) {
            case "sport": return "sport";
            case "live":  return "concert";
            case "club":  return "social";
            case "gam":   return "gaming";
            default:      return "other";
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
