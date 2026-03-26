# Student Event Booking System

A full-stack Java EE REST API + single-page web app that lets students discover, register for, and rate campus and public events. It merges locally-created student events with live public events pulled from the **Skiddle** and **Ticketmaster** APIs, enriches every event with real-time weather from **OpenWeatherMap**, persists everything in **Firebase Firestore**, and protects write operations with **JWT authentication** and **BCrypt passwords**.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 11 |
| REST Framework | Jersey 2.x (JAX-RS) |
| Server | Apache Tomcat 9 (inside Docker) |
| Database | Firebase Firestore (NoSQL) |
| Authentication | JWT (JJWT 0.11.5) + BCrypt (jbcrypt 0.4) |
| External APIs | Skiddle, Ticketmaster Discovery, OpenWeatherMap |
| Build Tool | Apache Maven 3.9 |
| Containerisation | Docker (multi-stage build) |
| Frontend | Bootstrap 5.3.2 + Vanilla JS (single-page) |

---

## Features

| Feature | Details |
|---|---|
| Event discovery | Browse all events with search, filter by type/date/venue |
| External events | Live sync from Skiddle + Ticketmaster every 5 minutes |
| Live weather | OpenWeatherMap data shown per event |
| JWT auth | BCrypt password hashing, 24-hour tokens |
| Student dashboard | My Events tab: view registrations and posted events |
| Add/Edit/Delete events | Students manage own events; admins manage all |
| Venue autocomplete | Nominatim (OpenStreetMap) search — no API key required |
| Venue map link | Venue name is a clickable Google Maps link |
| Event time display | Shows formatted time or "TBD" for external events with no fixed time |
| Star ratings | Students rate attended events; live average shown |
| Admin panel | Analytics dashboard, student management, force sync |
| Admin user management | Delete individual/bulk students — removes from Firestore **and** Firebase Authentication |
| Admin login guard | Admin login form rejects non-admin accounts at both client + server |
| API resilience | Skiddle, Ticketmaster, and OpenWeatherMap failures are isolated — existing data always served |
| In-memory cache | 60-second TTL cache on `GET /api/events` with write-through invalidation |
| In-memory fallback | Full offline mode seeded from `events.json` when Firestore is unavailable |
| CI/CD | GitHub Actions → Google Cloud Run + Artifact Registry; Jenkinsfile included |
| Performance testing | JMeter test plan covering all major endpoints (results CSV included) |

---

## Project Structure

```
StudentEventBooking/
├── Dockerfile
├── Jenkinsfile                        # Jenkins CI/CD pipeline
├── run.ps1                            # One-click Docker launch
├── pom.xml
├── firebase.json                      # Firebase deployment config
├── firestore.indexes.json             # Custom Firestore indexes
├── firestore.rules                    # Firestore security rules
├── ISYS40061_FinalReport.docx         # Coursework report
├── jmeter/
│   ├── StudentEventBooking.jmx        # JMeter performance test plan
│   ├── results-summary.csv            # Load test results (25 concurrent users, 3,000 requests)
│   ├── jmeter-report.html             # Custom HTML performance report with charts
│   └── report/index.html              # Auto-generated JMeter dashboard
└── src/main/
    ├── java/com/ntu/eventbooking/
    │   ├── ApplicationConfig.java     # Jersey application configuration
    │   ├── auth/
    │   │   ├── Secured.java           # @NameBinding annotation for auth filter
    │   │   ├── JwtUtil.java           # JWT generation + validation (JJWT)
    │   │   └── AuthFilter.java        # ContainerRequestFilter — enforces @Secured
    │   ├── cache/
    │   │   └── EventCache.java        # 60-second TTL in-memory cache
    │   ├── models/
    │   │   ├── Event.java             # Event POJO
    │   │   ├── Student.java           # Student POJO (+ password, role, adminCode)
    │   │   └── Rating.java            # Rating request POJO
    │   ├── resources/
    │   │   ├── AuthResource.java      # REST: /api/auth  (login/logout/register)
    │   │   ├── EventResource.java     # REST: /api/events
    │   │   ├── AdminResource.java     # REST: /api/admin  (admin-only)
    │   │   ├── StudentResource.java   # REST: /api/students
    │   │   ├── StatusResource.java    # REST: /api/status (Firebase health check)
    │   │   ├── CorsFilter.java        # CORS filter — allows all origins
    │   │   └── ResourceUtil.java      # Shared utility methods for resources
    │   ├── scheduler/
    │   │   └── EventSyncScheduler.java  # Polls Skiddle + TM every 5 min
    │   ├── services/
    │   │   ├── FirebaseService.java   # Firestore CRUD + in-memory fallback
    │   │   ├── SkiddleService.java    # Skiddle API integration
    │   │   ├── TicketmasterService.java # Ticketmaster API integration
    │   │   └── WeatherService.java    # OpenWeatherMap integration
    │   └── startup/
    │       └── AppStartupListener.java  # Seeds admin + starts scheduler on deploy
    ├── resources/
    │   ├── events.json                # Seed data (fallback when Firebase is offline)
    │   └── serviceAccountKey.json     # Firebase service account credentials
    └── webapp/
        ├── index.html                 # SPA: login page + student/admin dashboards
        └── WEB-INF/web.xml
```

---

## Quick Start

### Prerequisites
- Docker Desktop
- A Firebase project with Firestore enabled (free tier is fine)
- API keys for Skiddle, Ticketmaster, and OpenWeatherMap (all free tiers)

### 1. Configure environment

Copy `.env.example` to `.env` and fill in your keys:

```
FIREBASE_CREDENTIALS=/app/serviceAccountKey.json
SKIDDLE_API_KEY=your_skiddle_key
TICKETMASTER_API_KEY=your_ticketmaster_key
OPENWEATHER_API_KEY=your_openweather_key
JWT_SECRET=your-32-character-minimum-secret-key
```

> If `JWT_SECRET` is omitted, a hardcoded default is used (fine for development).

### 2. Run

```powershell
.\run.ps1
```

The app starts at **http://localhost:8080** with the frontend at **http://localhost:8080/student-event-booking-1.0-SNAPSHOT/**.

### Default admin credentials

| Field | Value |
|---|---|
| Email | `admin@ntu.ac.uk` |
| Password | `admin123` |
| Admin Code | `NTU-ADMIN-2026` |

---

## Authentication

The API uses **JWT Bearer tokens** for protected endpoints. On login, the server returns a token that must be included in the `Authorization` header:

```
Authorization: Bearer <token>
```

Tokens are valid for **24 hours** and contain claims: `sub` (email), `studentId`, `name`, `role`.

**Roles:**
- `student` — can create events, register/cancel for events, submit ratings
- `admin` — all student permissions + edit/delete any event, view all students, analytics, force sync

---

## API Reference

All endpoints are under the base path `/api`.

### Auth — `/api/auth`

#### POST /api/auth/register — Create student account

No authentication required.

**Request:**
```json
{ "studentId": "N0123456", "name": "Sidd Patel", "email": "N0123456@ntu.ac.uk", "password": "pass123" }
```

**Response 201:**
```json
{ "status": "registered", "studentId": "N0123456", "name": "Sidd Patel", "email": "n0123456@ntu.ac.uk", "role": "student" }
```

---

#### POST /api/auth/login — Login (student or admin)

No authentication required.

**Student request:**
```json
{ "email": "N0123456@ntu.ac.uk", "password": "pass123" }
```

**Admin request:**
```json
{ "email": "admin@ntu.ac.uk", "password": "admin123", "adminCode": "NTU-ADMIN-2026" }
```

**Response 200:**
```json
{ "token": "eyJhbGc...", "role": "student", "name": "Sidd Patel", "studentId": "N0123456", "email": "n0123456@ntu.ac.uk" }
```

**Errors:** 400 (missing fields), 401 (bad credentials / bad admin code)

---

#### POST /api/auth/logout — Invalidate session

```
Authorization: Bearer <token>
```

**Response 200:**
```json
{ "message": "Logged out successfully" }
```

---

### Events — `/api/events`

#### GET /api/events — List all events (public)

No authentication required. Supports optional query parameters:

| Parameter | Example | Description |
|---|---|---|
| `type` | `sport` | Filter by event type (case-insensitive) |
| `venue` | `Nottingham` | Partial venue name match |
| `date` | `2026-04-15` | Exact date in YYYY-MM-DD format |

```bash
curl http://localhost:8080/api/events
curl "http://localhost:8080/api/events?type=sport&date=2026-04-15"
```

**Response 200:** JSON array of event objects including live weather data.

---

#### POST /api/events — Create a new event

```
Authorization: Bearer <token>   (role: student or admin)
```

**Request:**
```json
{
  "title": "Gaming Night",
  "type": "gaming",
  "date": "2026-04-15",
  "time": "19:00",
  "venue": "NTU Clifton Campus",
  "cost": 0,
  "maxParticipants": 20,
  "latitude": 52.916,
  "longitude": -1.177
}
```

Note: `publisherID` is automatically set from the JWT token on the server side. `time` is optional (HH:mm format). Coordinates default to Nottingham city centre if omitted.

**Response 201:** Created event object with auto-generated `eventId`.

---

#### POST /api/events/{eventId}/register — Register for an event (public)

No authentication required.

**Request:**
```json
{ "studentId": "N0123456" }
```

**Response 200:**
```json
{ "status": "registered", "eventId": "...", "studentId": "N0123456", "currentAttendees": 5, "maxParticipants": 20, "spotsRemaining": 15 }
```

**Errors:** 404 (event not found), 409 (event full or already registered)

---

#### POST /api/events/{eventId}/cancel — Cancel a registration (public)

No authentication required.

**Request:**
```json
{ "studentId": "N0123456" }
```

**Response 200:**
```json
{ "status": "cancelled", "eventId": "...", "studentId": "N0123456", "currentAttendees": 4 }
```

**Errors:** 404 (event not found or student not registered)

---

#### POST /api/events/{eventId}/rate — Rate an event (public)

No authentication required.

**Request:**
```json
{ "studentId": "N0123456", "stars": 4 }
```

**Response 200:**
```json
{ "status": "rated", "eventId": "...", "yourRating": 4, "averageRating": 3.75, "totalRatings": 8 }
```

---

#### POST /api/events/{eventId}/edit — Edit an event

```
Authorization: Bearer <token>   (role: student — own events only; admin — any event)
```

**Request:** Partial update — only provided fields are changed.
```json
{ "title": "New Title", "date": "2026-05-01", "maxParticipants": 30 }
```

**Response 200:** Updated event object.

**Errors:** 403 (trying to edit someone else's event), 404 (event not found)

---

#### DELETE /api/events/{eventId} — Delete an event

```
Authorization: Bearer <token>   (role: student — own events only; admin — any event)
```

**Response 200:**
```json
{ "status": "deleted", "eventId": "..." }
```

---

#### DELETE /api/events/{eventId}/registrations/{studentId} — Remove a booking

```
Authorization: Bearer <token>   (role: admin only)
```

**Response 200:**
```json
{ "status": "removed", "eventId": "...", "studentId": "N0123456", "currentAttendees": 4 }
```

---

#### GET /api/events/{eventId}/registrations — List registrations for an event

```
Authorization: Bearer <token>   (role: admin only)
```

**Response 200:**
```json
{ "eventId": "...", "registrations": ["N0123456", "N0789012"], "count": 2 }
```

---

### Students — `/api/students`

#### GET /api/students/{studentId}/registrations — Get a student's registered events (public)

No authentication required.

**Response 200:** JSON array of events the student is registered for, including cancellation and rating status per event.

---

### Admin — `/api/admin`

All admin endpoints require `Authorization: Bearer <token>` with role `admin`.

#### GET /api/admin/students — List all students

**Response 200:** JSON array of student objects (password excluded).

```json
[
  { "studentId": "N0123456", "name": "Sidd Patel", "email": "n0123456@ntu.ac.uk", "role": "student" },
  { "studentId": "ADMIN001", "name": "Admin",      "email": "admin@ntu.ac.uk",     "role": "admin" }
]
```

---

#### DELETE /api/admin/students/{studentId} — Delete a student

**Response 200:**
```json
{ "deleted": "N0123456" }
```

**Errors:** 403 (cannot delete admin accounts), 404 (student not found)

---

#### DELETE /api/admin/students/bulk — Delete multiple students

**Request:**
```json
{ "studentIds": ["N0123456", "N0789012"] }
```

**Response 200:**
```json
{ "deleted": 2 }
```

Admin accounts in the list are silently skipped.

---

#### GET /api/admin/analytics — System analytics

**Response 200:**
```json
{
  "totalEvents": 42,
  "totalStudents": 15,
  "totalRegistrations": 127,
  "totalRevenue": 340.00,
  "overallAvgRating": 3.87,
  "eventsByType": { "sport": 12, "gaming": 8, "social": 10, "concert": 5, "study": 4, "other": 3 },
  "topEvents": [
    { "eventId": "...", "title": "Gaming Night", "currentAttendees": 19, "maxParticipants": 20, "type": "gaming" }
  ],
  "firebaseAvailable": true
}
```

---

#### POST /api/admin/sync — Force external event sync

Immediately fetches Skiddle + Ticketmaster events and persists them to Firestore. Normally runs automatically every 5 minutes.

**Response 200:**
```json
{ "status": "synced", "eventsImported": 15, "message": "External events synced successfully" }
```

---

### Status — `/api/status`

#### GET /api/status — Firebase health check (public)

No authentication required.

**Response 200:**
```json
{ "firebase": "connected", "fallback": false }
```

---

## Event Object Schema

```json
{
  "eventId":         "auto-generated string",
  "publisherID":     "N0123456",
  "title":           "Gaming Night",
  "type":            "gaming",
  "date":            "2026-04-15",
  "time":            "19:00",
  "venue":           "NTU Clifton Campus",
  "cost":            0.0,
  "maxParticipants": 20,
  "currentAttendees": 5,
  "spotsRemaining":  15,
  "latitude":        52.916,
  "longitude":       -1.177,
  "averageRating":   4.25,
  "ratingCount":     4,
  "ratingTotal":     17,
  "source":          "local",
  "weather":         "Clouds, 12°C"
}
```

`source` values: `"local"` (student-created), `"skiddle"`, `"ticketmaster"`.

`time` is optional — omitted if not set on the event.

`ratingTotal` stores the sum of all star values and is used internally to recalculate the exact average when new ratings are added.

---

## Architecture Notes

**Presubscribed workflow:** External events (Skiddle, Ticketmaster) are fetched by a `ScheduledExecutorService` every 5 minutes and stored in Firestore. `GET /api/events` never calls external APIs directly — it always reads from the database, making it fast and resilient to third-party outages.

**JWT auth flow:** Login returns a signed JWT. The client includes this token in every protected request. `AuthFilter` (a JAX-RS `@NameBinding` `ContainerRequestFilter`) validates the token and injects the caller's identity as request properties. Only endpoints annotated with `@Secured` trigger the filter — public endpoints like `GET /api/events` are unaffected.

**BCrypt passwords:** All passwords are hashed with BCrypt (cost factor 12) before storage. Plaintext passwords are never persisted or logged.

**In-memory fallback:** If Firestore is unavailable (no `FIREBASE_CREDENTIALS` env var), the API falls back to an in-memory store seeded from `events.json`. All functionality works offline for demonstration purposes.

**Caching:** `GET /api/events` (unfiltered) is cached in memory for 60 seconds. The cache is invalidated whenever an event is created, updated, deleted, or a registration is added or cancelled.

**CORS:** `CorsFilter` allows all origins (`*`) so the SPA can be served from any host during development.

**Performance testing:** A JMeter test plan (`jmeter/StudentEventBooking.jmx`) covers all major endpoints. `jmeter/results-summary.csv` contains results from a 25-concurrent-user load test (3,000 requests across 6 endpoints). A visual HTML report with charts is at `jmeter/jmeter-report.html` and the full JMeter dashboard at `jmeter/report/index.html`. Key finding: cached event list median = **5 ms** vs uncached average = **11,353 ms**, demonstrating the TTL cache effectiveness.

---

## CI/CD Pipeline

This project includes two CI/CD pipeline configurations to demonstrate both modern cloud-based and enterprise-grade DevOps workflows.

### 1. GitHub Actions (`.github/workflows/deploy.yml`)

Triggers automatically on every push to the `main` branch:

| Step | Action |
|---|---|
| Checkout | Pulls latest code |
| Java 11 setup | Configures JDK + Maven cache |
| Build | `mvn clean package -DskipTests` |
| Test | `mvn test` |
| Docker Build | Builds image from `Dockerfile` |
| Docker Push | Pushes image to Docker Hub |

**Required GitHub Secrets:**

Go to your repo → **Settings → Secrets and variables → Actions → New repository secret**

| Secret | Value |
|---|---|
| `GCP_PROJECT_ID` | Your GCP project ID (e.g. `my-project-123456`) |
| `GCP_SA_KEY` | Service account JSON key with roles: Cloud Run Admin, Storage Admin, Service Account User |

### 2. Jenkinsfile (Enterprise Demo)

A `Jenkinsfile` in the project root defines a declarative pipeline with the following stages:

| Stage | Description |
|---|---|
| **Checkout** | Pulls source from SCM |
| **Build** | `mvn clean package -DskipTests` |
| **Test** | `mvn test` + publishes JUnit XML reports |
| **Docker Build** | Builds and tags the Docker image |
| **Deploy** | Stops old container, runs new one with `.env` config |

Post-pipeline: prints success/failure message and cleans the workspace.

**Jenkins prerequisites:**
- Maven 3.9 tool configured in Jenkins (named `Maven 3.9`)
- Java 11 tool configured in Jenkins (named `Java 11`)
- Docker available on the Jenkins agent
- `.env` file present on the agent with API keys
# CI/CD pipeline active

---

## Contributors

| Name | GitHub | Role |
|---|---|---|
| Siddharth | [@Siddharth24R](https://github.com/Siddharth24R) | Developer |
