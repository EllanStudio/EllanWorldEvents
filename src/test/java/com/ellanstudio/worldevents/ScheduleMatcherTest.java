package com.ellanstudio.worldevents;

import org.junit.jupiter.api.Test;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleMatcherTest {
    @Test
    void matchesConfiguredMinuteAndDay() {
        LocalDateTime saturday = LocalDateTime.of(2026, 9, 5, 22, 30);
        assertTrue(ScheduleMatcher.matches(saturday, Set.of(DayOfWeek.SATURDAY), LocalTime.of(22, 30)));
        assertFalse(ScheduleMatcher.matches(saturday, Set.of(DayOfWeek.FRIDAY), LocalTime.of(22, 30)));
        assertFalse(ScheduleMatcher.matches(saturday, Set.of(DayOfWeek.SATURDAY), LocalTime.of(18, 30)));
    }
}
