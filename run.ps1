# One-click launch script for Student Event Booking System
# Usage: .\run.ps1
#
# Requirements:
#   - Docker Desktop must be running
#   - .env file must exist in this folder (already included)
#   - serviceAccountKey.json (Firebase credentials) must be at the path below
#   - Docker image must be built first: docker build -t student-event-booking .

docker run -p 8080:8080 `
  --dns 8.8.8.8 --dns 8.8.4.4 `
  --env-file .env `
  -v "A:/Projects/VS - Code/Building/Student-Event-Booking/student-event-booking-system-firebase-adminsdk-fbsvc-f2fdc3f26a.json:/app/serviceAccountKey.json" `
  student-event-booking
