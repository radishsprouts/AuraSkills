package dev.aurelium.auraskills.bukkit.skills.archery;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.damage.DamageMeta;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.damage.DamageType;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.bukkit.util.CompatUtil;
import dev.aurelium.auraskills.bukkit.util.VersionUtils;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import dev.aurelium.auraskills.common.scheduler.Task;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.attribute.AttributeModifier.Operation;
import org.bukkit.entity.*;
import org.bukkit.entity.AbstractArrow.PickupStatus;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class ArcheryAbilities extends BukkitAbilityImpl {

    private final UUID legacyStunId = UUID.fromString("886ccad1-20f0-48e4-8634-53f3a76cf2ea");
    private final String legacyStunName = "AureliumSkills-Stun";
    private final String stunKey = "stun_ability";
    private static final double MAX_HOMING_ANGLE_COS = Math.cos(Math.toRadians(25.0));

    public ArcheryAbilities(AuraSkills plugin) {
        super(plugin, Abilities.RETRIEVAL, Abilities.ARCHER, Abilities.BOW_MASTER, Abilities.PIERCING, Abilities.STUN,
                Abilities.HOMING_ARROW, Abilities.MULTISHOT_BARRAGE, Abilities.SNIPER_SCOPE, Abilities.APOLLO_QUIVER);
    }

    private DamageModifier bowMaster(Player player, User user) {
        var ability = Abilities.BOW_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        DamageMeta meta = event.getDamageMeta();
        Player attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            User user = plugin.getUser(attacker);
            if (meta.getDamageType() == DamageType.BOW) {
                meta.addAttackModifier(bowMaster(attacker, user));
                if (meta.getTarget() instanceof LivingEntity livingTarget) {
                    meta.addAttackModifier(sniperScope(attacker, user, livingTarget));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void archeryListener(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof AbstractArrow arrow)) return;

        if (!(arrow.getShooter() instanceof Player player)) return;
        if (player.hasMetadata("NPC")) return;

        // Applies abilities
        User user = plugin.getUser(player);

        if (event.getEntity() instanceof LivingEntity entity) {
            stun(player, user, entity);
            apolloPiercingDamage(player, user, entity, event);
        }

        if (arrow instanceof Arrow normalArrow) {
            piercing(player, event, user, normalArrow);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void pierceListener(EntityShootBowEvent event) {
        if (!(event.getProjectile() instanceof AbstractArrow arrow)) return;

        if (!(arrow.getShooter() instanceof Player player)) return;

        User user = plugin.getUser(player);

        if (arrow instanceof Arrow normalArrow) {
            pierceInit(user, player, normalArrow);
        }

        // Apollo Quiver: 矢の消費キャンセル
        var apollo = Abilities.APOLLO_QUIVER;
        if (!isDisabled(apollo) && !failsChecks(player, apollo)) {
            if (rand.nextDouble() < getValue(apollo, user) / 100.0) {
                ItemStack consumable = event.getConsumable();
                if (consumable != null && consumable.getType() != Material.AIR) {
                    ItemStack refund = consumable.clone();
                    refund.setAmount(1);
                    arrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
                    plugin.getScheduler().executeAtEntity(player, task -> player.getInventory().addItem(refund));
                }
            }
        }

        // Homing Arrow: 微誘導タスク
        var homing = Abilities.HOMING_ARROW;
        if (!isDisabled(homing) && !failsChecks(player, homing)) {
            startHomingTask(player, user, arrow);
        }

        // Multishot Barrage: 連射追加矢
        var barrage = Abilities.MULTISHOT_BARRAGE;
        if (!isDisabled(barrage) && !failsChecks(player, barrage)) {
            if (!arrow.hasMetadata("auraskills_barrage_extra") && rand.nextDouble() < getValue(barrage, user) / 100.0) {
                spawnBarrageArrow(player, user, arrow);
            }
        }
    }

    public void stun(Player player, User user, LivingEntity entity) {
        var ability = Abilities.STUN;
        double stunSpeedReduction = ability.optionDouble("speed_reduction", 0.2);

        if (failsChecks(player, ability)) return;

        if (rand.nextDouble() < (getValue(ability, user) / 100)) {
            AttributeInstance speed = entity.getAttribute(AttributeCompat.movementSpeed);
            if (speed == null) return;
            // Check if there already is a stun modifier
            for (AttributeModifier existingModifier : speed.getModifiers()) {
                if (isStunModifier(existingModifier)) {
                    return;
                }
            }
            // Applies stun
            AttributeModifier modifier = getAttributeModifier(speed, stunSpeedReduction);
            speed.addModifier(modifier);

            scheduleStunRemoval(entity);
        }
    }

    @SuppressWarnings("removal")
    private @NotNull AttributeModifier getAttributeModifier(AttributeInstance speed, double stunSpeedReduction) {
        double reducedSpeed = speed.getValue() * stunSpeedReduction;
        double attributeValue = -1 * reducedSpeed;

        AttributeModifier modifier;
        if (VersionUtils.isAtLeastVersion(21)) {
            NamespacedKey key = new NamespacedKey(plugin, stunKey);
            modifier = new AttributeModifier(key, attributeValue, Operation.ADD_NUMBER, EquipmentSlotGroup.ANY);
        } else {
            modifier = new AttributeModifier(legacyStunId, legacyStunName, attributeValue, Operation.ADD_NUMBER);
        }
        return modifier;
    }

    private void scheduleStunRemoval(LivingEntity entity) {
        plugin.getScheduler().scheduleAtEntity(entity, () -> {
            AttributeInstance newSpeed = entity.getAttribute(AttributeCompat.movementSpeed);
            if (newSpeed == null) return;
            for (AttributeModifier attributeModifier : newSpeed.getModifiers()) {
                if (isStunModifier(attributeModifier)) {
                    newSpeed.removeModifier(attributeModifier);
                }
            }
        }, 40L * 50L, TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void removeStun(PlayerQuitEvent event) {
        // Removes stun on logout
        AttributeInstance speed = event.getPlayer().getAttribute(AttributeCompat.movementSpeed);
        if (speed == null) return;

        for (AttributeModifier attributeModifier : speed.getModifiers()) {
            if (isStunModifier(attributeModifier)) {
                speed.removeModifier(attributeModifier);
            }
        }
    }

    private boolean isStunModifier(AttributeModifier modifier) {
        if (modifier.getName().equals(legacyStunName)) {
            return true;
        }
        if (VersionUtils.isAtLeastVersion(21)) {
            final String pluginNamespace = "auraskills";
            String namespace = modifier.getKey().getNamespace();
            String key = modifier.getKey().getKey();

            return namespace.equals(pluginNamespace) && key.equals(stunKey);
        }
        return false;
    }

    public void piercing(Player player, EntityDamageByEntityEvent event, User user, Arrow arrow) {
        var ability = Abilities.PIERCING;

        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) return;
        // Disable if enemy is blocking with a shield
        Entity damaged = event.getEntity();
        if (damaged instanceof Player damagedPlayer) {
            if (damagedPlayer.isBlocking()) {
                return;
            }
        }
        if (rand.nextDouble() < (getValue(ability, user) / 100)) {
            if (arrow.getPierceLevel() < 127) {
                arrow.setPierceLevel(arrow.getPierceLevel() + 1);
            }
        }
    }

    public void pierceInit(User user, Player player, Arrow arrow) {
        var ability = Abilities.PIERCING;

        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) return;

        if (rand.nextDouble() < (getValue(ability, user) / 100)) {
            // Adds 1 pierce to the initial shot otherwise it doesn't pierce on non-lethal damage.
            if (arrow.getPierceLevel() < 127) {
                arrow.setPierceLevel(arrow.getPierceLevel() + 1);
            }
        }
    }

    @Override
    public String replaceDescPlaceholders(String input, Ability ability, User user) {
        if (ability.equals(Abilities.RETRIEVAL)) {
            return TextUtil.replace(input, "{time}", NumberUtil.format1(ability.optionDouble("delay_sec", 3)));
        } else if (ability.equals(Abilities.STUN)) {
            return TextUtil.replace(input, "{reduction_percent}", NumberUtil.format1(ability.optionDouble("speed_reduction", 0.2) * 100.0));
        }
        return input;
    }

    @EventHandler
    public void retrieval(ProjectileHitEvent event) {
        // Ignore if an entity was hit
        if (event.getHitBlock() == null || event.getHitEntity() != null) return;
        if (!(event.getEntity() instanceof AbstractArrow arrow)) return;
        if (event.getEntity() instanceof Trident) return;
        if (arrow.getPickupStatus() != PickupStatus.ALLOWED) return;
        if (!(arrow.getShooter() instanceof Player player)) return;

        var ability = Abilities.RETRIEVAL;
        if (isDisabled(ability)) return;

        if (failsChecks(player, ability)) return;

        ItemStack item = getArrowItem(arrow);
        plugin.getScheduler().scheduleAtEntity(arrow, () -> {
            if (!arrow.isValid()) return; // Ignore if the arrow has de-spawned or was picked up
            if (!arrow.getWorld().equals(player.getWorld())) return;

            double value = getValue(ability, plugin.getUser(player));
            // Check if arrow is close enough
            if (arrow.getLocation().distanceSquared(player.getLocation()) > value * value) {
                return;
            }

            item.setAmount(1);
            // Abort if the player doesn't have enough inventory space
            plugin.getScheduler().executeAtEntity(player, (task) -> {
                if (!player.getInventory().addItem(item).isEmpty()) {
                    return;
                }

                plugin.getScheduler().executeAtEntity(arrow, (t2) -> {
                    arrow.getWorld().spawnParticle(CompatUtil.witchParticle(), arrow.getLocation(), 5, 0, 0, 0);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.4f, 1.9f);

                    arrow.remove();
                });
            });

        }, Math.round(ability.optionDouble("delay_sec", 3) * 1000), TimeUnit.MILLISECONDS);
    }

    private ItemStack getArrowItem(AbstractArrow abstractArrow) {
        if (abstractArrow instanceof Arrow arrow) {
            if (isNormalArrow(arrow)) {
                return new ItemStack(Material.ARROW);
            } else {
                ItemStack item = new ItemStack(Material.TIPPED_ARROW);
                PotionMeta meta = (PotionMeta) item.getItemMeta();
                if (meta != null) {
                    if (VersionUtils.isAtLeastVersion(20, 2)) {
                        meta.setBasePotionType(arrow.getBasePotionType());
                    } else {
                        setLegacyTippedArrow(arrow, meta);
                    }
                    item.setItemMeta(meta);
                }
                return item;
            }
        } else if (abstractArrow instanceof SpectralArrow) {
            return new ItemStack(Material.SPECTRAL_ARROW);
        }
        return new ItemStack(Material.ARROW);
    }

    private boolean isNormalArrow(Arrow arrow) {
        if (VersionUtils.isAtLeastVersion(20, 2)) {
            return arrow.getBasePotionType() == null || arrow.getBasePotionType().toString().equals("UNCRAFTABLE");
        } else {
            try {
                Method getBasePotionData = arrow.getClass().getDeclaredMethod("getBasePotionData");
                Object potionData = getBasePotionData.invoke(arrow);

                Method getType = potionData.getClass().getDeclaredMethod("getType");
                Object potionType = getType.invoke(potionData);

                return potionType.toString().equals("UNCRAFTABLE");
            } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private DamageModifier sniperScope(Player player, User user, LivingEntity target) {
        var ability = Abilities.SNIPER_SCOPE;
        if (target == null || isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        double dist = player.getLocation().distance(target.getLocation());
        if (dist >= 10.0) {
            double maxBonus = getValue(ability, user) / 100.0;
            double bonus = Math.min(maxBonus, (dist - 10.0) * 0.02);
            if (bonus > 0) {
                return new DamageModifier(bonus, DamageModifier.Operation.ADD_COMBINED);
            }
        }
        return DamageModifier.none();
    }

    private void apolloPiercingDamage(Player player, User user, LivingEntity target, EntityDamageByEntityEvent event) {
        var ability = Abilities.APOLLO_QUIVER;
        if (isDisabled(ability) || failsChecks(player, ability)) return;
        if (player.hasMetadata("auraskills_apollo_pierce")) return;

        double ignoreRatio = getSecondaryValue(ability, user) / 100.0;
        if (ignoreRatio > 0) {
            double extraDmg = Math.max(0.5, event.getDamage() * ignoreRatio * 0.35);
            player.setMetadata("auraskills_apollo_pierce", new FixedMetadataValue(plugin, true));
            try {
                // 発生源の無いダメージのまま、戦闘記録にはこのプレイヤーの正規ダメージとして帰属させる
                ((dev.aurelium.auraskills.bukkit.level.BukkitLevelManager) plugin.getLevelManager()).getMobCombatTracker()
                        .runAttributed(player, () -> target.damage(extraDmg));
            } finally {
                player.removeMetadata("auraskills_apollo_pierce", plugin);
            }
        }
    }

    private void startHomingTask(Player shooter, User user, AbstractArrow arrow) {
        double searchRange = getValue(Abilities.HOMING_ARROW, user);
        double turnStrength = Math.min(0.08, getSecondaryValue(Abilities.HOMING_ARROW, user));

        final int[] ticksRun = {0};
        final AtomicReference<Task> taskRef = new AtomicReference<>();
        Task task = plugin.getScheduler().timerAtEntity(arrow, () -> {
            ticksRun[0]++;
            if (!arrow.isValid() || arrow.isDead() || arrow.isInBlock() || arrow.isOnGround() || ticksRun[0] > 60) {
                if (taskRef.get() != null) taskRef.get().cancel();
                return;
            }
            Vector vel = arrow.getVelocity();
            double speed = vel.length();
            if (speed < 0.3) {
                if (taskRef.get() != null) taskRef.get().cancel();
                return;
            }
            Vector currentDir = vel.clone().normalize();
            Location arrowLoc = arrow.getLocation();

            LivingEntity bestTarget = null;
            double bestDistSq = searchRange * searchRange;

            for (Entity entity : arrow.getNearbyEntities(searchRange, searchRange, searchRange)) {
                if (!(entity instanceof Monster target)) continue;
                if (target.isDead() || !target.isValid() || target.isInvisible()) continue;
                if (target instanceof Tameable t && t.isTamed()) continue;

                Location targetCenter = target.getLocation().add(0, target.getHeight() * 0.65, 0);
                Vector toTarget = targetCenter.toVector().subtract(arrowLoc.toVector());
                double distSq = toTarget.lengthSquared();
                if (distSq > bestDistSq || distSq < 0.25) continue;

                Vector toTargetDir = toTarget.clone().normalize();
                double dot = currentDir.dot(toTargetDir);
                if (dot < MAX_HOMING_ANGLE_COS) continue;

                if (!shooter.hasLineOfSight(target)) continue;

                bestDistSq = distSq;
                bestTarget = target;
            }

            if (bestTarget != null) {
                Location targetCenter = bestTarget.getLocation().add(0, bestTarget.getHeight() * 0.65, 0);
                Vector desiredDir = targetCenter.toVector().subtract(arrowLoc.toVector()).normalize();
                Vector steeredDir = currentDir.multiply(1.0 - turnStrength).add(desiredDir.multiply(turnStrength)).normalize();
                Vector newVel = steeredDir.multiply(speed);
                newVel.setY(newVel.getY() + 0.015);
                arrow.setVelocity(newVel);
            }
        }, 50L, 50L, TimeUnit.MILLISECONDS);
        taskRef.set(task);
    }

    private void spawnBarrageArrow(Player player, User user, AbstractArrow originalArrow) {
        Vector vel = originalArrow.getVelocity();
        Location loc = originalArrow.getLocation();
        double angleOffset = Math.toRadians((rand.nextDouble() - 0.5) * 12.0);
        Vector extraVel = vel.clone().rotateAroundY(angleOffset);

        AbstractArrow extraArrow = player.getWorld().spawnArrow(loc, extraVel, (float) extraVel.length(), 0f);
        extraArrow.setShooter(player);
        extraArrow.setPickupStatus(AbstractArrow.PickupStatus.CREATIVE_ONLY);
        extraArrow.setMetadata("auraskills_barrage_extra", new FixedMetadataValue(plugin, true));

        double dmgRatio = getSecondaryValue(Abilities.MULTISHOT_BARRAGE, user) / 100.0;
        extraArrow.setDamage(originalArrow.getDamage() * dmgRatio);
    }

    // Below 1.20.2
    private void setLegacyTippedArrow(Arrow arrow, PotionMeta meta) {
        try {
            Method getBasePotionData = arrow.getClass().getDeclaredMethod("getBasePotionData");
            Object potionData = getBasePotionData.invoke(arrow);
            // Set PotionData to PotionMeta
            Class<?> potionDataClass = Class.forName("org.bukkit.potion.PotionData");
            Method setBasePotionData = PotionMeta.class.getDeclaredMethod("setBasePotionData", potionDataClass);

            setBasePotionData.invoke(meta, potionData);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException |
                ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

}
