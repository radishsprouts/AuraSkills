package dev.aurelium.auraskills.bukkit.skills.defense;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.damage.DamageMeta;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.bukkit.util.CompatUtil;
import dev.aurelium.auraskills.bukkit.util.VersionUtils;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.bukkit.util.Vector;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class DefenseAbilities extends BukkitAbilityImpl {

    private final Map<UUID, Long> lastStandCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> guardianBarrierTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Location> fortressPositions = new ConcurrentHashMap<>();

    public DefenseAbilities(AuraSkills plugin) {
        super(plugin, Abilities.SHIELDING, Abilities.DEFENDER, Abilities.MOB_MASTER, Abilities.IMMUNITY, Abilities.NO_DEBUFF,
                Abilities.GUARDIAN_BARRIER, Abilities.THORNS_COUNTER, Abilities.LAST_STAND, Abilities.FORTRESS_STANCE);
    }

    public DamageModifier shielding(User user, Player player) {
        var ability = Abilities.SHIELDING;

        if (isDisabled(ability)) return DamageModifier.none();

        if (failsChecks(player, ability)) return DamageModifier.none();

        if (!player.isSneaking()) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        double damageReduction = 1 - (getValue(ability, user) / 100);

        return new DamageModifier(damageReduction - 1, DamageModifier.Operation.MULTIPLY);
    }

    public DamageModifier mobMaster(DamageMeta meta, User user, Player player) {
        var ability = Abilities.MOB_MASTER;

        if (isDisabled(ability)) return DamageModifier.none();

        if (failsChecks(player, ability)) return DamageModifier.none();

        // TODO: Shouldn't this affect projectile damage from skeletons/ghasts etc?
        if (meta.getAttacker() instanceof LivingEntity && !(meta.getAttacker() instanceof Player)) {
            if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

            double damageReduction = 1 - (getValue(ability, user) / 100);

            return new DamageModifier(damageReduction - 1, DamageModifier.Operation.MULTIPLY);
        }

        return DamageModifier.none();
    }

    public void immunity(EntityDamageByEntityEvent event, User user, Player player) {
        var ability = Abilities.IMMUNITY;

        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) return;

        double chance = getValue(ability, user) / 100;
        if (rand.nextDouble() < chance) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void noDebuff(PotionSplashEvent event) {
        var ability = Abilities.NO_DEBUFF;

        if (isDisabled(ability)) return;

        for (LivingEntity entity : event.getAffectedEntities()) {
            if (!(entity instanceof Player player)) continue;

            if (failsChecks(player, ability)) return;

            for (PotionEffect effect : event.getPotion().getEffects()) {
                PotionEffectType type = effect.getType();
                if (isNegativeEffect(type)) {
                    User user = plugin.getUser(player);

                    double chance = getValue(ability, user) / 100;
                    if (rand.nextDouble() < chance) {
                        if (player.hasPotionEffect(type)) continue;

                        event.setIntensity(entity, 0);
                    }
                }
            }
        }
    }

    private boolean isNegativeEffect(PotionEffectType type) {
        if (VersionUtils.isAtLeastVersion(20, 6)) {
            return type.getCategory() == PotionEffectTypeCategory.HARMFUL;
        }

        return type.equals(PotionEffectType.POISON) || type.equals(PotionEffectType.UNLUCK) || type.equals(PotionEffectType.WITHER) ||
                type.equals(PotionEffectType.WEAKNESS) || type.equals(PotionEffectType.HUNGER) || type.equals(PotionEffectType.BLINDNESS) ||
                CompatUtil.isEffect(type, Set.of("slowness", "slow")) ||
                CompatUtil.isEffect(type, Set.of("mining_fatigue", "slow_digging")) ||
                CompatUtil.isEffect(type, Set.of("instant_damage", "harm")) ||
                CompatUtil.isEffect(type, Set.of("nausea", "confusion"));
    }

    public void noDebuffFire(User user, Player player, LivingEntity entity) {
        var ability = Abilities.NO_DEBUFF;

        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) return;

        if (entity.getEquipment() == null) return;

        ItemStack item = entity.getEquipment().getItemInMainHand();
        if (item.getEnchantmentLevel(Enchantment.FIRE_ASPECT) > 0) {
            double chance = getValue(ability, user) / 100;
            if (rand.nextDouble() < chance) {
                plugin.getScheduler().scheduleAtEntity(player, () -> player.setFireTicks(0), 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    @EventHandler
    public void defenseListener(EntityDamageByEntityEvent event) {
        if (event.isCancelled()) return;

        if (!(event.getEntity() instanceof Player player)) return;
        if (player.hasMetadata("NPC")) return;
        User user = plugin.getUser(player);

        if (event.getDamager() instanceof LivingEntity entity) {
            noDebuffFire(user, player, entity);
            thornsCounter(user, player, entity, event);
        }

        immunity(event, user, player);
        guardianBarrierCheck(user, player, event);
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var target = meta.getTargetAsPlayer();
        var attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            var attackerUser = plugin.getUser(attacker);
            meta.addAttackModifier(fortressStanceAttack(attackerUser, attacker));
        }

        if (target != null) {
            var user = plugin.getUser(target);
            meta.addDefenseModifier(mobMaster(meta, user, target));
            meta.addDefenseModifier(shielding(user, target));
            meta.addDefenseModifier(fortressStanceDefense(user, target));
            meta.addDefenseModifier(lastStandDefense(user, target, event));
        }
    }

    private void thornsCounter(User user, Player player, LivingEntity damager, EntityDamageByEntityEvent event) {
        var ability = Abilities.THORNS_COUNTER;
        if (isDisabled(ability) || failsChecks(player, ability)) return;
        if (player.hasMetadata("auraskills_thorns_counter")) return;
        if (damager instanceof Player) return; // PvE専用: 他プレイヤーは対象外

        if (rand.nextDouble() < getValue(ability, user) / 100.0) {
            double counterRatio = getSecondaryValue(ability, user) / 100.0;
            double counterDamage = Math.max(1.0, event.getDamage() * counterRatio);

            player.setMetadata("auraskills_thorns_counter", new FixedMetadataValue(plugin, true));
            try {
                damager.damage(counterDamage, player);
                damager.getWorld().spawnParticle(Particle.CRIT, damager.getLocation().add(0, 1.0, 0), 10, 0.2, 0.2, 0.2, 0.1);
                player.getWorld().playSound(player.getLocation(), Sound.ENCHANT_THORNS_HIT, SoundCategory.PLAYERS, 0.8f, 1.0f);
            } finally {
                player.removeMetadata("auraskills_thorns_counter", plugin);
            }
        }
    }

    private void guardianBarrierCheck(User user, Player player, EntityDamageByEntityEvent event) {
        var ability = Abilities.GUARDIAN_BARRIER;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        // ノックバック無効化チェック
        double kbNegateChance = getSecondaryValue(ability, user) / 100.0;
        if (rand.nextDouble() < kbNegateChance) {
            Vector velBefore = player.getVelocity();
            plugin.getScheduler().scheduleAtEntity(player, () -> player.setVelocity(velBefore), 50, TimeUnit.MILLISECONDS);
        }

        // バリアハートの展開チェック (15秒周期)
        long now = System.currentTimeMillis();
        Long lastBarrier = guardianBarrierTimes.get(player.getUniqueId());
        if (lastBarrier == null || now - lastBarrier >= 15000L) {
            guardianBarrierTimes.put(player.getUniqueId(), now);
            double maxHp = player.getAttribute(AttributeCompat.maxHealth) != null
                    ? player.getAttribute(AttributeCompat.maxHealth).getValue() : 20.0;
            double barrierAmount = Math.max(2.0, maxHp * (getValue(ability, user) / 100.0));
            player.setAbsorptionAmount(Math.max(player.getAbsorptionAmount(), (float) barrierAmount));
            player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.0, 0), 4, 0.3, 0.3, 0.3);
        }
    }

    private DamageModifier fortressStanceDefense(User user, Player player) {
        var ability = Abilities.FORTRESS_STANCE;
        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        Location current = player.getLocation();
        Location last = fortressPositions.get(player.getUniqueId());
        fortressPositions.put(player.getUniqueId(), current);

        if (last != null && last.getWorld().equals(current.getWorld()) && last.distanceSquared(current) <= 2.25) { // 1.5m以内
            double reduction = getValue(ability, user) / 100.0;
            return new DamageModifier(-reduction, DamageModifier.Operation.MULTIPLY);
        }
        return DamageModifier.none();
    }

    private DamageModifier fortressStanceAttack(User user, Player player) {
        var ability = Abilities.FORTRESS_STANCE;
        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        Location current = player.getLocation();
        Location last = fortressPositions.get(player.getUniqueId());
        if (last != null && last.getWorld().equals(current.getWorld()) && last.distanceSquared(current) <= 2.25) {
            double bonus = getSecondaryValue(ability, user) / 100.0;
            return new DamageModifier(bonus, DamageModifier.Operation.ADD_COMBINED);
        }
        return DamageModifier.none();
    }

    private DamageModifier lastStandDefense(User user, Player player, DamageEvent event) {
        var ability = Abilities.LAST_STAND;
        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        double maxHp = player.getAttribute(AttributeCompat.maxHealth) != null
                ? player.getAttribute(AttributeCompat.maxHealth).getValue() : 20.0;
        if (player.getHealth() <= maxHp * 0.3 || (player.getHealth() - event.getDamageMeta().getBaseDamage()) <= maxHp * 0.3) {
            long now = System.currentTimeMillis();
            Long lastUse = lastStandCooldowns.get(player.getUniqueId());
            if (lastUse == null || now - lastUse >= 45000L) {
                lastStandCooldowns.put(player.getUniqueId(), now);
                player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 120, 1, false, false, true));
                player.getWorld().playSound(player.getLocation(), Sound.ITEM_TOTEM_USE, SoundCategory.PLAYERS, 0.6f, 1.3f);
                double reduction = getValue(ability, user) / 100.0;
                return new DamageModifier(-reduction, DamageModifier.Operation.MULTIPLY);
            }
        }
        return DamageModifier.none();
    }

}
