package dev.curatorium.essentials.data;

import java.util.HashMap;
import java.util.Map;
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
    }
}
