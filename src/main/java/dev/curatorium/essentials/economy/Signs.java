package dev.curatorium.essentials.economy;

import dev.curatorium.essentials.config.EssConfig;
import dev.curatorium.essentials.kits.Kits;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.teleport.Teleports;
import dev.curatorium.essentials.teleport.Warps;
import dev.curatorium.essentials.text.Messages;
import dev.curatorium.essentials.util.Items;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * EssentialsX sign shops on vanilla signs. The first line names the kind ({@code [Buy]},
 * {@code [Sell]}…); the last line is usually the price (spurs or coins, "free" or empty for
 * none). A sign becomes a working shop only when someone with essentials.signs.create.<kind>
 * writes it: its first line turns {@code economy.signColor} and it's waxed so it can't be
 * edited. Every shop is an admin shop: items come from nowhere and go nowhere.
 *
 * <pre>
 * [Buy]      amount / item / price          [Sell]     amount / item / price
 * [Trade]    "n item" you give / "n item" you get
 * [Kit]      kit / - / price                [Warp]     warp / - / price
 * [Heal]     - / - / price                  [Free]     item / amount
 * [Disposal]                                [Balance]
 * [Time]     day|night / - / price          [Weather]  sun|storm / - / price
 * [Enchant]  item|* / enchantment:level / price
 * [Repair]   hand|all / - / price           [Spawnmob] amount / mob / price
 * [Gamemode] mode / - / price
 * </pre>
 */
public final class Signs {
    /** What a sign does when used; returns true if it worked (then the price is charged). */
    @FunctionalInterface
    interface Use {
        boolean run(ServerPlayer p, String[] lines) throws SignError;
    }

    /** Checks a sign's lines when it's written; throws with the reason if they're wrong. */
    @FunctionalInterface
    interface Check {
        void run(String[] lines) throws SignError;
    }

    static final class SignError extends Exception {
        final Component message;

        SignError(String key, Object... args) {
            super(key, null, false, false);
            this.message = Messages.get(key, args);
        }
    }

    record Kind(String tag, Check check, Use use, int priceLine, int defaultUse) {}

    private static final Map<String, Kind> KINDS = new LinkedHashMap<>();

    private static void kind(String name, Check check, Use use, int priceLine, int defaultUse) {
        KINDS.put(name, new Kind("[" + Character.toUpperCase(name.charAt(0)) + name.substring(1) + "]", check, use, priceLine, defaultUse));
    }

    static {
        kind("buy", l -> { amount(l[1]); item(l[2]); }, (p, l) -> {
            Items.give(p, new ItemStack(item(l[2])), amount(l[1]));
            p.sendSystemMessage(Messages.get("sign.buy.done", amount(l[1]), new ItemStack(item(l[2])).getHoverName()));
            return true;
        }, 3, Perms.ALL);
        kind("sell", l -> { amount(l[1]); item(l[2]); }, null, 3, Perms.ALL); // handled specially: it pays out
        kind("trade", l -> { stack(l[1]); stack(l[2]); }, (p, l) -> {
            ItemStack give = stack(l[1]), get = stack(l[2]);
            if (count(p, give.getItem()) < give.getCount()) throw new SignError("sign.trade.notEnough", give.getCount(), give.getHoverName());
            remove(p, give.getItem(), give.getCount());
            Items.give(p, get.copyWithCount(1), get.getCount());
            p.sendSystemMessage(Messages.get("sign.trade.done", give.getCount(), give.getHoverName(), get.getCount(), get.getHoverName()));
            return true;
        }, -1, Perms.ALL);
        kind("kit", l -> { if (!Kits.exists(l[1].toLowerCase(Locale.ROOT))) throw new SignError("kit.unknown", l[1]); },
                (p, l) -> Kits.claim(p.createCommandSourceStack(), p, l[1].toLowerCase(Locale.ROOT)) > 0, 3, Perms.ALL);
        kind("warp", l -> { if (Warps.location(l[1].toLowerCase(Locale.ROOT)) == null) throw new SignError("warp.unknown", l[1]); },
                (p, l) -> {
                    var loc = Warps.location(l[1].toLowerCase(Locale.ROOT));
                    if (loc == null) throw new SignError("warp.unknown", l[1]);
                    return Teleports.go(p, loc, true, Messages.get("warp.arrived", l[1]));
                }, 3, Perms.OP); // teleports are op-only
        kind("heal", l -> { }, (p, l) -> {
            p.setHealth(p.getMaxHealth());
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(5f);
            p.clearFire();
            p.sendSystemMessage(Messages.get("heal.healed"));
            return true;
        }, 3, Perms.ALL);
        kind("free", l -> { item(l[1]); if (!l[2].isBlank()) amount(l[2]); }, (p, l) -> {
            Item item = item(l[1]);
            int n = l[2].isBlank() ? new ItemStack(item).getMaxStackSize() : amount(l[2]);
            Items.give(p, new ItemStack(item), n);
            p.sendSystemMessage(Messages.get("sign.free.done", n, new ItemStack(item).getHoverName()));
            return true;
        }, -1, Perms.ALL);
        kind("disposal", l -> { }, (p, l) -> {
            p.openMenu(new SimpleMenuProvider((id, inv, pl) -> new ChestMenu(MenuType.GENERIC_9x4, id, inv, new SimpleContainer(36), 4),
                    Messages.get("disposal.title")));
            return true;
        }, -1, Perms.ALL);
        kind("balance", l -> { }, (p, l) -> {
            p.sendSystemMessage(Messages.get("balance.self", Money.format(Wallet.balance(p))));
            return true;
        }, -1, Perms.ALL);
        kind("time", l -> { if (time(l[1]) < 0) throw new SignError("sign.time.bad"); }, (p, l) -> {
            long t = time(l[1]);
            for (ServerLevel level : p.server.getAllLevels()) {
                long now = level.getDayTime();
                long target = now - Math.floorMod(now, 24000L) + t;
                level.setDayTime(target < now ? target + 24000 : target);
            }
            p.server.getPlayerList().broadcastSystemMessage(Messages.get("sign.time.done", p.getDisplayName(), l[1].toLowerCase(Locale.ROOT)), false);
            return true;
        }, 3, Perms.ALL);
        kind("weather", l -> { if (weather(l[1]) == null) throw new SignError("sign.weather.bad"); }, (p, l) -> {
            boolean storm = weather(l[1]);
            ServerLevel level = p.server.overworld();
            if (storm) level.setWeatherParameters(0, 6000, true, false);
            else level.setWeatherParameters(6000, 0, false, false);
            p.server.getPlayerList().broadcastSystemMessage(Messages.get(storm ? "weather.rain" : "weather.clear"), false);
            return true;
        }, 3, Perms.ALL);
        kind("enchant", l -> { if (!l[1].equals("*")) item(l[1]); enchantmentSpec(l[2]); }, (p, l) -> {
            ItemStack held = p.getMainHandItem();
            if (held.isEmpty()) throw new SignError("item.holdSomething");
            if (!l[1].equals("*") && !held.is(item(l[1]))) throw new SignError("sign.enchant.wrongItem", new ItemStack(item(l[1])).getHoverName());
            String[] spec = l[2].split(":");
            Holder<Enchantment> e = enchantment(p, spec[0]);
            int level = Integer.parseInt(spec[1]);
            if (!e.value().canEnchant(held)) throw new SignError("enchant.cantApply", Enchantment.getFullname(e, level), held.getHoverName());
            EnchantmentHelper.updateEnchantments(held, m -> m.set(e, level));
            p.sendSystemMessage(Messages.get("enchant.done", Enchantment.getFullname(e, level), held.getHoverName()));
            return true;
        }, 3, Perms.ALL);
        kind("repair", l -> { String m = l[1].toLowerCase(Locale.ROOT); if (!m.isEmpty() && !m.equals("hand") && !m.equals("all")) throw new SignError("sign.repair.bad"); },
                (p, l) -> {
                    int n = 0;
                    if (l[1].equalsIgnoreCase("all")) {
                        for (int i = 0; i < p.getInventory().getContainerSize(); i++) n += repair(p.getInventory().getItem(i));
                    } else {
                        n = repair(p.getMainHandItem());
                    }
                    if (n == 0) throw new SignError("sign.repair.nothing");
                    p.sendSystemMessage(Messages.get("repair.all", n));
                    return true;
                }, 3, Perms.ALL);
        kind("spawnmob", l -> { amount(l[1]); entity(l[2]); }, (p, l) -> {
            EntityType<?> type = entity(l[2]);
            int n = Math.min(amount(l[1]), EssConfig.SPAWNMOB_LIMIT.get());
            for (int i = 0; i < n; i++) {
                Entity e = type.create(p.level());
                if (e == null) break;
                e.moveTo(p.getX(), p.getY(), p.getZ(), p.getRandom().nextFloat() * 360, 0);
                if (e instanceof Mob mob) mob.finalizeSpawn(p.serverLevel(), p.serverLevel().getCurrentDifficultyAt(p.blockPosition()), MobSpawnType.COMMAND, null);
                p.level().addFreshEntity(e);
            }
            return true;
        }, 3, Perms.OP);
        kind("gamemode", l -> { if (mode(l[1]) == null) throw new SignError("gamemode.unknown", l[1]); }, (p, l) -> {
            p.setGameMode(mode(l[1]));
            p.sendSystemMessage(Messages.get("gamemode.set", mode(l[1]).getLongDisplayName()));
            return true;
        }, 3, Perms.OP);
    }

    // ---------------------------------------------------------------- parsing sign lines

    static int amount(String s) throws SignError {
        try {
            int n = Integer.parseInt(s.trim());
            if (n < 1 || n > 64 * 36) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw new SignError("sign.badAmount", s);
        }
    }

    static Item item(String s) throws SignError {
        ResourceLocation id = ResourceLocation.tryParse(s.trim().toLowerCase(Locale.ROOT).replace(' ', '_'));
        Item item = id == null ? null : BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        if (item == null || item == net.minecraft.world.item.Items.AIR) throw new SignError("sign.badItem", s);
        if (Wallet.isCoin(new ItemStack(item))) throw new SignError("sign.coin");
        return item;
    }

    /** "16 cobblestone" or "cobblestone" (one). */
    static ItemStack stack(String s) throws SignError {
        String t = s.trim();
        int space = t.indexOf(' ');
        int n = 1;
        if (space > 0 && t.substring(0, space).chars().allMatch(Character::isDigit)) {
            n = amount(t.substring(0, space));
            t = t.substring(space + 1);
        }
        return new ItemStack(item(t), n);
    }

    static EntityType<?> entity(String s) throws SignError {
        ResourceLocation id = ResourceLocation.tryParse(s.trim().toLowerCase(Locale.ROOT));
        EntityType<?> type = id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
        if (type == null || !type.canSummon()) throw new SignError("sign.badMob", s);
        return type;
    }

    static long time(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "day" -> 0;
            case "night" -> 14000;
            default -> -1;
        };
    }

    static Boolean weather(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "sun", "clear" -> false;
            case "storm", "rain" -> true;
            default -> null;
        };
    }

    static GameType mode(String s) {
        return switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "survival", "s", "0" -> GameType.SURVIVAL;
            case "creative", "c", "1" -> GameType.CREATIVE;
            case "adventure", "a", "2" -> GameType.ADVENTURE;
            case "spectator", "sp", "3" -> GameType.SPECTATOR;
            default -> null;
        };
    }

    static void enchantmentSpec(String s) throws SignError {
        String[] parts = s.trim().split(":");
        if (parts.length < 2) throw new SignError("sign.enchant.bad");
        try {
            int lvl = Integer.parseInt(parts[parts.length - 1]);
            if (lvl < 1 || lvl > 255) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            throw new SignError("sign.enchant.bad");
        }
    }

    /** "sharpness:5" or "minecraft:sharpness:5". */
    static Holder<Enchantment> enchantment(ServerPlayer p, String s) throws SignError {
        ResourceLocation id = ResourceLocation.tryParse(s.contains(":") ? s : "minecraft:" + s);
        var reg = p.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
        var holder = id == null ? null : reg.getHolder(id).orElse(null);
        if (holder == null) throw new SignError("sign.enchant.bad");
        return holder;
    }

    static int repair(ItemStack s) {
        if (!s.isDamageableItem() || !s.isDamaged()) return 0;
        s.setDamageValue(0);
        return 1;
    }

    /** Spurs on the price line: empty or "free" is 0. Throws if it isn't an amount. */
    static long price(String s) throws SignError {
        String t = s.trim();
        if (t.isEmpty() || t.equalsIgnoreCase("free")) return 0;
        OptionalLong v = Money.parse(t);
        if (v.isEmpty()) throw new SignError("sign.badPrice", s);
        return v.getAsLong();
    }

    static int count(ServerPlayer p, Item item) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (s.is(item) && s.getComponentsPatch().isEmpty()) n += s.getCount();
        return n;
    }

    static void remove(ServerPlayer p, Item item, int amount) {
        Inventory inv = p.getInventory();
        for (ItemStack s : inv.items) {
            if (amount <= 0) break;
            if (!s.is(item) || !s.getComponentsPatch().isEmpty()) continue;
            int take = Math.min(amount, s.getCount());
            s.shrink(take);
            amount -= take;
        }
        inv.setChanged();
    }

    // ---------------------------------------------------------------- the sign itself

    static ChatFormatting marker() {
        String code = EssConfig.SIGN_COLOR.get().replace("&", "").trim();
        ChatFormatting f = code.isEmpty() ? null : ChatFormatting.getByCode(code.charAt(0));
        return f != null && f.isColor() ? f : ChatFormatting.DARK_BLUE;
    }

    static String[] lines(SignText text) {
        String[] out = new String[4];
        for (int i = 0; i < 4; i++) out[i] = text.getMessage(i, false).getString();
        return out;
    }

    /** The kind of a working shop sign (first line in the marker colour), or null. */
    static Kind kindOf(SignText text) {
        Component first = text.getMessage(0, false);
        TextColor colour = first.getStyle().getColor();
        if (colour == null || !colour.equals(TextColor.fromLegacyFormat(marker()))) return null;
        return byTag(first.getString());
    }

    static Kind byTag(String line) {
        String t = line.trim().toLowerCase(Locale.ROOT);
        if (!t.startsWith("[") || !t.endsWith("]")) return null;
        return KINDS.get(t.substring(1, t.length() - 1));
    }

    static String name(Kind k) {
        return k.tag().substring(1, k.tag().length() - 1).toLowerCase(Locale.ROOT);
    }

    /**
     * From the mixin, after a player wrote a sign. A valid shop line from someone allowed to
     * create that kind becomes a working shop (coloured and waxed). Anything else is left plain,
     * including a first line that kept the shop colour from before (vanilla keeps line styles).
     */
    public static void afterEdit(SignBlockEntity sign, Player player, boolean front) {
        if (!(player instanceof ServerPlayer p)) return;
        SignText text = sign.getText(front);
        String[] l = lines(text);
        Kind kind = byTag(l[0]);
        Component plainFirst = Component.literal(l[0]);
        if (kind == null) {
            if (text.getMessage(0, false).getStyle().getColor() != null) sign.updateText(t -> t.setMessage(0, plainFirst), front);
            return;
        }
        if (!Perms.has(p, "essentials.signs.create." + name(kind), Perms.OP)) {
            sign.updateText(t -> t.setMessage(0, plainFirst), front);
            p.sendSystemMessage(Messages.get("sign.noCreate", kind.tag()));
            return;
        }
        try {
            kind.check().run(l);
            if (kind.priceLine() >= 0) price(l[kind.priceLine()]);
        } catch (SignError e) {
            sign.updateText(t -> t.setMessage(0, plainFirst), front);
            p.sendSystemMessage(Messages.get("sign.invalid", kind.tag()).append(" ").append(e.message));
            return;
        }
        Component first = Component.literal(kind.tag()).withStyle(marker());
        sign.updateText(t -> t.setMessage(0, first), front);
        sign.setWaxed(true);
        sign.setChanged();
        p.level().sendBlockUpdated(sign.getBlockPos(), sign.getBlockState(), sign.getBlockState(), 3);
        p.sendSystemMessage(Messages.get("sign.created", kind.tag()));
    }

    public static void registerEvents(IEventBus bus) {
        bus.addListener(PlayerInteractEvent.RightClickBlock.class, e -> {
            if (e.isCanceled() || e.getHand() != InteractionHand.MAIN_HAND || !(e.getEntity() instanceof ServerPlayer p)) return;
            if (!(p.level().getBlockEntity(e.getPos()) instanceof SignBlockEntity sign)) return;
            SignText text = sign.getText(sign.isFacingFrontText(p));
            Kind kind = kindOf(text);
            if (kind == null) return;
            e.setCanceled(true);
            e.setCancellationResult(InteractionResult.SUCCESS);
            use(p, kind, lines(text));
        });
        bus.addListener(BlockEvent.BreakEvent.class, e -> {
            if (!(e.getLevel().getBlockEntity(e.getPos()) instanceof SignBlockEntity sign)) return;
            if (kindOf(sign.getFrontText()) == null && kindOf(sign.getBackText()) == null) return;
            if (e.getPlayer() == null || Perms.has((ServerPlayer) e.getPlayer(), "essentials.signs.break", Perms.OP)) return;
            e.setCanceled(true);
            e.getPlayer().sendSystemMessage(Messages.get("sign.noBreak"));
        });
    }

    static void use(ServerPlayer p, Kind kind, String[] l) {
        String name = name(kind);
        if (!Perms.has(p, "essentials.signs.use." + name, kind.defaultUse())) {
            p.sendSystemMessage(Messages.get("sign.noUse", kind.tag()));
            return;
        }
        try {
            long price = kind.priceLine() >= 0 ? price(l[kind.priceLine()]) : 0;
            if (name.equals("sell")) {
                Item item = item(l[2]);
                int n = amount(l[1]);
                if (count(p, item) < n) throw new SignError("sign.sell.notEnough", n, new ItemStack(item).getHoverName());
                if (!Wallet.available()) throw new SignError("economy.unavailable");
                remove(p, item, n);
                Wallet.give(p, price, "[Sell] sign " + n + " " + Items.id(item));
                p.sendSystemMessage(Messages.get("sign.sell.done", n, new ItemStack(item).getHoverName(), Money.format(price)));
                return;
            }
            if (price > 0) {
                if (!Wallet.available()) throw new SignError("economy.unavailable");
                if (!Wallet.canAfford(p, price)) throw new SignError("sign.cantAfford", Money.format(price));
            }
            if (!kind.use().run(p, l)) return;
            if (price > 0) {
                Wallet.take(p, price, kind.tag() + " sign");
                p.sendSystemMessage(Messages.get("command.cost.paid", Money.format(price)));
            }
        } catch (SignError e) {
            p.sendSystemMessage(e.message);
        }
    }

    private Signs() {}
}
