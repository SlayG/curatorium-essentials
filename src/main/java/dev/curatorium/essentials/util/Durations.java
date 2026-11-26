package dev.curatorium.essentials.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reading and showing lengths of time, EssentialsX style ({@code 1d2h30m}, "1 day, 2 hours"). */
public final class Durations {
    private static final Pattern PART = Pattern.compile("(\\d+)\\s*(mo|y|w|d|h|m|s)");
    private static final long SECOND = 1000, MINUTE = 60 * SECOND, HOUR = 60 * MINUTE, DAY = 24 * HOUR;

    /** "1 day, 2 hours, 5 minutes"; seconds are shown only under an hour. "now" for 0 or less. */
    public static String format(long millis) {
        if (millis <= 0) return "now";
        long days = millis / DAY, hours = millis % DAY / HOUR, minutes = millis % HOUR / MINUTE;
        long seconds = (millis % MINUTE + SECOND - 1) / SECOND; // round up, so "0 seconds" never shows
        if (seconds == 60) {
            seconds = 0;
            minutes++;
        }
        List<String> parts = new ArrayList<>();
        add(parts, days, "day");
        add(parts, hours, "hour");
        add(parts, minutes, "minute");
        if (days == 0 && hours == 0) add(parts, seconds, "second");
        return parts.isEmpty() ? "1 second" : String.join(", ", parts);
    }

    /** Reads {@code 1y2mo3w4d5h6m7s} (any subset, any order) into milliseconds. */
    public static OptionalLong parse(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT);
        Matcher m = PART.matcher(s);
        long total = 0;
        int end = 0;
        while (m.find()) {
            if (m.start() != end) return OptionalLong.empty();
            end = m.end();
            long n = Long.parseLong(m.group(1));
            long unit = switch (m.group(2)) {
                case "y" -> 365 * DAY;
                case "mo" -> 30 * DAY;
                case "w" -> 7 * DAY;
                case "d" -> DAY;
                case "h" -> HOUR;
                case "m" -> MINUTE;
                default -> SECOND;
            };
            try {
                total = Math.addExact(total, Math.multiplyExact(n, unit));
            } catch (ArithmeticException e) {
                return OptionalLong.empty();
            }
        }
        return end == s.length() && total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
    }

    private static void add(List<String> parts, long n, String unit) {
        if (n > 0) parts.add(n + " " + unit + (n == 1 ? "" : "s"));
    }

    private Durations() {}
}
