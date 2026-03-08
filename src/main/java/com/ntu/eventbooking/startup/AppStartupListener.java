package com.ntu.eventbooking.startup;

import com.ntu.eventbooking.scheduler.EventSyncScheduler;
import com.ntu.eventbooking.services.FirebaseService;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Servlet lifecycle listener that starts and stops the EventSyncScheduler.
 *
 * Tomcat calls contextInitialized() when the WAR is deployed (application starts).
 * Tomcat calls contextDestroyed() when the WAR is undeployed (application stops).
 *
 * The @WebListener annotation registers this listener automatically — Tomcat 9
 * supports annotation-based listener registration without needing a web.xml entry.
 * The <listener> entry in web.xml is also present as an explicit registration
 * (both work; having both is harmless but using web.xml entry is more visible).
 */
@WebListener
public class AppStartupListener implements ServletContextListener {

    /**
     * Called by Tomcat when the application starts.
     * Starts the EventSyncScheduler which immediately fetches Skiddle and
     * Ticketmaster events and stores them in Firestore, then repeats every 5 minutes.
     */
    @Override
    public void contextInitialized(ServletContextEvent sce) {
        System.out.println("[AppStartupListener] Application starting — initialising EventSyncScheduler");
        // Seed default admin account if it doesn't already exist
        FirebaseService.getInstance().seedDefaultAdmin();
        EventSyncScheduler.getInstance().start();
    }

    /**
     * Called by Tomcat when the application stops.
     * Gracefully shuts down the ScheduledExecutorService to prevent thread leaks.
     */
    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        System.out.println("[AppStartupListener] Application stopping — shutting down EventSyncScheduler");
        EventSyncScheduler.getInstance().shutdown();
    }
}
