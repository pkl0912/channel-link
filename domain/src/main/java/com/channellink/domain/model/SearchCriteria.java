package com.channellink.domain.model;

import java.time.LocalDate;

/**
 * 공통 규약
 */
public record SearchCriteria(LocalDate checkIn, LocalDate checkOut, int adults, int children) {

    public SearchCriteria {
        if (checkIn == null || checkOut == null) {
            throw new IllegalArgumentException("checkIn/checkOut must not be null");
        }
        if (!checkOut.isAfter(checkIn)) {
            throw new IllegalArgumentException("checkOut must be after checkIn");
        }
        if (adults < 1) {
            throw new IllegalArgumentException("adults must be at least 1");
        }
        if (children < 0) {
            throw new IllegalArgumentException("children must not be negative");
        }
    }

    public long nights() {
        return checkOut.toEpochDay() - checkIn.toEpochDay();
    }
}
