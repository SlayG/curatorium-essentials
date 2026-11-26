package dev.curatorium.essentials.commands;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.curatorium.essentials.command.CommandRegistry;
import dev.curatorium.essentials.command.EssCommand;
import dev.curatorium.essentials.perm.Perms;
import dev.curatorium.essentials.text.Messages;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/** /ext (extinguish) and /potion. */
public final class PotionCommands {
    public static void register() {
        CommandRegistry.add(new Ext());
        CommandRegistry.add(new Potion());
    }

    /** /ext [player]: puts out a burning player. */
    static final class Ext extends EssCommand {
        Ext() {
            super("ext", Perms.OP, "eext", "extinguish", "eextinguish");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            selfOrOther(root, (ctx, p) -> {
                p.clearFire();
                p.sendSystemMessage(Messages.get("ext.done"));
                tellOther(ctx, p, Messages.get("ext.doneOther", p.getDisplayName()));
                return 1;
            });
        }
    }

    /**
     * /potion clear | apply | effect:&lt;effect&gt; power:&lt;n&gt; duration:&lt;seconds&gt; [splash:true]: changes
     * the potion (or tipped arrow) in hand. "apply" (essentials.potion.apply) drinks it without
     * using it up.
     */
    static final class Potion extends EssCommand {
        private static final int MAX_POWER = 255;
        private static final int MAX_SECONDS = 1_000_000;

        Potion() {
            super("potion", Perms.OP, "epotion", "elixer", "eelixer");
        }

        @Override
        protected void build(LiteralArgumentBuilder<CommandSourceStack> root) {
            root.executes(exec(ctx -> fail(ctx, Messages.get("potion.usage"))))
                    .then(textArg("options").suggests((c, b) -> suggest(b)).executes(exec(this::run)));
        }

        private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> suggest(SuggestionsBuilder b) {
            String all = b.getRemaining();
            int cut = all.lastIndexOf(' ') + 1;
            String last = all.substring(cut).toLowerCase(Locale.ROOT);
            SuggestionsBuilder sb = b.createOffset(b.getStart() + cut);
            List<String> options = new ArrayList<>();
            if (cut == 0) {
                options.add("clear");
                options.add("apply");
            }
            if (last.startsWith("effect:")) {
                for (ResourceLocation id : BuiltInRegistries.MOB_EFFECT.keySet()) {
                    options.add("effect:" + (id.getNamespace().equals("minecraft") ? id.getPath() : id.toString()));
                }
            } else {
                for (String o : List.of("effect:", "power:", "duration:", "splash:true")) {
                    if (!all.contains(o.substring(0, o.indexOf(':') + 1))) options.add(o);
                }
            }
            return SharedSuggestionProvider.suggest(options, sb);
        }

        private int run(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
            ServerPlayer me = self(ctx);
            ItemStack hand = me.getMainHandItem();
            PotionContents contents = hand.get(DataComponents.POTION_CONTENTS);
            if (contents == null) return fail(ctx, Messages.get("potion.holdPotion"));
            String[] words = str(ctx, "options").trim().split("\\s+");

            if (words.length == 1 && words[0].equalsIgnoreCase("clear")) {
                hand.set(DataComponents.POTION_CONTENTS, new PotionContents(contents.potion(), contents.customColor(), List.of()));
                reply(ctx, Messages.get("potion.cleared"));
                return 1;
            }
            if (words.length == 1 && words[0].equalsIgnoreCase("apply")) {
                if (!has(ctx.getSource(), "apply", Perms.OP)) return fail(ctx, Messages.get("potion.noApply"));
                int n = 0;
                for (MobEffectInstance e : contents.getAllEffects()) {
                    me.addEffect(new MobEffectInstance(e));
                    n++;
                }
                if (n == 0) return fail(ctx, Messages.get("potion.noEffects"));
                reply(ctx, Messages.get("potion.applied"));
                return 1;
            }

            Holder<MobEffect> effect = null;
            int power = 1, seconds = -1;
            boolean splash = false;
            for (String w : words) {
                int i = w.indexOf(':');
                if (i < 0) return fail(ctx, Messages.get("potion.usage"));
                String key = w.substring(0, i).toLowerCase(Locale.ROOT), value = w.substring(i + 1);
                switch (key) {
                    case "effect" -> {
                        ResourceLocation id = ResourceLocation.tryParse(value.toLowerCase(Locale.ROOT));
                        Optional<Holder.Reference<MobEffect>> found = id == null ? Optional.empty() : BuiltInRegistries.MOB_EFFECT.getHolder(id);
                        if (found.isEmpty()) return fail(ctx, Messages.get("potion.badEffect", value));
                        effect = found.get();
                    }
                    case "power", "strength", "amplifier" -> {
                        Integer v = number(value);
                        if (v == null || v < 1 || v > MAX_POWER) return fail(ctx, Messages.get("potion.badPower", value, MAX_POWER));
                        power = v;
                    }
                    case "duration" -> {
                        Integer v = number(value);
                        if (v == null || v < 1 || v > MAX_SECONDS) return fail(ctx, Messages.get("potion.badDuration", value));
                        seconds = v;
                    }
                    case "splash" -> splash = value.equalsIgnoreCase("true") || value.equalsIgnoreCase("yes");
                    default -> {
                        return fail(ctx, Messages.get("potion.usage"));
                    }
                }
            }
            if (effect == null || seconds < 0) return fail(ctx, Messages.get("potion.usage"));
            String effectName = BuiltInRegistries.MOB_EFFECT.getKey(effect.value()).getPath();
            if (!Perms.has(ctx.getSource(), "essentials.potion." + effectName, Perms.OP)) {
                return fail(ctx, Messages.get("potion.noEffect", effectName));
            }
            ItemStack target = hand;
            if (splash && hand.is(Items.POTION)) {
                target = hand.transmuteCopy(Items.SPLASH_POTION, hand.getCount());
                me.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, target);
            }
            target.set(DataComponents.POTION_CONTENTS, contents.withEffectAdded(new MobEffectInstance(effect, seconds * 20, power - 1)));
            reply(ctx, Messages.get("potion.added", effect.value().getDisplayName(), power, seconds));
            return 1;
        }

        private static Integer number(String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    private PotionCommands() {}
}
