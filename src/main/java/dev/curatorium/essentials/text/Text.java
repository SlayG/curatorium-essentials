package dev.curatorium.essentials.text;

import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
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
    private static final Pattern URL = Pattern.compile("(?i)\\b(https?://[^\\s]+|www\\.[^\\s]+\\.[^\\s]+)");
    private static final Pattern COLOR = Pattern.compile("(?i)&([0-9a-f]|#[0-9a-f]{6})");
    private static final Pattern FORMAT = Pattern.compile("(?i)&[l-or]");
    private static final Pattern MAGIC = Pattern.compile("(?i)&k");

    /** Formats admin-written text (messages, config, text files): every code is allowed. */
    public static MutableComponent format(String template, Object... args) {
        return parse(template, args, false);
    }

    /**
     * Formats text a player wrote. Codes they lack permission for are left as plain text;
     * links become clickable if {@code links} is on.
     */
    public static MutableComponent user(String raw, boolean color, boolean format, boolean magic, boolean links) {
        // Escape each code the player may not use as "&&", which shows a literal "&".
        StringBuilder s = new StringBuilder(raw.length() + 8);
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            if (c != '&' || i + 1 >= n) {
                s.append(c);
                continue;
            }
            char code = Character.toLowerCase(raw.charAt(i + 1));
            boolean allowed;
            if (code == '&') allowed = true; // already an escape: keep both
            else if (code == '#' || Character.digit(code, 16) >= 0) allowed = color;
            else if (code == 'k') allowed = magic;
            else if ((code >= 'l' && code <= 'o') || code == 'r') allowed = format;
            else allowed = true; // not a code at all
            s.append(allowed ? "&" : "&&");
            if (code == '&') {
                s.append('&');
                i++;
            }
        }
        return parse(s.toString(), new Object[0], links);
    }

    /** Removes the codes a player isn't allowed, for saved text such as nicknames. */
    public static String strip(String raw, boolean color, boolean format, boolean magic) {
        String s = raw;
        if (!color) s = COLOR.matcher(s).replaceAll("");
        if (!format) s = FORMAT.matcher(s).replaceAll("");
        if (!magic) s = MAGIC.matcher(s).replaceAll("");
        return s;
    }

    /** Strips formatting codes, for comparisons, logs and Discord. */
    public static String plain(String template) {
        return template.replaceAll("(?i)&#[0-9a-f]{6}|&[0-9a-fk-or]", "");
    }

    private static MutableComponent parse(String template, Object[] args, boolean links) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        StringBuilder run = new StringBuilder();
        int n = template.length();
        for (int i = 0; i < n; i++) {
            char c = template.charAt(i);
            if (c == '&' && i + 1 < n) {
                char code = template.charAt(i + 1);
                if (code == '&') { // "&&" is an escaped "&"
                    run.append('&');
                    i++;
                    continue;
                }
                if (code == '#' && i + 8 <= n && isHex(template, i + 2, 6)) {
                    flush(out, run, style, links);
                    style = Style.EMPTY.withColor(TextColor.fromRgb(Integer.parseInt(template.substring(i + 2, i + 8), 16)));
                    i += 7;
                    continue;
                }
                ChatFormatting fmt = ChatFormatting.getByCode(Character.toLowerCase(code));
                if (fmt != null) {
                    flush(out, run, style, links);
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
                            flush(out, run, style, links);
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
        flush(out, run, style, links);
        return out;
    }

    private static Component arg(Object value, Style style) {
        if (value instanceof Component c) return Component.empty().withStyle(style).append(c);
        return Component.literal(String.valueOf(value)).withStyle(style);
    }

    private static void flush(MutableComponent out, StringBuilder run, Style style, boolean links) {
        if (run.isEmpty()) return;
        String s = run.toString();
        run.setLength(0);
        if (!links) {
            out.append(Component.literal(s).withStyle(style));
            return;
        }
        Matcher m = URL.matcher(s);
        int last = 0;
        while (m.find()) {
            if (m.start() > last) out.append(Component.literal(s.substring(last, m.start())).withStyle(style));
            String url = m.group(1);
            String target = url.toLowerCase().startsWith("http") ? url : "https://" + url;
            Style linkStyle = style.withUnderlined(true);
            try {
                URI.create(target);
                linkStyle = linkStyle.withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, target));
            } catch (IllegalArgumentException ignored) {
                // not a valid link after all; show it underlined only
            }
            out.append(Component.literal(url).withStyle(linkStyle));
            last = m.end();
        }
        if (last < s.length()) out.append(Component.literal(s.substring(last)).withStyle(style));
    }

    private static boolean isHex(String s, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (Character.digit(s.charAt(i), 16) < 0) return false;
        }
        return true;
    }

    private Text() {}
}
