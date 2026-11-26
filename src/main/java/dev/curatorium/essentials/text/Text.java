package dev.curatorium.essentials.text;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * Turns EssentialsX-style formatted text into chat components: {@code &0-&9 &a-&f} colours,
 * {@code &k-&o} formats, {@code &r} reset and {@code &#RRGGBB} hex colours. Placeholders
 * {@code {0}}, {@code {1}}, … are filled from {@code args}; arguments are inserted as-is (never
 * parsed for codes), so player-written text can't inject formatting.
 */
public final class Text {
    public static MutableComponent format(String template, Object... args) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        int n = template.length();
        for (int i = 0; i < n; i++) {
            char c = template.charAt(i);
            if (c == '&' && i + 1 < n) {
                char code = template.charAt(i + 1);
                if (code == '#' && i + 8 <= n && isHex(template, i + 2, 6)) {
                    flush(out, run, style);
                    style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(template.substring(i + 2, i + 8), 16)));
                    i += 7;
                    continue;
                }
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(code));
                if (fmt != null) {
                    flush(out, run, style);
                    if (fmt == ChatFormatting.RESET) style = Style.EMPTY;
                    else if (fmt.isColor()) style = Style.EMPTY.withColor(fmt); // a colour resets formats, as in vanilla
                    else style = style.applyFormat(fmt);
                    i++;
                    continue;
                }
            }
            if (c == '{' && args.length > 0) {
                int close = template.indexOf('}', i);
                if (close > i + 1 && close - i <= 3) {
                    try {
                        int index = Integer.parseInt(template.substring(i + 1, close));
                        if (index >= 0 && index < args.length) {
                            flush(out, run, style);
                            out.append(arg(args[index], style));
                            i = close;
                            continue;
                        }
                    } catch (NumberFormatException ignored) {
                        // not a placeholder; keep the text
                    }
                }
            }
            run.append(c);
        }
        flush(out, run, style);
        return out;
    }

    /** Strips formatting codes, for logs and Discord. */
    public static String plain(String template) {
        return template.replaceAll("&#[0-9a-fA-F]{6}|&[0-9a-fA-Fk-oK-OrR]", "");
    }

    private static Component arg(Object value, Style style) {
        if (value instanceof Component c) return Component.empty().withStyle(style).append(c);
        return Component.literal(String.valueOf(value)).withStyle(style);
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style) {
        if (run.isEmpty()) return;
        out.append(Component.literal(run.toString()).withStyle(style));
        run.setLength(0);
    }

    private static boolean isHex(String s, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private Text() {}
}
