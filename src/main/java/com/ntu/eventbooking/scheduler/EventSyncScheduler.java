package com.ntu.eventbooking.scheduler;

import com.ntu.eventbooking.cache.EventCache;
import com.ntu.eventbooking.models.Event;
import com.ntu.eventbooking.services.FirebaseService;
import com.ntu.eventbooking.services.SkiddleService;
import com.ntu.eventbooking.services.TicketmasterService;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Presubscribed workflow scheduler — the core of the "proactive orchestration" pattern.
 *
 * Instead of calling Skiddle and Ticketmaster reactively on every GET /api/events request,
 * this scheduler polls both APIs every 5 minutes and persists the results into Firestore.
 * GET /api/events then serves events entirely from the database (zero live external calls
 * on read), making it faster and demonstrating a proper presubscribed workflow architecture.
 *
 * Lifecycle:
 *   start()    — called by AppStartupListener.contextInitialized() when Tomcat deploys the WAR
 *   shutdown() — called by AppStartupListener.contextDestroyed() when Tomcat undeploys
 *
 * Design decision: Uses a single-thread ScheduledExecutorService rather than a Spring
 * @Scheduled bean — this is a plain Java EE app with no Spring dependency.
 */
public class EventSyncScheduler {

    private static final int SYNC_INTERVAL_MINUTES = 5;

    private static EventSyncScheduler instance;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private EventSyncScheduler() {}

    /** Thread-safe singleton accessor. */
    public static synchronized EventSyncScheduler getInstance() {
        if (instance == null) {
            instance = new EventSyncScheduler();
        }
        return instance;
    }

    /**
     * Starts the scheduler. The first sync fires immediately (initialDelay=0)
     * so external events are in Firestore before any user loads the page.
     * Subsequent syncs fire every SYNC_INTERVAL_MINUTES minutes.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(
                this::syncExternalEvents,
                0,                          // initialDelay=0 → run immediately on startup
                SYNC_INTERVAL_MINUTES,
                TimeUnit.MINUTES
        );
        System.out.println("[EventSyncScheduler] Started — syncing every " + SYNC_INTERVAL_MINUTES + " minutes");
    }

    /** Gracefully stops the scheduler when the application shuts down. */
    public void shutdown() {
        scheduler.shutdownNow();
        System.out.println("[EventSyncScheduler] Shut down");
    }

    /**
     * Runs an immediate sync on demand (called by POST /api/admin/sync).
     * Runs synchronously on the calling thread and returns the number of events imported.
     */
    public static int syncNow() {
        List<Event> allExternal = new ArrayList<>();

        List<Event> skiddleEvents = SkiddleService.fetchNearbyEvents(52.9548, -1.1581);
        allExternal.addAll(skiddleEvents);

        List<Event> tmEvents = TicketmasterService.fetchEvents("Nottingham", 12);
        allExternal.addAll(tmEvents);

        FirebaseService firebase = FirebaseService.getInstance();
        for (Event event : allExternal) {
            firebase.saveExternalEvent(event);
        }

        EventCache.invalidateAll();

        System.out.println("[EventSyncScheduler] Manual sync: imported " + allExternal.size() + " external events");
        return allExternal.size();
    }

    /**
     * The scheduled task. Fetches events from Skiddle and Ticketmaster,
     * persists them to Firestore (upsert by eventId), and invalidates the cache.
     *
     * Failures inside individual services are already swallowed by those services
     * (they return empty lists). Wrap the whole method in try/catch so a bug here
     * never crashes the scheduler thread and breaks future runs.
     */
    private void syncExternalEvents() {
        try {
            String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            System.out.println("[EventSyncScheduler] Sync started at " + time);

            List<Event> allExternal = new ArrayList<>();

            // Fetch from Skiddle (10-mile radius around Nottingham city centre)
            List<Event> skiddleEvents = SkiddleService.fetchNearbyEvents(52.9548, -1.1581);
            allExternal.addAll(skiddleEvents);

            // Fetch from Ticketmaster (city-level search)
            List<Event> tmEvents = TicketmasterService.fetchEvents("Nottingham", 12);
            allExternal.addAll(tmEvents);

            // Persist each event to Firestore — saveExternalEvent() uses the eventId as
            // the document ID, so repeated syncs upsert (overwrite) rather than duplicate
            FirebaseService firebase = FirebaseService.getInstance();
            for (Event event : allExternal) {
                firebase.saveExternalEvent(event);
            }

            // Invalidate cache so the next GET /api/events reflects the fresh data
            EventCache.invalidateAll();

            System.out.println("[EventSyncScheduler] Synced " + allExternal.size()
                    + " external events (" + skiddleEvents.size() + " Skiddle, "
                    + tmEvents.size() + " Ticketmaster) at " + time);

        } catch (Exception ex) {
            // Catch-all: prevents any unexpected error from killing the scheduler thread
            System.out.println("[EventSyncScheduler] Sync failed: " + ex.getMessage());
        }
    }
}
