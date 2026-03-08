package com.ntu.eventbooking.models;

/**
 * Represents a registered student (or admin) in the system.
 *
 * Fields added for auth:
 *   password  — BCrypt-hashed password (never returned in API responses)
 *   role      — "student" or "admin"
 *   adminCode — required for admin login (checked against stored value)
 */
public class Student {

    private String studentId;  // e.g. "N0123456"
    private String name;
    private String email;      // unique identifier / document key in Firestore
    private String password;   // BCrypt hash — never stored or returned in plaintext
    private String role;       // "student" or "admin"
    private String adminCode;  // admin accounts only — checked at login

    public Student() {}

    public Student(String studentId, String name, String email) {
        this.studentId = studentId;
        this.name = name;
        this.email = email;
        this.role = "student";
    }

    public String getStudentId() { return studentId; }
    public void setStudentId(String studentId) { this.studentId = studentId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getAdminCode() { return adminCode; }
    public void setAdminCode(String adminCode) { this.adminCode = adminCode; }

    @Override
    public String toString() {
        return "Student{studentId='" + studentId + "', name='" + name
                + "', email='" + email + "', role='" + role + "'}";
    }
}
