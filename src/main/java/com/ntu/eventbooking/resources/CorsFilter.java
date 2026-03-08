package com.ntu.eventbooking.resources;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;
import java.io.IOException;

/**
 * CORS (Cross-Origin Resource Sharing) filter applied to every HTTP response.
 *
 * Design decision: Required because the Bootstrap frontend (index.html) makes
 * fetch() calls to http://localhost:8080/api/... from the browser. Without these
 * headers the browser's same-origin policy blocks every request with a CORS error.
 *
 * @Provider — Jersey automatically registers this class because it is in the same
 * package scanned by jersey.config.server.provider.packages (web.xml). No manual
 * registration needed in ApplicationConfig.
 *
 * Access-Control-Allow-Origin: * permits any origin (appropriate for a development
 * coursework project). In production this would be restricted to the frontend's domain.
 */
@Provider
public class CorsFilter implements ContainerResponseFilter {

    @Override
    public void filter(ContainerRequestContext requestContext,
                       ContainerResponseContext responseContext) throws IOException {

        responseContext.getHeaders().add("Access-Control-Allow-Origin", "*");
        responseContext.getHeaders().add("Access-Control-Allow-Methods",
                "GET, POST, PUT, DELETE, OPTIONS");
        responseContext.getHeaders().add("Access-Control-Allow-Headers",
                "Content-Type, Authorization, Accept");
        responseContext.getHeaders().add("Access-Control-Max-Age", "86400");
    }
}
