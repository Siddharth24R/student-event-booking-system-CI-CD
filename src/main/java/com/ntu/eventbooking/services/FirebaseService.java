package com.ntu.eventbooking.services;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.cloud.firestore.*;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.UserRecord;
import com.google.firebase.cloud.FirestoreClient;
import com.ntu.eventbooking.models.Event;
import com.ntu.eventbooking.models.Student;
import org.json.JSONArray;
import org.json.JSONObject;

import org.mindrot.jbcrypt.BCrypt;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Singleton service for all Firebase Firestore database operations.
 *
 * Initialisation strategy:
 *   1. Tries to load the Firebase service account key from the path in the
 *      FIREBASE_CREDENTIALS environment variable.
 *   2. Falls back to reading events.json from the classpath (src/main/resources/)
 *      if Firebase initialisation fails — this lets the API run without a Firebase
 *      account during development and testing.
 *
 * Collections used in Firestore:
 *   - "students"      — one document per registered student (keyed by email)
 *   - "events"        — one document per event (slugified title as document ID, e.g. "gaming-night")
 *   - "registrations" — sub-collection: events/{eventId}/registrations/{studentId}
 */
public class FirebaseService {

    private static FirebaseService instance;
    private Firestore db;
    private boolean firebaseAvailable = false;

    // In-memory fallback store (loaded from events.json on the classpath)
    private final List<Event>              fallbackEvents        = new ArrayList<>();
    private final Map<String, Student>     fallbackStudents      = new HashMap<>();
    // eventId -> set of studentIds
    private final Map<String, Set<String>> fallbackRegistrations = new HashMap<>();
    // "studentId:eventId" -> starCount  (per-student rating store)
    private final Map<String, Integer>     fallbackStudentRatings = new HashMap<>();
    // set of "studentId:eventId" keys where student cancelled their registration
    private final Set<String>              fallbackCancellations  = new HashSet<>();

    private FirebaseService() {
        loadFallbackData();   // always load seed data — used as fallback if Firebase DNS fails mid-session
        initFirebase();       // then attempt Firebase on top
    }

    /** Thread-safe singleton accessor. */
    public static synchronized FirebaseService getInstance() {
        if (instance == null) {
            instance = new FirebaseService();
        }
        return instance;
    }

    // =========================================================================
    // Initialisation
    // =========================================================================

    private void initFirebase() {
        // Strategy 1: FIREBASE_CREDENTIALS environment variable (absolute path to JSON file)
        String credentialsPath = System.getenv("FIREBASE_CREDENTIALS");
        if (credentialsPath != null && !credentialsPath.isBlank()) {
            try (FileInputStream serviceAccount = new FileInputStream(credentialsPath)) {
                initFromStream(serviceAccount);
                System.out.println("[FirebaseService] Connected via FIREBASE_CREDENTIALS env var");
                return;
            } catch (Exception e) {
                System.out.println("[FirebaseService] FIREBASE_CREDENTIALS path failed (" + e.getMessage() + ") — trying classpath");
            }
        }

        // Strategy 2: serviceAccountKey.json on the classpath (src/main/resources/)
        try (InputStream is = getClass().getResourceAsStream("/serviceAccountKey.json")) {
            if (is != null) {
                initFromStream(is);
                System.out.println("[FirebaseService] Connected via classpath serviceAccountKey.json");
                return;
            }
        } catch (Exception e) {
            System.out.println("[FirebaseService] Classpath serviceAccountKey.json failed (" + e.getMessage() + ")");
        }

        System.out.println("[FirebaseService] No Firebase credentials found — using in-memory fallback mode");
        System.out.println("[FirebaseService] To connect Firebase: place serviceAccountKey.json in src/main/resources/");
    }

    private void initFromStream(InputStream credStream) throws Exception {
        if (FirebaseApp.getApps().isEmpty()) {
            FirebaseOptions options = FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.fromStream(credStream))
                    .build();
            FirebaseApp.initializeApp(options);
        }
        db = FirestoreClient.getFirestore();
        firebaseAvailable = true;
    }

    /**
     * Loads events.json from the WAR classpath as a fallback when Firestore is unavailable.
     * The file lives at src/main/resources/events.json and is bundled inside the WAR.
     * Also seeds a default admin account if none exists in fallback storage.
     */
    private void loadFallbackData() {
        // Load events
        try (InputStream is = getClass().getResourceAsStream("/events.json")) {
            if (is == null) {
                System.out.println("[FirebaseService] events.json not found on classpath");
            } else {
                String jsonText = new String(is.readAllBytes(), StandardCharsets.UTF_8);
                JSONArray arr = new JSONArray(jsonText);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    Event e = jsonToEvent(obj);
                    if (e.getEventId() == null || e.getEventId().isBlank()) {
                        e.setEventId(UUID.randomUUID().toString());
                    }
                    fallbackEvents.add(e);
                }
                System.out.println("[FirebaseService] Loaded " + fallbackEvents.size() + " events from events.json");
            }
        } catch (Exception ex) {
            System.out.println("[FirebaseService] Could not load events.json: " + ex.getMessage());
        }
    }

    /**
     * Seeds the default admin account into Firestore if it does not already exist.
     * Called from AppStartupListener after Firebase is available, or stored in fallback.
     *
     * Default admin credentials:
     *   email:     admin@ntu.ac.uk
     *   password:  admin123  (stored as BCrypt hash)
     *   adminCode: NTU-ADMIN-2026
     */
    public void seedDefaultAdmin() {
        String adminEmail = "admin@ntu.ac.uk";
        if (studentExists(adminEmail)) {
            System.out.println("[FirebaseService] Default admin already exists — skipping seed");
            return;
        }
        try {
            String hash = BCrypt.hashpw("admin123", BCrypt.gensalt(12));
            Student admin = new Student("ADMIN001", "Admin", adminEmail);
            admin.setRole("admin");
            admin.setPassword(hash);
            admin.setAdminCode("NTU-ADMIN-2026");
            saveStudentWithAuth(admin);
            System.out.println("[FirebaseService] Default admin seeded: " + adminEmail);
        } catch (Exception e) {
            System.out.println("[FirebaseService] Could not seed default admin: " + e.getMessage());
        }
    }

    // =========================================================================
    // Student operations
    // =========================================================================

    /**
     * Saves a new student with authentication fields (password hash, role, adminCode).
     * Uses email as the Firestore document ID for fast lookup-by-email.
     * Returns true on success.
     */
    public boolean saveStudentWithAuth(Student student) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("studentId", student.getStudentId());
                data.put("name",      student.getName());
                data.put("email",     student.getEmail());
                data.put("password",  student.getPassword());
                data.put("role",      student.getRole() != null ? student.getRole() : "student");
                data.put("createdAt", FieldValue.serverTimestamp());
                if (student.getAdminCode() != null) {
                    data.put("adminCode", student.getAdminCode());
                }
                // Use studentId as document ID (not email) for clean Firestore structure
                db.collection("students").document(student.getStudentId()).set(data).get();
                return true;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveStudentWithAuth error: " + e.getMessage());
                return false;
            }
        } else {
            fallbackStudents.put(student.getEmail(), student);
            return true;
        }
    }

    /** Saves a new student without authentication fields (legacy method). Returns true on success. */
    public boolean saveStudent(Student student) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("studentId", student.getStudentId());
                data.put("name",      student.getName());
                data.put("email",     student.getEmail());
                data.put("role",      student.getRole() != null ? student.getRole() : "student");
                data.put("createdAt", FieldValue.serverTimestamp());
                // Use studentId as document ID (not email) for clean Firestore structure
                db.collection("students").document(student.getStudentId()).set(data).get();
                return true;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveStudent error: " + e.getMessage());
                return false;
            }
        } else {
            fallbackStudents.put(student.getEmail(), student);
            return true;
        }
    }

    /** Returns true if a student with the given email already exists. Queries by email field. */
    public boolean studentExists(String email) {
        if (firebaseAvailable) {
            try {
                QuerySnapshot snap = db.collection("students")
                        .whereEqualTo("email", email)
                        .limit(1)
                        .get().get();
                return !snap.isEmpty();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] studentExists error: " + e.getMessage());
                return false;
            }
        } else {
            return fallbackStudents.containsKey(email);
        }
    }

    /**
     * Looks up a student by email address and returns the full Student object,
     * including the BCrypt password hash for login verification.
     * Returns null if no student is found.
     */
    public Student getStudentByEmail(String email) {
        if (firebaseAvailable) {
            try {
                // Doc ID is now studentId — query by email field
                QuerySnapshot snap = db.collection("students")
                        .whereEqualTo("email", email)
                        .limit(1)
                        .get().get();
                if (snap.isEmpty()) return null;
                return docToStudent(snap.getDocuments().get(0));
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getStudentByEmail error: " + e.getMessage());
                return null;
            }
        } else {
            return fallbackStudents.get(email);
        }
    }

    /**
     * Creates a user in Firebase Authentication (so they appear in the Auth console).
     * Called alongside saveStudentWithAuth during registration.
     * Silently skips if Firebase is unavailable or the user already exists.
     */
    public void createAuthUser(String email, String plainPassword, String displayName) {
        if (!firebaseAvailable) return;
        try {
            UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                    .setEmail(email)
                    .setPassword(plainPassword)
                    .setDisplayName(displayName);
            FirebaseAuth.getInstance().createUser(request);
            System.out.println("[FirebaseService] Firebase Auth user created: " + email);
        } catch (Exception e) {
            // USER_ALREADY_EXISTS is fine — just log and continue
            System.out.println("[FirebaseService] createAuthUser skipped (" + e.getMessage() + ")");
        }
    }

    /**
     * Looks up a student by studentId (linear scan — Firestore query or fallback map).
     * Returns null if not found.
     */
    public Student getStudentById(String studentId) {
        if (firebaseAvailable) {
            try {
                // Doc ID is studentId — direct get, no query needed
                DocumentSnapshot doc = db.collection("students").document(studentId).get().get();
                if (!doc.exists()) return null;
                return docToStudent(doc);
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getStudentById error: " + e.getMessage());
                return null;
            }
        } else {
            return fallbackStudents.values().stream()
                    .filter(s -> studentId.equals(s.getStudentId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Returns all registered students (without password field in the returned objects).
     * Used by GET /api/admin/students.
     */
    public List<Student> getAllStudents() {
        if (firebaseAvailable) {
            try {
                List<Student> students = new ArrayList<>();
                QuerySnapshot snapshot = db.collection("students").get().get();
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    Student s = docToStudent(doc);
                    s.setPassword(null); // never return password hash
                    students.add(s);
                }
                return students;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getAllStudents error: " + e.getMessage());
                return new ArrayList<>(fallbackStudents.values());
            }
        } else {
            List<Student> result = new ArrayList<>(fallbackStudents.values());
            result.forEach(s -> s.setPassword(null));
            return result;
        }
    }

    // =========================================================================
    // Event operations
    // =========================================================================

    /**
     * Saves a new local event to Firestore using a slugified title as the document ID.
     * Example: "Gaming Night" → "gaming-night", or "gaming-night-2" if taken.
     * Returns the document ID on success, null on failure.
     */
    public String saveEvent(Event event) {
        if (firebaseAvailable) {
            try {
                String slug = generateUniqueSlug(event.getTitle());
                Map<String, Object> data = eventToMap(event);
                db.collection("events").document(slug).set(data).get();
                return slug;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveEvent error: " + e.getMessage());
                return null;
            }
        } else {
            String base = slugify(event.getTitle());
            String id = base;
            int counter = 2;
            final String[] idHolder = {id};
            while (fallbackEvents.stream().anyMatch(e -> idHolder[0].equals(e.getEventId()))) {
                idHolder[0] = base + "-" + counter++;
            }
            event.setEventId(idHolder[0]);
            event.setSource("local");
            fallbackEvents.add(event);
            return idHolder[0];
        }
    }

    /**
     * Saves (upserts) an external event using its own eventId as the Firestore document ID.
     * Called by EventSyncScheduler on every scheduled sync.
     *
     * Using document(eventId).set() instead of collection.add() means that running the
     * scheduler again overwrites the same document rather than creating a duplicate.
     * This is the correct pattern for a presubscribed polling workflow.
     */
    public void saveExternalEvent(Event event) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = eventToMap(event);
                db.collection("events").document(event.getEventId()).set(data).get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveExternalEvent error: " + e.getMessage());
            }
        } else {
            // Upsert into fallback in-memory list
            for (int i = 0; i < fallbackEvents.size(); i++) {
                if (fallbackEvents.get(i).getEventId().equals(event.getEventId())) {
                    fallbackEvents.set(i, event);
                    return;
                }
            }
            fallbackEvents.add(event);
        }
    }

    /** Returns all events from Firestore, or the in-memory fallback list. */
    public List<Event> getAllEvents() {
        if (firebaseAvailable) {
            try {
                List<Event> events = new ArrayList<>();
                QuerySnapshot snapshot = db.collection("events").get().get();
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    Event e = docToEvent(doc);
                    events.add(e);
                }
                return events;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getAllEvents error: " + e.getMessage() + " — returning fallback events");
                return new ArrayList<>(fallbackEvents);
            }
        } else {
            return new ArrayList<>(fallbackEvents);
        }
    }

    /** Returns a single event by its Firestore document ID, or null if not found. */
    public Event getEventById(String eventId) {
        if (firebaseAvailable) {
            try {
                DocumentSnapshot doc = db.collection("events").document(eventId).get().get();
                if (!doc.exists()) return null;
                return docToEvent(doc);
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getEventById error: " + e.getMessage());
                return null;
            }
        } else {
            return fallbackEvents.stream()
                    .filter(e -> eventId.equals(e.getEventId()))
                    .findFirst()
                    .orElse(null);
        }
    }

    /**
     * Persists any changes to an existing event (e.g. incremented attendee count).
     * Uses Firestore merge so only provided fields are updated.
     */
    public boolean updateEvent(Event event) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = eventToMap(event);
                db.collection("events").document(event.getEventId()).set(data, SetOptions.merge()).get();
                return true;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] updateEvent error: " + e.getMessage());
                return false;
            }
        } else {
            // Update in the in-memory list
            for (int i = 0; i < fallbackEvents.size(); i++) {
                if (fallbackEvents.get(i).getEventId().equals(event.getEventId())) {
                    fallbackEvents.set(i, event);
                    return true;
                }
            }
            return false;
        }
    }

    /**
     * Permanently deletes an event document from Firestore.
     * Returns true on success, false on failure.
     */
    public boolean deleteEvent(String eventId) {
        if (firebaseAvailable) {
            try {
                db.collection("events").document(eventId).delete().get();
                return true;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] deleteEvent error: " + e.getMessage());
                return false;
            }
        } else {
            return fallbackEvents.removeIf(e -> eventId.equals(e.getEventId()));
        }
    }

    /**
     * Updates the average rating and rating count for an event.
     * Called after a new rating is submitted via POST /events/{id}/rate.
     */
    public boolean addRating(String eventId, double newAvg, int newCount, double newTotal) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> update = new HashMap<>();
                update.put("averageRating", newAvg);
                update.put("ratingCount",   newCount);
                update.put("ratingTotal",   newTotal);
                db.collection("events").document(eventId).update(update).get();
                return true;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] addRating error: " + e.getMessage());
                return false;
            }
        } else {
            Event event = getEventById(eventId);
            if (event == null) return false;
            event.setAverageRating(newAvg);
            event.setRatingCount(newCount);
            event.setRatingTotal(newTotal);
            return updateEvent(event);
        }
    }

    // =========================================================================
    // Per-student rating operations
    // =========================================================================

    /**
     * Returns true if the given student has already rated this event.
     * Stored in Firestore: events/{eventId}/studentRatings/{studentId}
     */
    public boolean hasStudentRated(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                DocumentSnapshot doc = db.collection("events")
                        .document(eventId)
                        .collection("studentRatings")
                        .document(studentId)
                        .get().get();
                return doc.exists();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] hasStudentRated error: " + e.getMessage());
                return false;
            }
        } else {
            return fallbackStudentRatings.containsKey(studentId + ":" + eventId);
        }
    }

    /**
     * Returns the star rating (1–5) a student gave an event, or 0 if not rated.
     */
    public int getStudentRating(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                DocumentSnapshot doc = db.collection("events")
                        .document(eventId)
                        .collection("studentRatings")
                        .document(studentId)
                        .get().get();
                if (doc.exists() && doc.contains("rating")) {
                    Long stars = doc.getLong("rating");
                    return stars != null ? stars.intValue() : 0;
                }
                return 0;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getStudentRating error: " + e.getMessage());
                return 0;
            }
        } else {
            Integer r = fallbackStudentRatings.get(studentId + ":" + eventId);
            return r != null ? r : 0;
        }
    }

    /**
     * Persists the student's rating for an event so it can be retrieved later.
     */
    public void saveStudentRating(String eventId, String studentId, int stars) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("rating",     stars);       // renamed: "stars" → "rating" per schema
                data.put("studentId",  studentId);
                data.put("ratedAt",    FieldValue.serverTimestamp());
                db.collection("events")
                  .document(eventId)
                  .collection("studentRatings")
                  .document(studentId)
                  .set(data).get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveStudentRating error: " + e.getMessage());
            }
        } else {
            fallbackStudentRatings.put(studentId + ":" + eventId, stars);
        }
    }

    /**
     * Returns all eventIds rated by a student, mapped to their star score.
     * Used by GET /api/students/{studentId}/registrations to include userRating.
     */
    public Map<String, Integer> getAllStudentRatings(String studentId) {
        Map<String, Integer> result = new HashMap<>();
        if (firebaseAvailable) {
            try {
                QuerySnapshot eventsSnap = db.collection("events").get().get();
                for (DocumentSnapshot eventDoc : eventsSnap.getDocuments()) {
                    DocumentSnapshot ratingDoc = db.collection("events")
                            .document(eventDoc.getId())
                            .collection("studentRatings")
                            .document(studentId)
                            .get().get();
                    if (ratingDoc.exists() && ratingDoc.contains("rating")) {
                        Long stars = ratingDoc.getLong("rating");
                        if (stars != null) result.put(eventDoc.getId(), stars.intValue());
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getAllStudentRatings error: " + e.getMessage());
            }
        } else {
            String prefix = studentId + ":";
            for (Map.Entry<String, Integer> entry : fallbackStudentRatings.entrySet()) {
                if (entry.getKey().startsWith(prefix)) {
                    result.put(entry.getKey().substring(prefix.length()), entry.getValue());
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Cancellation operations
    // =========================================================================

    /**
     * Records that a student has self-cancelled their registration for an event.
     * Stored in Firestore: events/{eventId}/cancellations/{studentId}
     * Once cancelled, the student cannot re-register.
     */
    public void saveCancellation(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("studentId",   studentId);
                data.put("cancelledAt", FieldValue.serverTimestamp());
                db.collection("events")
                  .document(eventId)
                  .collection("cancellations")
                  .document(studentId)
                  .set(data).get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] saveCancellation error: " + e.getMessage());
            }
        } else {
            fallbackCancellations.add(studentId + ":" + eventId);
        }
    }

    /**
     * Returns true if the student previously cancelled their registration for this event.
     */
    public boolean hasCancelled(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                DocumentSnapshot doc = db.collection("events")
                        .document(eventId)
                        .collection("cancellations")
                        .document(studentId)
                        .get().get();
                return doc.exists();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] hasCancelled error: " + e.getMessage());
                return false;
            }
        } else {
            return fallbackCancellations.contains(studentId + ":" + eventId);
        }
    }

    /**
     * Returns all eventIds that a given student has cancelled, as a Set.
     * Used by GET /api/students/{studentId}/registrations to include isCancelled.
     */
    public Set<String> getCancelledEventIds(String studentId) {
        Set<String> result = new HashSet<>();
        if (firebaseAvailable) {
            try {
                QuerySnapshot eventsSnap = db.collection("events").get().get();
                for (DocumentSnapshot eventDoc : eventsSnap.getDocuments()) {
                    DocumentSnapshot cancelDoc = db.collection("events")
                            .document(eventDoc.getId())
                            .collection("cancellations")
                            .document(studentId)
                            .get().get();
                    if (cancelDoc.exists()) {
                        result.add(eventDoc.getId());
                    }
                }
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getCancelledEventIds error: " + e.getMessage());
            }
        } else {
            String prefix = studentId + ":";
            for (String key : fallbackCancellations) {
                if (key.startsWith(prefix)) {
                    result.add(key.substring(prefix.length()));
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Registration operations
    // =========================================================================

    /**
     * Records that a student has registered for an event.
     * Stored in Firestore as events/{eventId}/registrations/{studentId} → { studentId, registeredAt }.
     */
    public void addRegistration(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                Map<String, Object> data = new HashMap<>();
                data.put("studentId",    studentId);
                data.put("registeredAt", FieldValue.serverTimestamp());
                data.put("status",       "registered");
                db.collection("events")
                  .document(eventId)
                  .collection("registrations")
                  .document(studentId)
                  .set(data).get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] addRegistration error: " + e.getMessage());
            }
        } else {
            fallbackRegistrations
                    .computeIfAbsent(eventId, k -> new HashSet<>())
                    .add(studentId);
        }
    }

    /**
     * Returns true if the given student is already registered for the given event.
     */
    public boolean isStudentRegistered(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                DocumentSnapshot doc = db.collection("events")
                        .document(eventId)
                        .collection("registrations")
                        .document(studentId)
                        .get().get();
                return doc.exists();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] isStudentRegistered error: " + e.getMessage());
                return false;
            }
        } else {
            Set<String> regs = fallbackRegistrations.get(eventId);
            return regs != null && regs.contains(studentId);
        }
    }

    /**
     * Removes a student's registration from an event (admin action).
     */
    public void removeRegistration(String eventId, String studentId) {
        if (firebaseAvailable) {
            try {
                db.collection("events")
                  .document(eventId)
                  .collection("registrations")
                  .document(studentId)
                  .delete().get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] removeRegistration error: " + e.getMessage());
            }
        } else {
            Set<String> regs = fallbackRegistrations.get(eventId);
            if (regs != null) regs.remove(studentId);
        }
    }

    /**
     * Deletes a student account from the system by studentId.
     */
    public void deleteStudent(String studentId) {
        if (firebaseAvailable) {
            try {
                db.collection("students").document(studentId).delete().get();
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] deleteStudent error: " + e.getMessage());
            }
        } else {
            fallbackStudents.values().removeIf(s -> studentId.equals(s.getStudentId()));
        }
    }

    /**
     * Returns all eventIds that a given student has registered for.
     * Scans every event's registrations sub-collection for the studentId document.
     * Used by GET /api/students/{studentId}/registrations
     */
    public List<String> getRegisteredEventIds(String studentId) {
        if (firebaseAvailable) {
            try {
                List<String> result = new ArrayList<>();
                // Get all events
                QuerySnapshot eventsSnap = db.collection("events").get().get();
                for (DocumentSnapshot eventDoc : eventsSnap.getDocuments()) {
                    DocumentSnapshot regDoc = db.collection("events")
                            .document(eventDoc.getId())
                            .collection("registrations")
                            .document(studentId)
                            .get().get();
                    if (regDoc.exists()) {
                        result.add(eventDoc.getId());
                    }
                }
                return result;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getRegisteredEventIds error: " + e.getMessage());
                return Collections.emptyList();
            }
        } else {
            List<String> result = new ArrayList<>();
            for (Map.Entry<String, Set<String>> entry : fallbackRegistrations.entrySet()) {
                if (entry.getValue().contains(studentId)) {
                    result.add(entry.getKey());
                }
            }
            return result;
        }
    }

    /**
     * Returns the list of studentIds registered for a given event.
     * Used by GET /api/events/{id}/registrations (admin endpoint).
     */
    public List<String> getRegistrations(String eventId) {
        if (firebaseAvailable) {
            try {
                List<String> result = new ArrayList<>();
                QuerySnapshot snapshot = db.collection("events")
                        .document(eventId)
                        .collection("registrations")
                        .get().get();
                for (DocumentSnapshot doc : snapshot.getDocuments()) {
                    result.add(doc.getId());
                }
                return result;
            } catch (InterruptedException | ExecutionException e) {
                System.out.println("[FirebaseService] getRegistrations error: " + e.getMessage());
                return Collections.emptyList();
            }
        } else {
            Set<String> regs = fallbackRegistrations.get(eventId);
            return regs != null ? new ArrayList<>(regs) : Collections.emptyList();
        }
    }

    // =========================================================================
    // Conversion helpers
    // =========================================================================

    private Map<String, Object> eventToMap(Event e) {
        Map<String, Object> m = new HashMap<>();
        m.put("publisherID",      e.getPublisherID());
        m.put("title",            e.getTitle());
        m.put("type",             e.getType());
        m.put("date",             e.getDate());
        m.put("time",             e.getTime() != null ? e.getTime() : "");
        m.put("venue",            e.getVenue());
        m.put("cost",             e.getCost());
        m.put("maxParticipants",  e.getMaxParticipants());
        m.put("currentAttendees", e.getCurrentAttendees());
        m.put("latitude",         e.getLatitude());
        m.put("longitude",        e.getLongitude());
        m.put("averageRating",    e.getAverageRating());
        m.put("ratingCount",      e.getRatingCount());
        m.put("ratingTotal",      e.getRatingTotal());
        m.put("source",           e.getSource() != null ? e.getSource() : "local");
        return m;
    }

    private Event docToEvent(DocumentSnapshot doc) {
        Event e = new Event();
        e.setEventId(doc.getId());
        e.setPublisherID(doc.getString("publisherID"));
        e.setTitle(doc.getString("title"));
        e.setType(doc.getString("type"));
        e.setDate(doc.getString("date"));
        e.setTime(doc.getString("time") != null ? doc.getString("time") : "");
        e.setVenue(doc.getString("venue"));
        e.setCost(doc.getDouble("cost") != null ? doc.getDouble("cost") : 0.0);
        e.setMaxParticipants(doc.getLong("maxParticipants") != null
                ? doc.getLong("maxParticipants").intValue() : 200);
        e.setCurrentAttendees(doc.getLong("currentAttendees") != null
                ? doc.getLong("currentAttendees").intValue() : 0);
        e.setLatitude(doc.getDouble("latitude")  != null ? doc.getDouble("latitude")  : 0.0);
        e.setLongitude(doc.getDouble("longitude") != null ? doc.getDouble("longitude") : 0.0);
        e.setAverageRating(doc.getDouble("averageRating") != null ? doc.getDouble("averageRating") : 0.0);
        e.setRatingCount(doc.getLong("ratingCount") != null
                ? doc.getLong("ratingCount").intValue() : 0);
        e.setRatingTotal(doc.getDouble("ratingTotal") != null ? doc.getDouble("ratingTotal") : 0.0);
        e.setSource(doc.getString("source") != null ? doc.getString("source") : "local");
        return e;
    }

    private Event jsonToEvent(JSONObject obj) {
        Event e = new Event();
        e.setEventId(obj.optString("eventId", null));
        e.setPublisherID(obj.optString("publisherID", ""));
        e.setTitle(obj.optString("title", ""));
        e.setType(obj.optString("type", ""));
        e.setDate(obj.optString("date", ""));
        e.setTime(obj.optString("time", ""));
        e.setVenue(obj.optString("venue", ""));
        e.setCost(obj.optDouble("cost", 0.0));
        e.setMaxParticipants(obj.optInt("maxParticipants", 50));
        e.setCurrentAttendees(obj.optInt("currentAttendees", 0));
        e.setLatitude(obj.optDouble("latitude", 52.9548));
        e.setLongitude(obj.optDouble("longitude", -1.1581));
        e.setAverageRating(obj.optDouble("averageRating", 0.0));
        e.setRatingCount(obj.optInt("ratingCount", 0));
        e.setRatingTotal(obj.optDouble("ratingTotal", 0.0));
        e.setSource(obj.optString("source", "local"));
        return e;
    }

    private Student docToStudent(DocumentSnapshot doc) {
        Student s = new Student();
        // Doc ID is studentId; fall back to the stored field for safety
        s.setStudentId(doc.getString("studentId") != null ? doc.getString("studentId") : doc.getId());
        s.setName(doc.getString("name"));
        s.setEmail(doc.getString("email"));
        s.setPassword(doc.getString("password"));
        s.setRole(doc.getString("role") != null ? doc.getString("role") : "student");
        s.setAdminCode(doc.getString("adminCode"));
        return s;
    }

    public boolean isFirebaseAvailable() {
        return firebaseAvailable;
    }

    // =========================================================================
    // Slug helpers (for human-readable Firestore document IDs on local events)
    // =========================================================================

    /**
     * Converts an event title into a URL-safe slug.
     * Example: "Gaming Night!" → "gaming-night"
     *          "5-a-Side Football" → "5-a-side-football"
     *
     * Public and static so SkiddleService and TicketmasterService can use it
     * when building their prefixed document IDs (e.g. "skiddle-gaming-night").
     */
    public static String slugify(String title) {
        if (title == null || title.isBlank()) return "event";
        return title.trim()
                    .toLowerCase()
                    .replaceAll("[^a-z0-9\\s-]", "")   // remove special chars except hyphens
                    .replaceAll("\\s+", "-")             // spaces → hyphens
                    .replaceAll("-{2,}", "-")             // collapse multiple hyphens
                    .replaceAll("^-|-$", "");             // trim leading/trailing hyphens
    }

    /**
     * Generates a slug that is guaranteed to be unique in the Firestore events collection.
     * If "gaming-night" already exists, tries "gaming-night-2", "gaming-night-3", etc.
     */
    private String generateUniqueSlug(String title) throws InterruptedException, ExecutionException {
        String base = slugify(title);
        String candidate = base;
        int counter = 2;
        while (true) {
            DocumentSnapshot existing = db.collection("events").document(candidate).get().get();
            if (!existing.exists()) return candidate;
            candidate = base + "-" + counter++;
        }
    }
}
