package com.ntu.eventbooking.auth;

import javax.ws.rs.NameBinding;
import java.lang.annotation.*;

/**
 * Name-binding annotation used to mark JAX-RS endpoints that require authentication.
 *
 * Usage:
 *   @Secured("STUDENT")         — any authenticated student or admin
 *   @Secured("ADMIN")           — admin only
 *   @Secured("OWN_OR_ADMIN")    — owner of the resource OR admin
 *
 * Applied to both the endpoint method AND the AuthFilter class so JAX-RS
 * knows to invoke the filter only on annotated endpoints.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Secured {
    /** The required role: "STUDENT", "ADMIN", or "OWN_OR_ADMIN" */
    String value() default "STUDENT";
}
