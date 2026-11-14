package dev.aurelium.auraskills.bukkit.skills.fighting;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.damage.DamageType;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.bukkit.util.CompatUtil;
import dev.aurelium.auraskills.common.ability.AbilityData;
import dev.aurelium.auraskills.common.message.type.AbilityMessage;
import dev.aurelium.auraskills.common.scheduler.Task;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import dev.aurelium.auraskills.bukkit.util.ChainBreakHelper;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class FightingAbilities extends BukkitAbilityImpl {

    public static final String BLEED_DAMAGER_KEY = "bleed_damager";
    public static final String DUAL_WIELD_ACTIVE_META = "auraskills_dual_wield_attack";
    private final String parryKey = "parry_ready";
    private final String parryVector = "parry_vector";
    private final Map<UUID, Long> offhandCooldowns = new ConcurrentHashMap<>();

    public FightingAbilities(AuraSkills plugin) {
        super(plugin, Abilities.PARRY, Abilities.FIGHTER, Abilities.SWORD_MASTER, Abilities.FIRST_STRIKE, Abilities.BLEED,
                Abilities.DUAL_WIELD, Abilities.LIFESTEAL_SLASH, Abilities.CLEAVING_ARC, Abilities.EXECUTIONER);
    }

    @Override
    public String replaceDescPlaceholders(String input, Ability ability, User user) {
        if (ability.equals(Abilities.BLEED)) {
            return TextUtil.replace(input,
                    "{base_ticks}", String.valueOf(ability.optionInt("base_ticks", 3)),
                    "{added_ticks}", String.valueOf(ability.optionInt("added_ticks", 2)));
        } else if (ability.equals(Abilities.PARRY)) {
            String secDisplay = NumberUtil.format2((double) ability.optionInt("time_ms", 250) / 1000);
            return TextUtil.replace(input,
                    "{time}", secDisplay);
        }
        return input;
    }

    private DamageModifier swordMaster(Player player, User user) {
        var ability = Abilities.SWORD_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    private DamageModifier firstStrike(User user, Player player) {
        var ability = Abilities.FIRST_STRIKE;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        // Player is on cooldown
        if (player.hasMetadata("AureliumSkills-FirstStrike")) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        Locale locale = user.getLocale();

        if (ability.optionBoolean("enable_message", true)) {
            plugin.getAbilityManager().sendMessage(player, plugin.getMsg(AbilityMessage.FIRST_STRIKE_DEALT, locale));
        }
        // Adds metadata
        player.setMetadata("AureliumSkills-FirstStrike", new FixedMetadataValue(plugin, true));
        // Increments counter
        AbilityData abilityData = user.getAbilityData(ability);
        if (abilityData.containsKey("counter")) {
            abilityData.setData("counter", abilityData.getInt("counter") + 1);
        } else {
            abilityData.setData("counter", 0);
        }
        int id = abilityData.getInt("counter");
        // Schedules metadata removal
        long cooldown = ability.optionInt("cooldown_ticks", 6000);
        plugin.getScheduler().scheduleAtEntity(player, () -> {
            if (user.getAbilityData(ability).containsKey("counter")) {
                if (user.getAbilityData(ability).getInt("counter") == id) {
                    player.removeMetadata("AureliumSkills-FirstStrike", plugin);
                }
            }
        }, cooldown * 50, TimeUnit.MILLISECONDS);

        double modifier = getValue(ability, user) / 100;
        return new DamageModifier(modifier, DamageModifier.Operation.ADD_COMBINED);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void bleedListener(EntityDamageByEntityEvent event) {
        var ability = Abilities.BLEED;

        if (isDisabled(ability)) return;

        if (!(event.getDamager() instanceof Player player)) return;

        if (failsChecks(player, ability)) return;

        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) return;
        // If player used sword
        if (player.getInventory().getItemInMainHand().getType().name().toUpperCase(Locale.ROOT).contains("SWORD")) {
            User user = plugin.getUser(player);

            if (event.getEntity() instanceof LivingEntity) {
                checkBleed(event, user, (LivingEntity) event.getEntity(), ability);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();
        var target = meta.getTargetAsPlayer();

        if (attacker != null) {
            if (meta.getDamageType() == DamageType.SWORD) {
                var user = plugin.getUser(attacker);
                meta.addAttackModifier(swordMaster(attacker, user));
                meta.addAttackModifier(firstStrike(user, attacker));
            }
            if (meta.getDamageType() == DamageType.SWORD || meta.getDamageType() == DamageType.AXE) {
                var user = plugin.getUser(attacker);
                if (meta.getTarget() instanceof LivingEntity livingTarget) {
                    meta.addAttackModifier(executioner(user, livingTarget));
                }
            }
            if (target != null) {
                var user = plugin.getUser(target);
                meta.addDefenseModifier(handleParry(event, target, user));
            }
        }
    }

    public void checkBleed(EntityDamageByEntityEvent event, User user, LivingEntity entity, Ability ability) {
        if (rand.nextDouble() < (getValue(ability, user) / 100)) {
            // Return if damage is fatal
            if (event.getFinalDamage() >= entity.getHealth()) return;

            PersistentDataContainer container = entity.getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "bleed_ticks");

            if (!container.has(key, PersistentDataType.INTEGER)) {
                int baseTicks = ability.optionInt("base_ticks", 3);
                container.set(key, PersistentDataType.INTEGER, baseTicks);
                // Send messages
                if (ability.optionBoolean("enable_enemy_message", true)) {
                    Locale locale = user.getLocale();
                    if (event.getDamager() instanceof Player player) {
                        plugin.getAbilityManager().sendMessage(player, plugin.getMsg(AbilityMessage.BLEED_ENEMY_BLEEDING, locale));
                    }
                }
                if (entity instanceof Player player) {
                    if (ability.optionBoolean("enable_self_message", true)) {
                        Locale locale = user.getLocale();
                        plugin.getAbilityManager().sendMessage(player, plugin.getMsg(AbilityMessage.BLEED_SELF_BLEEDING, locale));
                    }
                }
                // Schedule applying bleed tick damage
                scheduleBleedTicks(entity, user, ability);
            } else {
                int currentTicks = container.getOrDefault(key, PersistentDataType.INTEGER, 0);
                int addedTicks = ability.optionInt("added_ticks", 2);
                int maxTicks = ability.optionInt("max_ticks", 11);
                int resultingTicks = currentTicks + addedTicks;
                if (resultingTicks <= maxTicks) { // Check that resulting bleed ticks does not exceed maximum
                    container.set(key, PersistentDataType.INTEGER, resultingTicks);
                }
            }
        }
    }

    private void scheduleBleedTicks(LivingEntity entity, User user, Ability ability) {
        // Schedules bleed ticks
        final AtomicReference<Task> taskRef = new AtomicReference<>();

        Task task = plugin.getScheduler().timerAtEntity(entity, () -> {
            if (!entity.isValid()) { // Stop if entity died/transformed
                Task t = taskRef.get();
                if (t != null) {
                    t.cancel();
                }
                return;
            }
            PersistentDataContainer container = entity.getPersistentDataContainer();
            NamespacedKey key = new NamespacedKey(plugin, "bleed_ticks");

            if (container.has(key, PersistentDataType.INTEGER)) {
                int bleedTicks = container.getOrDefault(key, PersistentDataType.INTEGER, 0);
                if (bleedTicks > 0) {
                    // Apply bleed
                    double damage = ability.getSecondaryValue(user.getAbilityLevel(ability));
                    double healthBefore = entity.getHealth();
                    // Mark entity with damager UUID for leveler
                    NamespacedKey damagerKey = new NamespacedKey(plugin, BLEED_DAMAGER_KEY);
                    container.set(damagerKey, PersistentDataType.STRING, user.getUuid().toString());

                    entity.damage(damage);
                    // Disable invulnerable frames
                    entity.setNoDamageTicks(0);
                    // Remove damager data
                    container.remove(damagerKey);
                    double healthAfter = entity.getHealth();
                    if (healthAfter != healthBefore) { // Only display particles if damage was actually done
                        displayBleedParticles(entity, ability);
                    }
                    // Decrement bleed ticks
                    if (bleedTicks != 1) {
                        container.set(key, PersistentDataType.INTEGER, bleedTicks - 1);
                    } else {
                        container.remove(key);
                    }
                    return;
                } else {
                    container.remove(key);
                }
            }
            if (entity instanceof Player player) {
                if (ability.optionBoolean("enable_stop_message", true)) {
                    Locale locale = user.getLocale();
                    plugin.getAbilityManager().sendMessage(player, plugin.getMsg(AbilityMessage.BLEED_STOP, locale));
                }
            }
            Task t = taskRef.get();
            if (t != null) {
                t.cancel();
            }
        }, 40 * 50L, ability.optionInt("tick_period", 40) * 50L, TimeUnit.MILLISECONDS);

        taskRef.set(task);
    }

    private void displayBleedParticles(LivingEntity entity, Ability ability) {
        // Check if disabled
        if (!ability.optionBoolean("show_particles", true)) {
            return;
        }
        Location location = entity.getLocation().add(0, entity.getHeight() * 0.6, 0);
        BlockData particleData = Material.REDSTONE_BLOCK.createBlockData();

        entity.getWorld().spawnParticle(CompatUtil.dustParticle(), location, 30, particleData);
    }

    @EventHandler
    public void onDeath(PlayerRespawnEvent event) {
        PersistentDataContainer container = event.getPlayer().getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, "bleed_ticks");
        container.remove(key);
        container.remove(new NamespacedKey(plugin, BLEED_DAMAGER_KEY));
    }

    @EventHandler
    public void parryReady(PlayerInteractEvent event) {
        var ability = Abilities.PARRY;
        if (isDisabled(ability)) return;

        if (event.getAction() != Action.LEFT_CLICK_AIR && event.getAction() != Action.LEFT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        if (!player.getInventory().getItemInMainHand().getType().toString().contains("SWORD")) {
            return;
        }

        if (failsChecks(player, ability)) return;

        User user = plugin.getUser(player);

        // Return if already ready
        if (user.metadataBoolean(parryKey)) {
            return;
        }
        Vector facing = player.getLocation().getDirection();
        user.getMetadata().put(parryKey, true);
        user.getMetadata().put(parryVector, facing);

        scheduleUnready(user);
    }

    public DamageModifier handleParry(DamageEvent event, Player player, User user) {
        var ability = Abilities.PARRY;
        if (failsChecks(player, ability)) return DamageModifier.none();
        // Return if not parry ready
        if (!user.metadataBoolean(parryKey)) return DamageModifier.none();

        if (event.getDamageMeta().getAttacker() != null &&
                !isFacingCloseEnough(user, player, event.getDamageMeta().getAttacker())) {
            return DamageModifier.none();
        }

        double value = getValue(ability, user);

        plugin.getUiProvider().sendActionBar(user, plugin.getMsg(AbilityMessage.PARRY_PARRIED, user.getLocale()));
        plugin.getUiProvider().getActionBarManager().setPaused(user, 1500, TimeUnit.MILLISECONDS);
        if (ability.optionBoolean("enable_sound", true)) {
            player.getWorld().playSound(player.getLocation(), Sound.ITEM_TRIDENT_RETURN, SoundCategory.PLAYERS, 1f, 1.4f);
        }

        Vector velBefore = player.getVelocity();
        // Disable knockback
        plugin.getScheduler().scheduleAtEntity(player, () -> player.setVelocity(velBefore),
                50, TimeUnit.MILLISECONDS);

        return new DamageModifier((1 - value / 100) - 1, DamageModifier.Operation.MULTIPLY);
    }

    private boolean isFacingCloseEnough(User user, Player player, Entity damager) {
        Vector va = damager.getLocation().toVector();
        Vector vb = player.getLocation().toVector();
        Vector playerToDamager = va.subtract(vb).normalize();

        Object facingObj = user.getMetadata().get(parryVector);
        if (facingObj == null) return false;

        Vector facing = (Vector) facingObj;

        return playerToDamager.dot(facing) >= 0.7;
    }

    private void scheduleUnready(User user) {
        plugin.getScheduler().scheduleSync(() -> {
            user.getMetadata().remove(parryKey);
            user.getMetadata().remove(parryVector);
        }, Abilities.PARRY.optionInt("time_ms", 250), TimeUnit.MILLISECONDS);
    }

    private DamageModifier executioner(User user, LivingEntity target) {
        var ability = Abilities.EXECUTIONER;
        if (target == null || isDisabled(ability)) return DamageModifier.none();
        Player player = Bukkit.getPlayer(user.getUuid());
        if (player != null && failsChecks(player, ability)) return DamageModifier.none();

        double maxHealth = target.getAttribute(AttributeCompat.maxHealth) != null
                ? target.getAttribute(AttributeCompat.maxHealth).getValue() : 20.0;
        if (target.getHealth() <= maxHealth * 0.4) {
            double bonus = getValue(ability, user) / 100.0;
            return new DamageModifier(bonus, DamageModifier.Operation.ADD_COMBINED);
        }
        return DamageModifier.none();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFightingCombatEffects(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        if (event.getCause() == EntityDamageEvent.DamageCause.THORNS) return;

        User user = plugin.getUser(player);

        // Lifesteal Slash
        var lifestealAbility = Abilities.LIFESTEAL_SLASH;
        if (!isDisabled(lifestealAbility) && !failsChecks(player, lifestealAbility)) {
            if (rand.nextDouble() < getValue(lifestealAbility, user) / 100.0) {
                double healPercent = getSecondaryValue(lifestealAbility, user) / 100.0;
                double healAmount = Math.min(8.0, event.getFinalDamage() * healPercent);
                if (healAmount > 0) {
                    double maxHp = player.getAttribute(AttributeCompat.maxHealth) != null
                            ? player.getAttribute(AttributeCompat.maxHealth).getValue() : 20.0;
                    player.setHealth(Math.min(maxHp, player.getHealth() + healAmount));
                    player.getWorld().spawnParticle(Particle.HEART, player.getLocation().add(0, 1.2, 0), 2, 0.2, 0.2, 0.2);
                }
            }
        }

        // Cleaving Arc (Only hit enemy monsters, avoid PvP/pets)
        var cleavingAbility = Abilities.CLEAVING_ARC;
        if (!isDisabled(cleavingAbility) && !failsChecks(player, cleavingAbility)) {
            if (!player.hasMetadata("auraskills_cleaving") && !player.hasMetadata(DUAL_WIELD_ACTIVE_META)) {
                player.setMetadata("auraskills_cleaving", new FixedMetadataValue(plugin, true));
                try {
                    double cleaveRatio = getValue(cleavingAbility, user) / 100.0;
                    double cleaveDamage = Math.max(1.0, event.getFinalDamage() * cleaveRatio);
                    int count = 0;
                    for (Entity entity : target.getNearbyEntities(2.5, 2.5, 2.5)) {
                        if (count >= 4) break;
                        if (entity instanceof Monster enemy && !enemy.equals(target) && !enemy.isDead() && enemy.isValid()) {
                            if (enemy instanceof Tameable t && t.isTamed()) continue;
                            enemy.damage(cleaveDamage, player);
                            enemy.getWorld().spawnParticle(Particle.SWEEP_ATTACK, enemy.getLocation().add(0, 1.0, 0), 1);
                            count++;
                        }
                    }
                    if (count > 0) {
                        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.8f, 1.0f);
                    }
                } finally {
                    player.removeMetadata("auraskills_cleaving", plugin);
                }
            }
        }
    }

    @EventHandler
    public void onExecutionerKill(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        var ability = Abilities.EXECUTIONER;
        if (isDisabled(ability) || failsChecks(killer, ability)) return;
        killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 80, 0, false, false, true));
    }

    // --- Dual Wield (二刀流) ---
    private boolean isMeleeWeapon(Material mat) {
        if (mat == null || mat.isAir()) return false;
        String name = mat.name();
        return name.endsWith("_SWORD") || name.endsWith("_AXE");
    }

    private long getOffhandCooldownMs(Material offhandMat) {
        String name = offhandMat.name();
        if (name.endsWith("_AXE")) {
            return 1000L;
        }
        return 625L;
    }

    private double getBaseWeaponDamage(Material mat) {
        return switch (mat.name()) {
            case "NETHERITE_SWORD" -> 8.0;
            case "DIAMOND_SWORD" -> 7.0;
            case "IRON_SWORD" -> 6.0;
            case "STONE_SWORD" -> 5.0;
            case "WOODEN_SWORD", "GOLDEN_SWORD" -> 4.0;
            case "NETHERITE_AXE" -> 10.0;
            case "DIAMOND_AXE", "IRON_AXE", "STONE_AXE" -> 9.0;
            case "WOODEN_AXE", "GOLDEN_AXE" -> 7.0;
            default -> 4.0;
        };
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDualWieldInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player player = event.getPlayer();
        var ability = Abilities.DUAL_WIELD;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (!isMeleeWeapon(mainHand.getType()) || !isMeleeWeapon(offHand.getType())) return;

        if (action == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null) {
            Material clickedType = event.getClickedBlock().getType();
            if (clickedType.isInteractable() && !clickedType.name().contains("_LOG") && !clickedType.name().contains("_WOOD") && !clickedType.name().contains("_STEM")) {
                return;
            }
            if (player.isSneaking()) {
                return;
            }
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
        }

        tryExecuteOffhandAttack(player, offHand, null);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDualWieldInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player player = event.getPlayer();
        var ability = Abilities.DUAL_WIELD;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        ItemStack mainHand = player.getInventory().getItemInMainHand();
        ItemStack offHand = player.getInventory().getItemInOffHand();
        if (!isMeleeWeapon(mainHand.getType()) || !isMeleeWeapon(offHand.getType())) return;

        if (event.getRightClicked() instanceof LivingEntity target && !(target instanceof Villager) && !(target instanceof ArmorStand)) {
            event.setCancelled(true);
            tryExecuteOffhandAttack(player, offHand, target);
        }
    }

    private void tryExecuteOffhandAttack(Player player, ItemStack offHand, @org.jetbrains.annotations.Nullable LivingEntity directTarget) {
        long now = System.currentTimeMillis();
        long cooldown = getOffhandCooldownMs(offHand.getType());
        Long lastAttack = offhandCooldowns.get(player.getUniqueId());
        if (lastAttack != null && now - lastAttack < cooldown) {
            return;
        }
        offhandCooldowns.put(player.getUniqueId(), now);

        player.swingOffHand();
        player.getWorld().playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, SoundCategory.PLAYERS, 0.7f, 1.15f);

        LivingEntity target = directTarget != null ? directTarget : rayTraceMeleeTarget(player, 3.5);
        if (target == null || target.isDead() || target.equals(player)) return;

        User user = plugin.getUser(player);
        double baseDmg = getBaseWeaponDamage(offHand.getType());
        int sharpness = offHand.getEnchantmentLevel(Enchantment.SHARPNESS);
        if (sharpness > 0) {
            baseDmg += 0.5 * sharpness + 0.5;
        }
        double ratio = getValue(Abilities.DUAL_WIELD, user) / 100.0;
        double finalBaseDmg = Math.max(1.0, baseDmg * ratio);

        int prevNoDamageTicks = target.getNoDamageTicks();
        target.setNoDamageTicks(0);
        target.setLastDamage(0);

        player.setMetadata(DUAL_WIELD_ACTIVE_META, new FixedMetadataValue(plugin, true));
        try {
            target.damage(finalBaseDmg, player);
        } finally {
            player.removeMetadata(DUAL_WIELD_ACTIVE_META, plugin);
            target.setNoDamageTicks(Math.min(prevNoDamageTicks, 10));
        }

        int fireAspect = offHand.getEnchantmentLevel(Enchantment.FIRE_ASPECT);
        if (fireAspect > 0) {
            target.setFireTicks(Math.max(target.getFireTicks(), fireAspect * 80));
        }
        int knockback = offHand.getEnchantmentLevel(Enchantment.KNOCKBACK);
        if (knockback > 0) {
            Vector dir = target.getLocation().toVector().subtract(player.getLocation().toVector()).setY(0).normalize();
            target.setVelocity(target.getVelocity().add(dir.multiply(0.4 * knockback).setY(0.15)));
        }
        ChainBreakHelper.damageToolRespectingUnbreaking(player, offHand, 1);
    }

    @org.jetbrains.annotations.Nullable
    private LivingEntity rayTraceMeleeTarget(Player player, double range) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        var result = player.getWorld().rayTraceEntities(eye, dir, range, 0.4,
                e -> e instanceof LivingEntity && !e.equals(player) && !(e instanceof ArmorStand) && !(e instanceof Tameable t && t.isTamed()));
        if (result != null && result.getHitEntity() instanceof LivingEntity living) {
            return living;
        }
        return null;
    }

}
