package com.hrms.core.leave;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WorkingDaysTests {

    // 2026-11-23 is a Monday
    @ParameterizedTest(name = "{0} to {1} = {2}")
    @CsvSource({
            "2026-11-23, 2026-11-23, 1",   // a single Monday
            "2026-11-23, 2026-11-27, 5",   // Monday to Friday
            "2026-11-28, 2026-11-29, 0",   // just a weekend
            "2026-11-27, 2026-11-30, 2",   // Friday to Monday, across a weekend
            "2026-11-23, 2026-12-04, 10",  // two full weeks
    })
    void countsWeekdaysInclusive(LocalDate start, LocalDate end, int expected) {
        assertThat(WorkingDays.between(start, end)).isEqualTo(expected);
    }
}
