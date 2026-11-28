package dev.curatorium.essentials.discord;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class DiscordEmojisTest {
    @AfterEach
    void reset() {
        DiscordEmojis.set(List.of());
    }

    @Test
    void turnsKnownNamesIntoDiscordEmojis() {
        DiscordEmojis.set(List.of(new DiscordEmojis.Emote("pog_cat", 123, false), new DiscordEmojis.Emote("catjam", 456, true)));
        assertEquals("hi <:pog_cat:123> and <a:catjam:456>", DiscordEmojis.toDiscord("hi :pog_cat: and :CATJAM:"));
    }

    @Test
    void escapesMarkdownAroundThemButNotInside() {
        DiscordEmojis.set(List.of(new DiscordEmojis.Emote("pog_cat", 123, false)));
        assertEquals("\\*bold\\* <:pog_cat:123> \\_x\\_ :nope:", DiscordEmojis.toDiscord("*bold* :pog_cat: _x_ :nope:"));
    }

    @Test
    void withoutEmojisItOnlyEscapes() {
        assertEquals("\\*a\\* :pog_cat:", DiscordEmojis.toDiscord("*a* :pog_cat:"));
    }
}
