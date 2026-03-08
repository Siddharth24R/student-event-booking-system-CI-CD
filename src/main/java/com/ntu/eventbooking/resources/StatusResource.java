package com.ntu.eventbooking.resources;

import com.ntu.eventbooking.services.FirebaseService;
import org.json.JSONObject;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

/**
 * GET /api/status — returns Firebase connection status.
 * Useful for diagnosing whether Firebase credentials are loaded.
 */
@Path("status")
public class StatusResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response status() {
        FirebaseService firebase = FirebaseService.getInstance();
        boolean connected = firebase.isFirebaseAvailable();

        JSONObject result = new JSONObject();
        result.put("firebase", connected ? "connected" : "fallback");
        result.put("mode", connected ? "Firebase Firestore" : "in-memory (data not persisted)");
        if (!connected) {
            result.put("fix", "Place serviceAccountKey.json in src/main/resources/ and rebuild");
        }

        return Response.ok(result.toString()).build();
    }
}
