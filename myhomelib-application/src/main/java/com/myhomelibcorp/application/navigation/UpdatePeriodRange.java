package com.myhomelibcorp.application.navigation;
import java.time.LocalDate;
/** Inclusive interval for catalogue insertion dates. */
public record UpdatePeriodRange(LocalDate from, LocalDate to) {
    public static UpdatePeriodRange parse(String id, LocalDate today) {
        if (id == null || today == null || !id.startsWith("updates-")) return null;
        String[] parts = id.split("-");
        if (parts.length != 3) return null;
        int n;
        try { n = Integer.parseInt(parts[2]); } catch (NumberFormatException e) { return null; }
        // Calendar-month dropdown IDs encode YYYYMM and must not mean rolling months.
        if ("calendar".equals(parts[1])) {
            int year = n / 100;
            int month = n % 100;
            if (year < 1900 || year > today.getYear() || month < 1 || month > 12) return null;
            java.time.YearMonth selected = java.time.YearMonth.of(year, month);
            if (selected.isAfter(java.time.YearMonth.from(today))) return null;
            return new UpdatePeriodRange(selected.atDay(1), selected.atEndOfMonth());
        }
        return switch (parts[1]) {
            case "day" -> n >= 1 && n <= 6 ? new UpdatePeriodRange(today.minusDays(n - 1L), today) : null;
            case "week" -> n >= 1 && n <= 4 ? new UpdatePeriodRange(today.minusWeeks(n).plusDays(1), today) : null;
            case "month" -> n >= 1 && n <= 12 ? new UpdatePeriodRange(today.minusMonths(n).plusDays(1), today) : null;
            case "year" -> n >= 1900 && n <= today.getYear()
                ? new UpdatePeriodRange(LocalDate.of(n, 1, 1), LocalDate.of(n, 12, 31)) : null;
            default -> null;
        };
    }
}
