package com.auditflow.gateway.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;

/**
 * A half-open window {@code [from, to)} from two optional query
 * parameters: {@code to} defaults to now, {@code from} to a default
 * length before it. Inverted or over-long windows are a 400 - the caller
 * gets told, rather than a query that scans a year or returns nothing.
 * The cap is {@link #MAX_LENGTH} for the per-day series (a year of
 * points is plenty); reports pass {@code null} because a multi-year
 * evidence request is legitimate and the event cap already bounds them.
 */
public record TimeWindow(Instant from, Instant to) {

    /** The one cap for windows that produce a per-day series. */
    public static final Duration MAX_LENGTH = Duration.ofDays(366);

    /**
     * @param maxLength the longest window accepted, or null for no cap
     */
    public static TimeWindow resolve(Instant from, Instant to, Duration defaultLength, Duration maxLength) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(defaultLength);
        if (!start.isBefore(end)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "from must be before to");
        }
        if (maxLength != null && Duration.between(start, end).compareTo(maxLength) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "window is longer than " + maxLength.toDays() + " days; narrow it");
        }
        return new TimeWindow(start, end);
    }

    public Duration length() {
        return Duration.between(from, to);
    }

    /** The window of the same length that ends where this one starts. */
    public TimeWindow previous() {
        return new TimeWindow(from.minus(length()), from);
    }
}
