package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.source.type.EntityXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.paper.util.PaperUtil;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.paper.util.PaperCombatUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class MobCombatTracker implements Listener {

    private final AuraSkills plugin;
    public static final String TRAP_KILL_KEY = "trap_kill";
    private final NamespacedKey trapKillKey;

    // Cache of combat records per entity UUID
    private final Map<UUID, CombatRecord> combatRecords = new ConcurrentHashMap<>();

    // Stationary kill tracking per player UUID
    private final Map<UUID, PlayerStationaryRecord> stationaryRecords = new ConcurrentHashMap<>();

    public MobCombatTracker(AuraSkills plugin) {
        this.plugin = plugin;
        this.trapKillKey = new NamespacedKey(plugin, TRAP_KILL_KEY);
    }

    public NamespacedKey getTrapKillKey() {
        return trapKillKey;
    }

    public boolean isTrapKill(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(trapKillKey, PersistentDataType.INTEGER);
    }

    public void markTrapKill(LivingEntity entity) {
        entity.getPersistentDataContainer().set(trapKillKey, PersistentDataType.INTEGER, 1);
    }

    @Nullable
    public CombatRecord getCombatRecord(UUID entityId) {
        return combatRecords.get(entityId);
    }

    @Nullable
    public CombatRecord removeCombatRecord(UUID entityId) {
        return combatRecords.remove(entityId);
    }

    /**
     * Checks if this entity is a combat-eligible mob (hostile monster or aggressive neutral mob).
     */
    public boolean isCombatMob(Entity entity) {
        if (!(entity instanceof LivingEntity)) return false;
        if (entity instanceof Player || entity instanceof ArmorStand) return false;

        // Monsters, Enemies, Bosses
        if (entity instanceof Monster || entity instanceof Boss || entity instanceof Enemy) {
            return true;
        }

        // Specific combat-capable neutral mobs
        EntityType type = entity.getType();
        return switch (type) {
            case IRON_GOLEM, POLAR_BEAR, WOLF, PANDA, LLAMA, TRADER_LLAMA,
                 BEE, GOAT, DOLPHIN, PUFFERFISH, SNOW_GOLEM, SLIME, MAGMA_CUBE,
                 SHULKER, GHAST, PHANTOM -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isCombatMob(entity)) return;

        // Player created mobs (Iron Golem, Snow Golem)
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) {
            markTrapKill(entity);
            return;
        }

        if (entity instanceof IronGolem golem && golem.isPlayerCreated()) {
            markTrapKill(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        Entity original = event.getEntity();
        boolean wasTrap = original.getPersistentDataContainer().has(trapKillKey, PersistentDataType.INTEGER);
        CombatRecord originalRecord = combatRecords.get(original.getUniqueId());

        if (wasTrap || (originalRecord != null && originalRecord.isTainted())) {
            for (Entity transformed : event.getTransformedEntities()) {
                if (transformed instanceof LivingEntity living) {
                    markTrapKill(living);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        LivingEntity original = event.getEntity();
        boolean wasTrap = original.getPersistentDataContainer().has(trapKillKey, PersistentDataType.INTEGER);
        CombatRecord originalRecord = combatRecords.get(original.getUniqueId());

        if (wasTrap || (originalRecord != null && originalRecord.isTainted())) {
            // Children will be spawned right after this event
            plugin.getScheduler().executeSync(() -> {
                Location loc = original.getLocation();
                Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0);
                for (Entity e : nearby) {
                    if (e instanceof Slime && e != original) {
                        markTrapKill((LivingEntity) e);
                    }
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDamageTrack(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !isCombatMob(entity)) {
            return;
        }

        double damage = event.getFinalDamage();
        if (damage <= 0.0) return;

        CombatRecord record = combatRecords.computeIfAbsent(entity.getUniqueId(), u -> new CombatRecord(entity));
        record.addTotalDamage(damage);

        // Immediate check: AI disabled
        if (!entity.hasAI() || (entity instanceof Mob mob && !mob.isAware())) {
            record.setTainted(true);
            markTrapKill(entity);
            return;
        }

        Player playerDamager = null;
        EntityXpSource.EntityDamagers damagerType = EntityXpSource.EntityDamagers.PLAYER;
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            playerDamager = resolvePlayerDamager(byEntityEvent.getDamager());
            if (byEntityEvent.getDamager() instanceof Projectile) {
                damagerType = EntityXpSource.EntityDamagers.PROJECTILE;
            }
        }

        if (playerDamager != null) {
            boolean isLegit = !plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_ENABLED)
                    || isLegitAttack(entity, playerDamager, event);
            if (isLegit) {
                record.addLegitDamage(damage);
                record.recordPlayerDamage(playerDamager.getUniqueId(), damage, damagerType);
            } else {
                record.addIllegalDamage(damage);
            }
        } else {
            // Environmental, mob vs mob, pet damage, thorns, etc. -> Not counted towards player legit damage
            record.addNonPlayerDamage(damage);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onEntityDeathCheck(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isCombatMob(entity)) {
            combatRecords.remove(entity.getUniqueId());
            return;
        }

        if (!plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_ENABLED)) {
            return;
        }

        boolean isTrap = false;

        // 1. Check if already marked as trap
        if (isTrapKill(entity)) {
            isTrap = true;
        }

        // 2. Check player created
        if (!isTrap && entity instanceof IronGolem golem && golem.isPlayerCreated()) {
            isTrap = true;
        }

        // 3. Check combat record damage ratio
        CombatRecord record = combatRecords.get(entity.getUniqueId());
        if (!isTrap && record != null) {
            if (record.isTainted()) {
                isTrap = true;
            } else {
                double minRatio = plugin.configDouble(Option.SOURCE_ENTITY_TRAP_PROTECTION_MIN_LEGIT_DAMAGE_RATIO);
                AttributeInstance maxHealthAttr = entity.getAttribute(AttributeCompat.maxHealth);
                double maxHealth = maxHealthAttr != null ? maxHealthAttr.getValue() : 20.0;
                double effectiveHealth = Math.max(maxHealth, record.getTotalDamage());

                double ratio = record.getLegitDamage() / effectiveHealth;
                if (ratio < minRatio) {
                    isTrap = true;
                }
            }
        }

        // 4. Check stationary kills
        Player killer = entity.getKiller();
        if (!isTrap && killer != null) {
            if (isStationaryKill(killer)) {
                isTrap = true;
            }
        }

        if (isTrap) {
            markTrapKill(entity);
            if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_VANILLA_XP)) {
                event.setDroppedExp(0);
            }
        }
    }

    /**
     * Determines whether an attack by a player is legitimate combat or trap/cheese abuse.
     */
    public boolean isLegitAttack(LivingEntity entity, Player player, EntityDamageEvent event) {
        // 1. Vehicle cheese (mob trapped inside boat, minecart, etc.)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_VEHICLE_CHEESE)) {
            if (entity.isInsideVehicle() && !(entity.getVehicle() instanceof LivingEntity)) {
                return false;
            }

            // Player attacking land mob from a boat on water
            if (player.isInsideVehicle() && player.getVehicle() instanceof Boat) {
                if (!isAquaticOrFlyingMob(entity)) {
                    return false;
                }
            }
        }

        // 2. Movement impeding block entrapment (cobweb, powder snow, sweet berry, honey block)
        if (isTrappedInImpedingBlock(entity)) {
            return false;
        }

        // 3. Entity cramming check (dense processing pit)
        int maxCrammed = plugin.configInt(Option.SOURCE_ENTITY_TRAP_PROTECTION_MAX_CRAMMED_ENTITIES);
        if (maxCrammed > 0 && isEntityCrammed(entity, maxCrammed)) {
            return false;
        }

        // 4. Line-of-sight cheese (hitting through half-slab/trapdoor gaps where mob cannot see player)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_LOS_CHEESE)) {
            if (!hasCombatLineOfSight(entity, player)) {
                return false;
            }
        }

        // 5. Water conveyor cheese
        if (isWaterConveyorTrapped(entity, player)) {
            return false;
        }

        // 6. Pathfinder & reachability check (pit, pillar, wall, ceiling height, arrow sniping from unreachable heights)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_PATH_CHEESE)) {
            if (!canMobFightPlayer(entity, player, event)) {
                return false;
            }
        }

        return true;
    }

    private boolean isAquaticOrFlyingMob(LivingEntity entity) {
        return entity instanceof WaterMob || entity instanceof Drowned || entity instanceof Guardian
                || entity instanceof Flying || entity instanceof Ghast || entity instanceof Phantom || entity instanceof Blaze;
    }

    private boolean isRangedMob(LivingEntity entity) {
        EntityType type = entity.getType();
        return switch (type) {
            case SKELETON, STRAY, BOGGED, PILLAGER, WITCH, SNOW_GOLEM,
                 BLAZE, GHAST, LLAMA, TRADER_LLAMA, BREEZE, SHULKER -> true;
            default -> false;
        };
    }

    private boolean isTrappedInImpedingBlock(LivingEntity entity) {
        EntityType type = entity.getType();
        Block feet = entity.getLocation().getBlock();
        Block eye = entity.getEyeLocation().getBlock();

        Material feetType = feet.getType();
        Material eyeType = eye.getType();

        // Cobweb (except spiders)
        if (type != EntityType.SPIDER && type != EntityType.CAVE_SPIDER) {
            if (feetType == Material.COBWEB || eyeType == Material.COBWEB) {
                return true;
            }
        }

        // Powder snow
        if (feetType == Material.POWDER_SNOW || eyeType == Material.POWDER_SNOW) {
            return true;
        }

        // Sweet berry bush
        if (feetType == Material.SWEET_BERRY_BUSH || eyeType == Material.SWEET_BERRY_BUSH) {
            return true;
        }

        // Standing directly on honey block (cannot jump)
        Block below = feet.getRelative(0, -1, 0);
        return below.getType() == Material.HONEY_BLOCK;
    }

    private boolean isEntityCrammed(LivingEntity entity, int maxCrammed) {
        Location loc = entity.getLocation();
        int count = 0;
        for (Entity nearby : loc.getWorld().getNearbyEntities(loc, 1.5, 1.5, 1.5)) {
            if (isCombatMob(nearby)) {
                count++;
                if (count >= maxCrammed) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasCombatLineOfSight(LivingEntity entity, Player player) {
        // Check if mob has line of sight to player eye or chest
        if (entity.hasLineOfSight(player)) {
            return true;
        }
        Location mobEye = entity.getEyeLocation();
        Location playerEye = player.getEyeLocation();
        Location playerChest = player.getLocation().add(0, 1.0, 0);

        // Raytrace block check
        return isClearLine(mobEye, playerEye) || isClearLine(mobEye, playerChest);
    }

    private boolean isClearLine(Location from, Location to) {
        var result = from.getWorld().rayTraceBlocks(from, to.clone().subtract(from).toVector(),
                from.distance(to), org.bukkit.FluidCollisionMode.NEVER, true);
        return result == null || result.getHitBlock() == null;
    }

    private boolean isWaterConveyorTrapped(LivingEntity entity, Player player) {
        if (isAquaticOrFlyingMob(entity)) return false;

        Block block = entity.getLocation().getBlock();
        if (block.getType() == Material.WATER || block.getType() == Material.BUBBLE_COLUMN) {
            // In water/bubble column and cannot reach player
            if (entity instanceof Mob mob) {
                return !PaperCombatUtil.canMobReachPlayer(mob, player);
            }
        }
        return false;
    }

    private boolean canMobFightPlayer(LivingEntity entity, Player player, EntityDamageEvent event) {
        // Special case: Wither suffocation in bedrock
        if (entity instanceof Wither) {
            if (event.getCause() == EntityDamageEvent.DamageCause.SUFFOCATION) {
                return false;
            }
            // Check if trapped inside bedrock/obsidian ceiling
            Block block = entity.getEyeLocation().getBlock();
            if (block.getType() == Material.BEDROCK || block.getType() == Material.OBSIDIAN) {
                return false;
            }
            return true;
        }

        // Special case: Ender Dragon aerial combat
        if (entity instanceof EnderDragon) {
            return true;
        }

        // Flying mobs or ranged attack mobs (Ghast, Blaze, Phantom, Skeleton, etc.)
        if (isAquaticOrFlyingMob(entity) || isRangedMob(entity)) {
            double dist = entity.getLocation().distance(player.getLocation());
            // If ranged/flying mob is within 24 blocks and has line of sight, it's valid combat
            return dist <= 24.0 && hasCombatLineOfSight(entity, player);
        }

        // Warden special handling: requires either physical path reachability or close-range combat
        if (entity instanceof Warden) {
            if (entity instanceof Mob mob) {
                return PaperCombatUtil.canMobReachPlayer(mob, player);
            }
            return true;
        }

        // Standard ground-based melee mobs (Zombie, Iron Golem, Enderman, Piglin, Spider, etc.)
        if (entity instanceof Mob mob) {
            return PaperCombatUtil.canMobReachPlayer(mob, player);
        }

        return true;
    }

    private boolean isStationaryKill(Player player) {
        int limit = plugin.configInt(Option.SOURCE_ENTITY_TRAP_PROTECTION_STATIONARY_KILL_LIMIT);
        if (limit <= 0) return false;

        double radius = plugin.configDouble(Option.SOURCE_ENTITY_TRAP_PROTECTION_STATIONARY_KILL_RADIUS);
        Location currentLoc = player.getLocation();

        PlayerStationaryRecord record = stationaryRecords.computeIfAbsent(player.getUniqueId(),
                u -> new PlayerStationaryRecord(currentLoc));

        if (record.isWithinRadius(currentLoc, radius)) {
            record.increment();
            return record.getCount() > limit;
        } else {
            record.reset(currentLoc);
            return false;
        }
    }

    @Nullable
    private Player resolvePlayerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        } else if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    /**
     * Tracks combat damage for an individual mob.
     */
    public static class CombatRecord {
        private final UUID entityId;
        private double totalDamage = 0.0;
        private double legitDamage = 0.0;
        private double illegalDamage = 0.0;
        private double nonPlayerDamage = 0.0;
        private boolean tainted = false;

        // Player damage contribution tracking
        private final Map<UUID, Double> playerDamages = new ConcurrentHashMap<>();
        private final Map<UUID, EntityXpSource.EntityDamagers> playerDamagerTypes = new ConcurrentHashMap<>();

        public CombatRecord(LivingEntity entity) {
            this.entityId = entity.getUniqueId();
        }

        public void recordPlayerDamage(UUID playerId, double amount, EntityXpSource.EntityDamagers damagerType) {
            this.playerDamages.merge(playerId, amount, Double::sum);
            this.playerDamagerTypes.put(playerId, damagerType);
        }

        public Map<UUID, Double> getPlayerDamages() {
            return playerDamages;
        }

        public EntityXpSource.EntityDamagers getPlayerDamagerType(UUID playerId) {
            return playerDamagerTypes.getOrDefault(playerId, EntityXpSource.EntityDamagers.PLAYER);
        }

        public void addTotalDamage(double amount) {
            this.totalDamage += amount;
        }

        public void addLegitDamage(double amount) {
            this.legitDamage += amount;
        }

        public void addIllegalDamage(double amount) {
            this.illegalDamage += amount;
        }

        public void addNonPlayerDamage(double amount) {
            this.nonPlayerDamage += amount;
        }

        public double getTotalDamage() {
            return totalDamage;
        }

        public double getLegitDamage() {
            return legitDamage;
        }

        public double getIllegalDamage() {
            return illegalDamage;
        }

        public boolean isTainted() {
            return tainted;
        }

        public void setTainted(boolean tainted) {
            this.tainted = tainted;
        }
    }

    /**
     * Tracks whether a player has stayed in the same stationary position killing mobs.
     */
    public static class PlayerStationaryRecord {
        private Location baseLocation;
        private int count = 0;

        public PlayerStationaryRecord(Location baseLocation) {
            this.baseLocation = baseLocation.clone();
            this.count = 1;
        }

        public boolean isWithinRadius(Location loc, double radius) {
            if (!Objects.equals(loc.getWorld(), baseLocation.getWorld())) {
                return false;
            }
            return loc.distanceSquared(baseLocation) <= radius * radius;
        }

        public void increment() {
            this.count++;
        }

        public int getCount() {
            return count;
        }

        public void reset(Location newLoc) {
            this.baseLocation = newLoc.clone();
            this.count = 1;
        }
    }
}
