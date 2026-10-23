package com.myhomelibcorp.application.navigation;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
class UpdatePeriodRangeTest {
    private final LocalDate today = LocalDate.of(2026, 10, 20);
    @Test void dayWeekAndMonthRangesAreInclusive() {
        assertThat(UpdatePeriodRange.parse("updates-day-1", today).from()).isEqualTo(today);
        assertThat(UpdatePeriodRange.parse("updates-day-6", today).from()).isEqualTo(today.minusDays(5));
        assertThat(UpdatePeriodRange.parse("updates-week-2", today).from()).isEqualTo(today.minusWeeks(2).plusDays(1));
        assertThat(UpdatePeriodRange.parse("updates-month-12", today).from()).isEqualTo(today.minusMonths(12).plusDays(1));
    }
    @Test void selectedCalendarMonthUsesExactMonthBoundsAcrossYears() {
        assertThat(UpdatePeriodRange.parse("updates-calendar-202610", today))
                .isEqualTo(new UpdatePeriodRange(LocalDate.of(2026,10,1), LocalDate.of(2026,10,31)));
        assertThat(UpdatePeriodRange.parse("updates-calendar-202402", today))
                .isEqualTo(new UpdatePeriodRange(LocalDate.of(2024,2,1), LocalDate.of(2024,2,29)));
        assertThat(UpdatePeriodRange.parse("updates-calendar-202512", LocalDate.of(2026,1,2)))
                .isEqualTo(new UpdatePeriodRange(LocalDate.of(2025,12,1), LocalDate.of(2025,12,31)));
    }
    @Test void calendarMonthValidationDoesNotPermitFutureOrMalformedMonths() {
        for (String id : new String[]{
                "updates-calendar-202611", "updates-calendar-202613",
                "updates-calendar-202600", "updates-calendar-189912",
                "updates-calendar-nope", "updates-calendar-2147483648"
        }) assertThat(UpdatePeriodRange.parse(id, today)).as(id).isNull();
    }
    @Test void yearSelectionUsesCalendarBounds() {
        assertThat(UpdatePeriodRange.parse("updates-year-2024", today))
                .isEqualTo(new UpdatePeriodRange(LocalDate.of(2024,1,1),LocalDate.of(2024,12,31)));
    }
    @Test void datesCrossYearAndLeapDay() {
        assertThat(UpdatePeriodRange.parse("updates-week-1", LocalDate.of(2026,1,2)).from()).isEqualTo(LocalDate.of(2025,12,27));
        assertThat(UpdatePeriodRange.parse("updates-day-6", LocalDate.of(2024,2,29)).from()).isEqualTo(LocalDate.of(2024,2,24));
    }
    @Test void invalidPeriodsAreRejected() {
        for (String id : new String[]{"updates","updates-header-day","updates-day-7","updates-week-0","updates-month-13","updates-year-2027","updates-year-nope"})
            assertThat(UpdatePeriodRange.parse(id,today)).as(id).isNull();
    }
}
