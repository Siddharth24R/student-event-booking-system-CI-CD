package com.ntu.eventbooking.resources;

import org.json.JSONObject;

/** Shared helper methods for resource classes. */
class ResourceUtil {

    private ResourceUtil() {}

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String error(String message) {
        return new JSONObject().put("error", message).toString();
    }
}
