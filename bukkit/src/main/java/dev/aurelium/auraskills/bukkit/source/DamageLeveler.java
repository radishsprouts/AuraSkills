package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.source.SkillSource;
import dev.aurelium.auraskills.api.source.type.DamageXpSource;
import dev.aurelium.auraskills.api.source.type.DamageXpSource.DamageCause;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.level.BukkitLevelManager;
import dev.aurelium.auraskills.common.source.SourceTypes;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.projectiles.ProjectileSource;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class DamageLeveler extends SourceLeveler {

    private final Map<UUID, Long> lastGainTime = new ConcurrentHashMap<>();
    private final NamespacedKey defenseHitsKey;
    private final NamespacedKey spawnerMobKey;
    private final NamespacedKey roseStackerSpawner;
    private final Map<UUID, StationaryDefenseRecord> stationaryDefenseRecords = new ConcurrentHashMap<>();
    private final Map<UUID, FallDamageRecord> fallDamageRecords = new ConcurrentHashMap<>();

    public DamageLeveler(AuraSkills plugin) {
        super(plugin, SourceTypes.DAMAGE);
        this.defenseHitsKey = new NamespacedKey(plugin, "defense_xp_hits");
        this.spawnerMobKey = new NamespacedKey(plugin, "is_spawner_mob");
        this.roseStackerSpawner = NamespacedKey.fromString("rosestacker:spawner_spawned");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    @SuppressWarnings("deprecation")
    public void onDamage(EntityDamageEvent event) {
        if (disabled()) return;
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        if (player.hasMetadata("NPC")) return;

        var skillSource = getSource(event);
        if (skillSource == null) return;

        DamageXpSource source = skillSource.source();
        Skill skill = skillSource.skill();

        // Check cooldown
        long lastGain = lastGainTime.getOrDefault(player.getUniqueId(), 0L);
        int cooldownMs = source.getCooldownMs();
        if (lastGain + cooldownMs > System.currentTimeMillis()) {
            return;
        }

        // Disregard self inflected damage
        if (event instanceof EntityDamageByEntityEvent entityEvent) {
            if (isSelfInflicted(entityEvent.getDamager(), player)) return;

            // Trap check for defense XP: ignore if damager mob is trapped/crammed or player is cheese-farming
            if (entityEvent.getDamager() instanceof LivingEntity damagerLiving) {
                MobCombatTracker tracker = ((BukkitLevelManager) plugin.getLevelManager()).getMobCombatTracker();
                if (tracker != null && tracker.isCombatMob(damagerLiving)) {
                    if (tracker.isTrapKill(damagerLiving) || !tracker.isLegitAttack(damagerLiving, player, event)) {
                        return;
                    }
                }
            }
        }

        if (failsChecks(event, player, player.getLocation(), skill)) return;

        // Defense skill specific anti-afk and exploit protections
        if (skill.equals(Skills.DEFENSE)) {
            // Check shield blocking option
            if (!Skills.DEFENSE.optionBoolean("allow_shield_blocking", false) && player.isBlocking()) {
                return;
            }

            // Defense from mob attack: verify mob origin and hit count limits
            if (event instanceof EntityDamageByEntityEvent entityEvent && entityEvent.getDamager() instanceof LivingEntity damagerLiving) {
                MobCombatTracker tracker = ((BukkitLevelManager) plugin.getLevelManager()).getMobCombatTracker();
                if (tracker != null) {
                    // Block defense XP from artificial spawn mobs (silverfish blocks, spawners, potions, etc.)
                    if (tracker.isNoDefenseXp(damagerLiving)) {
                        return;
                    }
                }

                PersistentDataContainer pdc = damagerLiving.getPersistentDataContainer();
                if (pdc.has(spawnerMobKey, PersistentDataType.INTEGER) || pdc.has(roseStackerSpawner, PersistentDataType.INTEGER)) {
                    return;
                }

                // Per-mob defense hit limit (maximum 5 hits per mob)
                int hits = pdc.getOrDefault(defenseHitsKey, PersistentDataType.INTEGER, 0);
                if (hits >= 5) {
                    return;
                }
                pdc.set(defenseHitsKey, PersistentDataType.INTEGER, hits + 1);
            }

            // Stationary defense check (taking damage repeatedly without moving or looking around)
            Location loc = player.getLocation();
            StationaryDefenseRecord defRecord = stationaryDefenseRecords.computeIfAbsent(player.getUniqueId(), u -> new StationaryDefenseRecord(loc));
            boolean withinRadius = defRecord.isWithinRadius(loc, 3.0);
            boolean headMoved = defRecord.hasHeadMoved(loc);

            if (withinRadius) {
                defRecord.increment();
                if (headMoved) {
                    defRecord.updateHead(loc);
                }
                if (defRecord.getCount() > 10) {
                    return; // Stationary defense limit exceeded
                }
            } else {
                if (!headMoved) {
                    defRecord.increment();
                    if (defRecord.getCount() > 10) {
                        return;
                    }
                } else {
                    defRecord.reset(loc);
                }
            }
        }

        // Fall damage anti-afk check (Agility XP loop in water-elevator / drop trap)
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL) {
            FallDamageRecord fallRecord = fallDamageRecords.computeIfAbsent(player.getUniqueId(), u -> new FallDamageRecord(player.getLocation()));
            if (fallRecord.recordFall(player.getLocation())) {
                return; // Stationary fall loop detected
            }
        }

        double damage;
        if (source.useOriginalDamage()) {
            damage = event.getOriginalDamage(EntityDamageEvent.DamageModifier.BASE);
        } else {
            damage = event.getFinalDamage();
        }

        // Check if player survives
        if (source.mustSurvive() && event.getFinalDamage() >= player.getHealth()) {
            return;
        }

        double xp = damage * source.getXp();

        // Adjust to max and min for defense
        if (skill.equals(Skills.DEFENSE)) {
            xp = Math.min(xp, Skills.DEFENSE.optionDouble("max", 100.0));
            // Returns if xp is less than the min
            if (xp < Skills.DEFENSE.optionDouble("min", 0)) {
                return;
            }
        }

        User user = plugin.getUser(player);

        Entity damager = null;
        if (event instanceof EntityDamageByEntityEvent entityEvent) {
            damager = entityEvent.getDamager();
        }

        try {
            DamageCause skillsCause = DamageCause.valueOf(event.getCause().name());

            plugin.getLevelManager().addDamageXp(user, skill, source, xp, skillsCause, damager, event);

            // Mark last gained time
            lastGainTime.put(player.getUniqueId(), System.currentTimeMillis());
        } catch (IllegalArgumentException e) {
            plugin.logger().warn("Bukkit DamageCause " + event.getCause().name() + " is missing in AuraSkills DamageCause enum");
        }
    }

    private boolean isSelfInflicted(Entity damager, Player player) {
        if (damager instanceof Player) {
            return damager.equals(player);
        } else if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter) {
                return shooter.equals(player);
            }
        }
        return false;
    }

    private SkillSource<DamageXpSource> getSource(EntityDamageEvent event) {
        var sources = plugin.getSkillManager().getSourcesOfType(DamageXpSource.class);
        for (SkillSource<DamageXpSource> entry : sources) {
            DamageXpSource source = entry.source();
            // Check if causes match
            DamageXpSource.DamageCause[] causes = source.getCauses();
            if (causes != null) {
                boolean matchingCause = false;
                for (DamageXpSource.DamageCause cause : causes) {
                    if (cause.toString().equals(event.getCause().toString())) {
                        matchingCause = true;
                        break;
                    }
                }
                if (!matchingCause) {
                    continue;
                }
            }
            // Check excluded causes
            DamageXpSource.DamageCause[] excludedCauses = source.getExcludedCauses();
            if (excludedCauses != null) {
                boolean matchingCause = false;
                for (DamageXpSource.DamageCause cause : excludedCauses) {
                    if (cause.toString().equals(event.getCause().toString())) {
                        matchingCause = true;
                        break;
                    }
                }
                if (matchingCause) {
                    continue;
                }
            }
            // Check excluded damagers
            if (source.getExcludedDamagers() != null) {
                if (event instanceof EntityDamageByEntityEvent entityEvent) {
                    if (Arrays.stream(source.getExcludedDamagers())
                            .anyMatch(excludedDamager -> damagerMatches(source, entityEvent.getDamager(), excludedDamager))) {
                        continue;
                    }
                } else {
                    continue;
                }
            }
            // Check damager
            if (source.getDamagers() != null) {
                if (event instanceof EntityDamageByEntityEvent entityEvent) {
                    if (Arrays.stream(source.getDamagers())
                            .noneMatch(damagerName -> damagerMatches(source, entityEvent.getDamager(), damagerName))) {
                        continue;
                    }
                } else {
                    continue;
                }
            }
            return entry;
        }
        return null;
    }

    private boolean damagerMatches(DamageXpSource source, Entity damager, String name) {
        if (source.includeProjectiles() && damager instanceof Projectile projectile) {
            return projectileSourceMatches(projectile.getShooter(), name);
        } else {
            return entityDamagerMatches(damager, name);
        }
    }

    private boolean entityDamagerMatches(Entity damager, String name) {
        if (name.equalsIgnoreCase("mob")) {
            return damager instanceof Mob;
        } else {
            return damager.getType().toString().equalsIgnoreCase(name);
        }
    }

    private boolean projectileSourceMatches(ProjectileSource projectileSource, String name) {
        if (name.equalsIgnoreCase("mob")) {
            return projectileSource instanceof Mob;
        } else if (projectileSource instanceof LivingEntity livingEntity) {
            return livingEntity.getType().toString().equalsIgnoreCase(name);
        }
        return false;
    }

    public static class StationaryDefenseRecord {
        private Location anchorLocation;
        private float lastYaw;
        private float lastPitch;
        private int count = 0;

        public StationaryDefenseRecord(Location loc) {
            this.anchorLocation = loc.clone();
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
            this.count = 1;
        }

        public boolean isWithinRadius(Location loc, double radius) {
            if (!Objects.equals(loc.getWorld(), anchorLocation.getWorld())) return false;
            return loc.distanceSquared(anchorLocation) <= radius * radius;
        }

        public boolean hasHeadMoved(Location loc) {
            float yawDiff = Math.abs(loc.getYaw() - lastYaw);
            if (yawDiff > 180.0f) yawDiff = 360.0f - yawDiff;
            float pitchDiff = Math.abs(loc.getPitch() - lastPitch);
            return yawDiff > 5.0f || pitchDiff > 3.0f;
        }

        public void increment() {
            this.count++;
        }

        public int getCount() {
            return count;
        }

        public void updateHead(Location loc) {
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
        }

        public void reset(Location loc) {
            this.anchorLocation = loc.clone();
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
            this.count = 1;
        }
    }

    public static class FallDamageRecord {
        private Location lastLocation;
        private long lastTime;
        private int streak;

        public FallDamageRecord(Location loc) {
            this.lastLocation = loc.clone();
            this.lastTime = System.currentTimeMillis();
            this.streak = 1;
        }

        public boolean recordFall(Location loc) {
            long now = System.currentTimeMillis();
            boolean samePlace = Objects.equals(loc.getWorld(), lastLocation.getWorld())
                    && loc.distanceSquared(lastLocation) <= 4.0; // 2m radius
            boolean recent = (now - lastTime) <= 45000L; // within 45s

            this.lastLocation = loc.clone();
            this.lastTime = now;

            if (samePlace && recent) {
                streak++;
            } else {
                streak = 1;
            }
            return streak > 3; // Block XP if streak > 3
        }
    }

}
