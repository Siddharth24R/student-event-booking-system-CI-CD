package com.ntu.eventbooking.services;

import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Integrates with the OpenWeatherMap Current Weather API to provide live weather
 * conditions at each event location, enriching the GET /api/events response.
 *
 * API documentation: https://openweathermap.org/current
 *
 * Design decision: All failures return an empty string rather than throwing an exception.
 * Weather data is supplementary — a weather API outage must never break the events listing.
 *
 * Configuration:
 *   Set the environment variable OPENWEATHER_API_KEY to your OpenWeatherMap API key.
 *   Register for a free key at https://openweathermap.org/api
 *
 * Example output: "15°C, Clouds" or "8°C, Light rain"
 */
public class WeatherService {

    private static final String OWM_URL = "https://api.openweathermap.org/data/2.5/weather";

    /**
     * Returns a human-readable weather summary for the given coordinates.
     * Returns empty string on any error (missing API key, network failure, bad response).
     *
     * @param latitude  Decimal latitude
     * @param longitude Decimal longitude
     * @return Weather string e.g. "15°C, Clouds" or "" on failure
     */
    public static String getWeather(double latitude, double longitude) {
        String apiKey = System.getenv("OPENWEATHER_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            // API key not configured — return silently without cluttering logs
            return "";
        }

        // Skip invalid coordinates (e.g. default 0,0 placeholder)
        if (latitude == 0.0 && longitude == 0.0) {
            return "";
        }

        String url = OWM_URL
                + "?lat=" + latitude
                + "&lon=" + longitude
                + "&appid=" + apiKey
                + "&units=metric";  // Celsius

        try (CloseableHttpClient httpClient = HttpClients.createDefault()) {
            HttpGet request = new HttpGet(url);
            request.setHeader("Accept", "application/json");

            try (CloseableHttpResponse response = httpClient.execute(request)) {
                int statusCode = response.getStatusLine().getStatusCode();
                String body = EntityUtils.toString(response.getEntity(), "UTF-8");

                if (statusCode != 200) {
                    System.out.println("[WeatherService] API returned status " + statusCode);
                    return "";
                }

                JSONObject root = new JSONObject(body);

                // Temperature is in the "main" object
                double temp = root.getJSONObject("main").getDouble("temp");
                int tempRounded = (int) Math.round(temp);

                // Weather description is in the first element of the "weather" array
                JSONArray weatherArr = root.getJSONArray("weather");
                String description = weatherArr.getJSONObject(0)
                        .getString("description");

                // Capitalise first letter of description for display
                description = description.substring(0, 1).toUpperCase()
                        + description.substring(1);

                return tempRounded + "°C, " + description;
            }
        } catch (Exception ex) {
            System.out.println("[WeatherService] Request failed: " + ex.getMessage());
            return "";
        }
    }
}
