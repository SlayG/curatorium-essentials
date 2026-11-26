package dev.curatorium.essentials.data;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the mod remembers about one player, saved as
 * {@code <world>/curatorium_essentials/userdata/<uuid>.json}. Later phases add fields here
 * (homes, nick, mail, ignores, mute and jail state…); missing fields load as their defaults, so
 * older files keep working.
 */
public final class UserData {
    public UUID uuid;
    /** Name at last login. */
    public String lastName = "";
    /** Epoch millis of the first login, 0 if unknown. */
    public long firstJoin;
    /** Epoch millis of the last logout, 0 while never logged out. */
    public long lastSeen;
    /** Where the player was when they logged out. */
    public Location logoutLocation;
    /** Command name to epoch millis when its cooldown ends. */
    public Map<String, Long> cooldowns = new HashMap<>();

    /** Epoch millis of the current (or last) login. */
    public long lastLogin;
    /** IP address at last login. */
    public String lastIp = "";
    /** Nickname with its colour codes, or null for none. */
    public String nick;
    /** Players whose chat, messages and mail this player doesn't see. */
    public Set<UUID> ignored = new HashSet<>();
    public List<Mail> mail = new ArrayList<>();
    /** Sees everyone's private messages (needs essentials.socialspy too). */
    public boolean socialSpy;
    /** Doesn't receive private messages (/msgtoggle). */
    public boolean msgDisabled;
    /** /r goes to whoever this player last messaged, instead of whoever last messaged them (/rtoggle). */
    public boolean replyToSent;
    /** Every chat message shouts, with local chat on (/toggleshout). */
    public boolean shout;

    // ------------------------------------------------------------ moderation (Phase 2)
    /** Epoch millis when the mute ends; 0 = not muted, -1 = muted until unmuted. */
    public long muteUntil;
    public String muteReason;
    /** Name of the jail the player is in, or null. */
    public String jail;
    /** Real-time jail: epoch millis of release. Online-time jail: 0 (see {@link #jailLeft}). 0 with jailLeft 0 = no end. */
    public long jailUntil;
    /** Online-time jail: millis of online time still to serve; 0 = not counting online time. */
    public long jailLeft;
    public String jailReason;
    /** Where the player was when jailed; they go back there on release. */
    public Location preJailLocation;

    // ------------------------------------------------------------ player state (Phase 2)
    public boolean vanished;
    public boolean god;
    public boolean fly;
    /** Item id -> commands that item runs when used (/powertool). */
    public Map<String, List<String>> powertools = new HashMap<>();
    /** /powertooltoggle: power tools only run while this is on. */
    public boolean powertoolsEnabled = true;
    /** Item ids this player never runs out of when placing (/unlimited). */
    public Set<String> unlimited = new HashSet<>();
    /** /clearinventoryconfirmtoggle; null = the config default. */
    public Boolean clearConfirm;
    /** Kit name -> epoch millis of the last time it was taken. */
    public Map<String, Long> kitsUsed = new HashMap<>();

    // ------------------------------------------------------------ teleports (Phase 3)
    /** Home name (lower case) -> location. */
    public Map<String, Location> homes = new java.util.TreeMap<>();
    /** Where /back goes: the place before the last teleport, or where the player died. */
    public Location lastLocation;
    /** /tptoggle: others may teleport to this player or send requests (false blocks them). */
    public boolean teleportEnabled = true;
    /** /tpauto: accept teleport requests automatically. */
    public boolean tpAuto;

    // ------------------------------------------------------------ economy (Phase 4)
    /** Spurs paid to this player while offline, handed over at their next login. */
    public long escrow;
    /** Who sent the held coins, for the login message. */
    public List<String> escrowNotes = new ArrayList<>();
    /** /paytoggle: whether others may /pay this player. */
    public boolean acceptPayments = true;
    /** /payconfirmtoggle; null = the config default. */
    public Boolean payConfirm;

    public boolean isMuted(long now) {
        return muteUntil == -1 || muteUntil > now;
    }

    /** A message left with /mail. */
    public static final class Mail {
        /** Null when sent from the console. */
        public UUID sender;
        public String senderName = "";
        public long sent;
        /** Epoch millis when it disappears, 0 for never (/mail sendtemp). */
        public long expires;
        public String message = "";
        public boolean read;

        public boolean expired(long now) {
            return expires > 0 && now >= expires;
        }
    }

    /** Set when anything changes; the store saves dirty users. */
    transient boolean dirty;

    public void markDirty() {
        dirty = true;
    }

    /** Fills defaults Gson leaves null when a field is missing from an older file. */
    void fixNulls(UUID id) {
        if (uuid == null) uuid = id;
        if (lastName == null) lastName = "";
        if (cooldowns == null) cooldowns = new HashMap<>();
        if (lastIp == null) lastIp = "";
        if (ignored == null) ignored = new HashSet<>();
        if (mail == null) mail = new ArrayList<>();
        if (powertools == null) powertools = new HashMap<>();
        if (unlimited == null) unlimited = new HashSet<>();
        if (kitsUsed == null) kitsUsed = new HashMap<>();
        if (homes == null) homes = new java.util.TreeMap<>();
        if (escrowNotes == null) escrowNotes = new ArrayList<>();
        mail.removeIf(m -> m == null || m.message == null);
        long now = System.currentTimeMillis();
        mail.removeIf(m -> m.expired(now));
    }
}
