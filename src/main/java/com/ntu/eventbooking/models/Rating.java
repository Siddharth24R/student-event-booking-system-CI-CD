package com.ntu.eventbooking.models;

/**
 * Represents a post-event rating submitted by a student.
 * Stars must be between 1 and 5 (validated in EventResource before processing).
 */
public class Rating {

    private String studentId;  // the student submitting the rating
    private int stars;         // 1 = poor, 5 = excellent

    public Rating() {}

    public Rating(String studentId, int stars) {
        this.studentId = studentId;
        this.stars = stars;
    }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public int getStars() { return stars; }
    public void setStars(int stars) { this.stars = stars; }
}
