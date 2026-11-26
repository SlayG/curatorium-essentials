package dev.curatorium.essentials.economy;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Showing and reading amounts of money. Amounts are in spurs, Numismatics' smallest coin;
 * the coin values match Numismatics' own (checked against 1.1.0).
 */
public final class Money {
    /** Coins from largest to smallest: name, plural, value in spurs. */
    private static final String[][] COINS = {
            {"sun", "suns"}, {"crown", "crowns"}, {"cog", "cogs"},
            {"sprocket", "sprockets"}, {"bevel", "bevels"}, {"spur", "spurs"}};
    private static final long[] VALUES = {4096, 512, 64, 16, 8, 1};

    /** "3 crowns, 2 cogs (1,664 spurs)"; "nothing" for 0. */
    public static String format(long spurs) {
        if (spurs <= 0) return "nothing";
        String coins = coins(spurs);
        // Under a bevel it's all spurs already; don't repeat the amount.
        if (spurs < 8) return coins;
        return coins + " (" + NumberFormat.getIntegerInstance(Locale.ROOT).format(spurs) + " spurs)";
    }

    /** "3 crowns, 2 cogs": the fewest coins that make up the amount. */
    public static String coins(long spurs) {
        List<String> parts = new ArrayList<>();
        long left = spurs;
        for (int i = 0; i < VALUES.length; i++) {
            long n = left / VALUES[i];
            if (n == 0) continue;
            left -= n * VALUES[i];
            parts.add(n + " " + (n == 1 ? COINS[i][0] : COINS[i][1]));
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    /**
     * Reads an amount typed as spurs ({@code 1664}) or as coins ({@code 3crown 2cog},
     * {@code 3crowns,2cogs}, {@code 1sun}). Empty if it isn't a valid positive amount.
     */
    public static OptionalLong parse(String input) {
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", " ").replace("_", "");
        if (s.isEmpty()) return OptionalLong.empty();
        try {
            if (s.chars().allMatch(Character::isDigit)) {
                long v = Long.parseLong(s);
                return v > 0 ? OptionalLong.of(v) : OptionalLong.empty();
            }
            long total = 0;
            for (String token : s.split("\\s+")) {
                int split = 0;
                while (split < token.length() && Character.isDigit(token.charAt(split))) split++;
                if (split == 0 || split == token.length()) return OptionalLong.empty();
                long count = Long.parseLong(token.substring(0, split));
                long value = valueOf(token.substring(split));
                if (value == 0) return OptionalLong.empty();
                total = Math.addExact(total, Math.multiplyExact(count, value));
            }
            return total > 0 ? OptionalLong.of(total) : OptionalLong.empty();
        } catch (NumberFormatException | ArithmeticException e) {
            return OptionalLong.empty();
        }
    }

    private static long valueOf(String coin) {
        for (int i = 0; i < COINS.length; i++) {
            if (coin.equals(COINS[i][0]) || coin.equals(COINS[i][1])) return VALUES[i];
        }
        return 0;
    }

    private Money() {}
}
