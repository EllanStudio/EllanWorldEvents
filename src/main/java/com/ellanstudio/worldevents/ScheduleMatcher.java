package com.ellanstudio.worldevents;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;

final class ScheduleMatcher {
    private ScheduleMatcher() {
    }

    static boolean matches(LocalDateTime now, Collection<DayOfWeek> days, LocalTime time) {
        return days.contains(now.getDayOfWeek())
                && now.getHour() == time.getHour()
                && now.getMinute() == time.getMinute();
    }
}
