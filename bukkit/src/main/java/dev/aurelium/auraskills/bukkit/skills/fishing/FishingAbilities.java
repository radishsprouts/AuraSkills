package dev.aurelium.auraskills.bukkit.skills.fishing;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.api.event.loot.LootDropEvent;
import dev.aurelium.auraskills.api.event.loot.LootDropEvent.Cause;
import dev.aurelium.auraskills.api.trait.Traits;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.party.PartyFriendlyFireListener;
import dev.aurelium.auraskills.bukkit.trait.GatheringLuckTraits;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Random;

public class FishingAbilities extends BukkitAbilityImpl {

    private final Random random = new Random();

    public FishingAbilities(AuraSkills plugin) {
        super(plugin, Abilities.LUCKY_CATCH, Abilities.FISHER, Abilities.TREASURE_HUNTER, Abilities.GRAPPLER, Abilities.EPIC_CATCH,
                Abilities.ANGLER_SURGE, Abilities.POSEIDON_FAVOR, Abilities.BARBED_HOOK, Abilities.SALVAGER);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void luckyCatch(PlayerFishEvent event) {
        var ability = Abilities.LUCKY_CATCH;

        if (isDisabled(ability)) return;

        Player player = event.getPlayer();

        if (failsChecks(player, ability)) return;

        if (event.isCancelled()) return;

        if (!(event.getCaught() instanceof Item item)) return;

        if (event.getExpToDrop() <= 0) return;

        User user = plugin.getUser(player);

        int extraDrops = plugin.getTraitManager().getTraitImpl(GatheringLuckTraits.class).rollExtraDrops(user, Traits.FISHING_LUCK);
        if (extraDrops == 0) return;

        ItemStack drop = item.getItemStack();

        drop.setAmount(Math.min(drop.getAmount() + extraDrops, drop.getMaxStackSize()));

        LootDropEvent dropEvent = new LootDropEvent(player, user.toApi(), drop, item.getLocation(), Cause.FISHING_LUCK, false);
        Bukkit.getPluginManager().callEvent(dropEvent);

        if (!dropEvent.isCancelled()) {
            item.setItemStack(dropEvent.getItem());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGH)
    public void grappler(PlayerFishEvent event) {
        var ability = Abilities.GRAPPLER;

        if (isDisabled(ability)) return;

        if (event.getCaught() == null) return;

        // Caught entity should be a mob, not an item
        if (event.getCaught() instanceof Item) return;

        Player player = event.getPlayer();
        if (failsChecks(player, ability)) return;
        User user = plugin.getUser(player);

        Vector vector = player.getLocation().toVector().subtract(event.getCaught().getLocation().toVector());
        Vector result = vector.multiply(0.004 + (getValue(ability, user) / 25000));

        if (isUnsafeVelocity(result)) { // Prevent excessive velocity warnings
            return;
        }
        event.getCaught().setVelocity(result);
    }

    private boolean isUnsafeVelocity(Vector vector) {
        double x = vector.getX();
        double y = vector.getY();
        double z = vector.getZ();

        return x > 4 || x < -4 || y > 4 || y < -4 || z > 4 || z < -4;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onFishCast(PlayerFishEvent event) {
        if (event.getState() == PlayerFishEvent.State.FISHING) {
            Player player = event.getPlayer();
            var surgeAbility = Abilities.ANGLER_SURGE;
            if (!isDisabled(surgeAbility) && !failsChecks(player, surgeAbility)) {
                User user = plugin.getUser(player);
                FishHook hook = event.getHook();
                int minWait = hook.getMinWaitTime();
                int maxWait = hook.getMaxWaitTime();
                if (maxWait > 40) {
                    double reduction = getValue(surgeAbility, user) / 100.0;
                    int newMin = Math.max(20, (int) (minWait * (1.0 - (reduction * 0.4))));
                    int newMax = Math.max(40, (int) (maxWait * (1.0 - (reduction * 0.4))));
                    hook.setWaitTime(newMin, newMax);
                }
            }
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_FISH) {
            Player player = event.getPlayer();
            User user = plugin.getUser(player);

            // Angler Surge double catch
            var surgeAbility = Abilities.ANGLER_SURGE;
            if (!isDisabled(surgeAbility) && !failsChecks(player, surgeAbility)) {
                if (event.getCaught() instanceof Item caughtItem) {
                    double chance = getValue(surgeAbility, user) / 100.0;
                    if (random.nextDouble() < chance) {
                        ItemStack stack = caughtItem.getItemStack();
                        stack.setAmount(Math.min(stack.getAmount() * 2, stack.getMaxStackSize()));
                        caughtItem.setItemStack(stack);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.5f);
                    }
                }
            }

            // Salvager (repairs caught gear and gives enchant bonus)
            var salvagerAbility = Abilities.SALVAGER;
            if (!isDisabled(salvagerAbility) && !failsChecks(player, salvagerAbility)) {
                if (event.getCaught() instanceof Item caughtItem) {
                    ItemStack stack = caughtItem.getItemStack();
                    if (stack.getItemMeta() instanceof Damageable dmg && dmg.hasDamage()) {
                        double percent = getValue(salvagerAbility, user);
                        int repair = (int) Math.max(1, stack.getType().getMaxDurability() * (percent / 100.0));
                        dmg.setDamage(Math.max(0, dmg.getDamage() - repair));
                        stack.setItemMeta(dmg);
                        caughtItem.setItemStack(stack);
                        player.playSound(player.getLocation(), Sound.BLOCK_ANVIL_USE, SoundCategory.PLAYERS, 0.5f, 1.4f);
                    }
                }
            }
        } else if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY) {
            // Barbed Hook
            Player player = event.getPlayer();
            var barbedAbility = Abilities.BARBED_HOOK;
            if (!isDisabled(barbedAbility) && !failsChecks(player, barbedAbility)) {
                if (event.getCaught() instanceof LivingEntity living && !(living instanceof Player)
                        && !PartyFriendlyFireListener.shouldProtectTarget(plugin, player, living)) {
                    User user = plugin.getUser(player);
                    double damage = getValue(barbedAbility, user);
                    living.damage(damage, player);
                    living.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 100, 1));
                    living.getWorld().spawnParticle(Particle.CRIT, living.getLocation().add(0, 1, 0), 8, 0.2, 0.2, 0.2, 0.1);
                    player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.7f, 1.2f);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPoseidonFavorDamage(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();
        if (attacker != null) {
            var ability = Abilities.POSEIDON_FAVOR;
            if (!isDisabled(ability) && !failsChecks(attacker, ability)) {
                if (attacker.isInWater() || (attacker.getWorld().hasStorm() && attacker.getLocation().getBlock().getLightFromSky() == 15)) {
                    var user = plugin.getUser(attacker);
                    double boost = getValue(ability, user) / 100.0;
                    meta.addAttackModifier(new DamageModifier(boost, DamageModifier.Operation.ADD_COMBINED));
                }
            }
        }
    }

}
