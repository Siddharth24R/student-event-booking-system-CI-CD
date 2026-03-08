//run

//  docker build -t student-event-booking .
// .\run.ps1

//output:

// http://localhost:8080/


package com.ntu.eventbooking;
import javax.ws.rs.core.Application;

/**
 * JAX-RS Application configuration class.
 *
 * Design decision: No @ApplicationPath annotation here.
 * The URL path mapping (/api/*) is already defined in web.xml via the Jersey servlet mapping.
 * Having both @ApplicationPath and web.xml servlet mapping causes a double-path conflict
 * in Tomcat 9 (requests would need to go to /api/api/...).
 *
 * Jersey discovers all @Path resources and @Provider filters automatically by scanning
 * the package listed in jersey.config.server.provider.packages (web.xml).
 */
public class ApplicationConfig extends Application {
    // No additional configuration needed — Jersey scans com.ntu.eventbooking.resources
    // for @Path resource classes and @Provider filters (including CorsFilter)
}
