package com.hrms.core.leave;

import java.time.DayOfWeek;
import java.time.LocalDate;

/** Counts Monday-to-Friday days. Public holidays are a known gap (they vary by country). */
public final class WorkingDays {

    private WorkingDays() {}

    /** Working days from {@code start} to {@code end}, both inclusive. */
    public static int between(LocalDate start, LocalDate end) {
        return (int) start.datesUntil(end.plusDays(1))
                .filter(day -> day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY)
                .count();
    }
}
