package com.mcpserver.reports;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Date parsing and relative-window resolution for RQL, ported from the report automation engine so
 * that a query written there behaves identically here.
 *
 * <p>Windows are inclusive at day granularity, weeks start on Monday, and a field with no declared
 * format falls back to common ISO-8601 shapes. A value that cannot be parsed never matches a date
 * rule — a date filter must not silently keep rows it could not evaluate.
 */
public final class RqlDates {

    /** Relative windows a query can name. */
    public static final Set<String> PRESETS = Set.of(
            "TODAY", "YESTERDAY", "THIS_WEEK", "LAST_WEEK", "THIS_MONTH", "LAST_MONTH",
            "THIS_QUARTER", "LAST_QUARTER", "THIS_YEAR", "LAST_YEAR");

    private static final List<DateTimeFormatter> ISO_FALLBACKS = List.of(
            DateTimeFormatter.ISO_INSTANT,
            DateTimeFormatter.ISO_OFFSET_DATE_TIME,
            DateTimeFormatter.ISO_LOCAL_DATE_TIME,
            new DateTimeFormatterBuilder()
                    .appendPattern("yyyy-MM-dd HH:mm:ss")
                    .parseDefaulting(ChronoField.NANO_OF_SECOND, 0)
                    .toFormatter(),
            DateTimeFormatter.ISO_LOCAL_DATE);

    private RqlDates() {
    }

    /** How one field's values are read: an optional pattern and an optional zone. */
    public record FieldConfig(String format, String timezone) {

        public ZoneId zone() {
            if (timezone != null && !timezone.isBlank()) {
                try {
                    return ZoneId.of(timezone);
                } catch (Exception ignored) {
                    // An unusable zone falls back to the system default rather than failing the run.
                }
            }
            return ZoneId.systemDefault();
        }
    }

    /** An inclusive instant range. */
    public record Window(Instant from, Instant to) {

        public boolean contains(Instant instant) {
            return instant != null && !instant.isBefore(from) && !instant.isAfter(to);
        }
    }

    public static boolean isPreset(String name) {
        return name != null && PRESETS.contains(name.trim().toUpperCase(Locale.ROOT));
    }

    /** Resolves a preset name against {@code now}; returns null when the name is not a preset. */
    public static Window window(String preset, ZoneId zone, Instant now) {
        if (!isPreset(preset)) return null;
        LocalDate today = now.atZone(zone).toLocalDate();
        return switch (preset.trim().toUpperCase(Locale.ROOT)) {
            case "TODAY" -> day(today, zone);
            case "YESTERDAY" -> day(today.minusDays(1), zone);
            case "THIS_WEEK" -> week(today, 0, zone);
            case "LAST_WEEK" -> week(today, -1, zone);
            case "THIS_MONTH" -> month(today, 0, zone);
            case "LAST_MONTH" -> month(today, -1, zone);
            case "THIS_QUARTER" -> quarter(today, 0, zone);
            case "LAST_QUARTER" -> quarter(today, -1, zone);
            case "THIS_YEAR" -> year(today, 0, zone);
            default -> year(today, -1, zone);
        };
    }

    /** Parses a value into an instant using the configured pattern, then ISO fallbacks. */
    public static Instant parse(Object rawValue, FieldConfig config) {
        if (rawValue == null) return null;
        if (rawValue instanceof Instant instant) return instant;
        String raw = String.valueOf(rawValue).trim();
        if (raw.isEmpty()) return null;
        ZoneId zone = config == null ? ZoneId.systemDefault() : config.zone();
        if (config != null && config.format() != null && !config.format().isBlank()) {
            try {
                Instant parsed = parseWith(raw, DateTimeFormatter.ofPattern(config.format()), zone);
                if (parsed != null) return parsed;
            } catch (IllegalArgumentException ignored) {
                // An invalid pattern is not fatal: fall through to ISO detection.
            }
        }
        for (DateTimeFormatter formatter : ISO_FALLBACKS) {
            Instant parsed = parseWith(raw, formatter, zone);
            if (parsed != null) return parsed;
        }
        return null;
    }

    /** True when the value looks like a date rather than a plain number or word. */
    public static boolean looksLikeDate(Object value) {
        if (value == null) return false;
        String raw = String.valueOf(value).trim();
        return raw.matches("\\d{4}-\\d{2}-\\d{2}(?:[T ].*)?");
    }

    private static Instant parseWith(String raw, DateTimeFormatter formatter, ZoneId zone) {
        try {
            return ZonedDateTime.parse(raw, formatter).toInstant();
        } catch (DateTimeException ignored) {
            // Not a zoned value — try the narrower shapes below.
        }
        try {
            return Instant.from(formatter.parse(raw));
        } catch (DateTimeException ignored) {
            // Not an instant.
        }
        try {
            return LocalDateTime.parse(raw, formatter).atZone(zone).toInstant();
        } catch (DateTimeException ignored) {
            // Not a local date-time.
        }
        try {
            return LocalDate.parse(raw, formatter).atStartOfDay(zone).toInstant();
        } catch (DateTimeException ignored) {
            return null;
        }
    }

    private static Window day(LocalDate date, ZoneId zone) {
        return new Window(date.atStartOfDay(zone).toInstant(),
                date.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static Window week(LocalDate today, int offset, ZoneId zone) {
        LocalDate start = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(offset);
        return new Window(start.atStartOfDay(zone).toInstant(),
                start.plusDays(7).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static Window month(LocalDate today, int offset, ZoneId zone) {
        LocalDate base = today.plusMonths(offset);
        LocalDate start = base.with(TemporalAdjusters.firstDayOfMonth());
        LocalDate end = base.with(TemporalAdjusters.lastDayOfMonth());
        return new Window(start.atStartOfDay(zone).toInstant(),
                end.plusDays(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static Window quarter(LocalDate today, int offset, ZoneId zone) {
        int target = today.get(IsoFields.QUARTER_OF_YEAR) + offset;
        int yearAdjust = 0;
        if (target < 1) {
            target += 4;
            yearAdjust = -1;
        }
        if (target > 4) {
            target -= 4;
            yearAdjust = 1;
        }
        LocalDate start = LocalDate.of(today.getYear() + yearAdjust, (target - 1) * 3 + 1, 1);
        return new Window(start.atStartOfDay(zone).toInstant(),
                start.plusMonths(3).atStartOfDay(zone).toInstant().minusNanos(1));
    }

    private static Window year(LocalDate today, int offset, ZoneId zone) {
        LocalDate start = LocalDate.of(today.getYear() + offset, 1, 1);
        return new Window(start.atStartOfDay(zone).toInstant(),
                start.plusYears(1).atStartOfDay(zone).toInstant().minusNanos(1));
    }
}
