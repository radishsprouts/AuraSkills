package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.source.type.EntityXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.level.BukkitLevelManager;
import dev.aurelium.auraskills.bukkit.skills.fighting.FightingAbilities;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.scheduler.Task;
import dev.aurelium.auraskills.common.scheduler.TaskRunnable;
import dev.aurelium.auraskills.paper.util.PaperCombatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.entity.SlimeSplitEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class MobCombatTracker implements Listener {

    private final AuraSkills plugin;
    public static final String TRAP_KILL_KEY = "trap_kill";
    public static final String TRAP_REASON_KEY = "trap_reason";
    public static final String NO_DEFENSE_XP_KEY = "no_defense_xp";
    private final NamespacedKey trapKillKey;
    private final NamespacedKey trapReasonKey;
    private final NamespacedKey noDefenseXpKey;
    private final NamespacedKey chunkKillMaxKey;

    /** 更新の無い記録を掃除するまでの時間。 */
    public static final long STALE_RECORD_MILLIS = 5 * 60_000L;
    /** 掃除の間隔（秒）。ConcurrentHashMap の走査だけなので1分に1回で足りる。 */
    private static final long CLEANUP_PERIOD_SECONDS = 60L;
    /** 派生ダメージ（出血・延焼・貫通）をその場で判定する、プレイヤーとモブの最大距離。これより遠いと直近の直接攻撃の判定を使う。 */
    private static final double DERIVED_JUDGE_MAX_DISTANCE = 64.0;
    /** 派生ダメージの判定結果を使い回す時間（ミリ秒）。延焼・出血は毎秒入るので、毎回の経路探索を避ける。 */
    private static final long DERIVED_JUDGE_TTL_MILLIS = 1000L;
    /** 着火の持続時間に足す余裕（ミリ秒）。 */
    private static final long IGNITE_GRACE_MILLIS = 1000L;

    private Task cleanupTask;
    private boolean removeListenerRegistered = false;

    // Cache of combat records per entity UUID
    private final Map<UUID, CombatRecord> combatRecords = new ConcurrentHashMap<>();

    // Stationary kill tracking per player UUID
    private final Map<UUID, PlayerStationaryRecord> stationaryRecords = new ConcurrentHashMap<>();

    // 発生源の無い damage() をプレイヤーのダメージとして記録するための、同一スレッド内の一時的な帰属先
    private final ThreadLocal<UUID> attributedPlayer = new ThreadLocal<>();

    public MobCombatTracker(AuraSkills plugin) {
        this.plugin = plugin;
        this.trapKillKey = new NamespacedKey(plugin, TRAP_KILL_KEY);
        this.trapReasonKey = new NamespacedKey(plugin, TRAP_REASON_KEY);
        this.noDefenseXpKey = new NamespacedKey(plugin, NO_DEFENSE_XP_KEY);
        this.chunkKillMaxKey = new NamespacedKey("levelledmobs", "chunk_kill_max_reached");
    }

    /**
     * 記録の掃除とモブ除去の監視を始める。掃除は非同期スレッドで行う（ConcurrentHashMap の走査だけでワールドには触れないので Folia でも安全）。
     * 再読み込みで再度呼ばれても、掃除のタスクは1つだけにする。
     */
    public void start() {
        stop();
        cleanupTask = plugin.getScheduler().timerAsync(new TaskRunnable() {
            @Override
            public void run() {
                cleanupStale(System.currentTimeMillis());
            }
        }, CLEANUP_PERIOD_SECONDS, CLEANUP_PERIOD_SECONDS, TimeUnit.SECONDS);
        if (!removeListenerRegistered) {
            // モブがワールドから消えた時に記録を消す（Paper のみ。無ければ掃除が拾う）
            removeListenerRegistered = PaperCombatUtil.registerEntityRemoveListener(plugin, this::removeCombatRecord);
        }
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    /** 更新の無い記録を消す。 */
    void cleanupStale(long nowMillis) {
        combatRecords.values().removeIf(r -> CombatXpMath.isStale(r.getLastUpdateMillis(), nowMillis, STALE_RECORD_MILLIS));
        stationaryRecords.values().removeIf(r -> CombatXpMath.isStale(r.getLastCountedMillis(), nowMillis, STALE_RECORD_MILLIS));
    }

    private CombatRecord getOrCreateRecord(LivingEntity entity) {
        CombatRecord record = combatRecords.computeIfAbsent(entity.getUniqueId(), u -> new CombatRecord(entity));
        record.touch();
        return record;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        stationaryRecords.remove(event.getPlayer().getUniqueId());
    }

    public NamespacedKey getTrapKillKey() {
        return trapKillKey;
    }

    public NamespacedKey getNoDefenseXpKey() {
        return noDefenseXpKey;
    }

    public boolean isTrapKill(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(trapKillKey, PersistentDataType.INTEGER);
    }

    public void markTrapKill(LivingEntity entity) {
        entity.getPersistentDataContainer().set(trapKillKey, PersistentDataType.INTEGER, 1);
    }

    public void markTrapKill(LivingEntity entity, XpReductionReason reason) {
        markTrapKill(entity);
        entity.getPersistentDataContainer().set(trapReasonKey, PersistentDataType.STRING, reason.name());
    }

    /** 罠印が付いた理由。理由が記録されていなければ OTHER_TRAP。 */
    public XpReductionReason getTrapReason(LivingEntity entity) {
        String name = entity.getPersistentDataContainer().get(trapReasonKey, PersistentDataType.STRING);
        if (name != null) {
            try {
                return XpReductionReason.valueOf(name);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return XpReductionReason.OTHER_TRAP;
    }

    public boolean isNoDefenseXp(LivingEntity entity) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        return pdc.has(noDefenseXpKey, PersistentDataType.INTEGER);
    }

    public void markNoDefenseXp(LivingEntity entity) {
        entity.getPersistentDataContainer().set(noDefenseXpKey, PersistentDataType.INTEGER, 1);
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
     * 発生源の無い damage() を、指定プレイヤーのダメージとして記録させて実行する（Apollo の貫通ダメージ用）。
     * damage() の中で呼ばれるイベントは同じスレッドで同期的に処理されるため、スレッド内の一時変数で帰属を渡す。
     * ダメージのイベント自体は変えないので、他のアビリティの再発動や再帰は起きない。
     */
    public void runAttributed(Player player, Runnable action) {
        UUID previous = attributedPlayer.get();
        attributedPlayer.set(player.getUniqueId());
        try {
            action.run();
        } finally {
            if (previous == null) {
                attributedPlayer.remove();
            } else {
                attributedPlayer.set(previous);
            }
        }
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

    /** ボス（連続撃破の判定対象外）。 */
    private boolean isBossLike(Entity entity) {
        return entity instanceof Boss || entity instanceof ElderGuardian || entity instanceof Warden;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        LivingEntity entity = event.getEntity();
        if (!isCombatMob(entity)) return;

        CreatureSpawnEvent.SpawnReason reason = event.getSpawnReason();

        // Mark artificial/special spawn mobs that should not grant passive defense XP (silverfish blocks, spawners, potions, infestation)
        if (reason == CreatureSpawnEvent.SpawnReason.SPAWNER
                || reason == CreatureSpawnEvent.SpawnReason.SILVERFISH_BLOCK
                || reason == CreatureSpawnEvent.SpawnReason.SPELL
                || reason == CreatureSpawnEvent.SpawnReason.REINFORCEMENTS
                || reason.name().contains("INFEST")
                || reason.name().contains("POTION")) {
            markNoDefenseXp(entity);
        }

        // Player created mobs (Iron Golem, Snow Golem)
        if (reason == CreatureSpawnEvent.SpawnReason.BUILD_IRONGOLEM
                || reason == CreatureSpawnEvent.SpawnReason.BUILD_SNOWMAN) {
            markTrapKill(entity, XpReductionReason.PLAYER_GOLEM);
            markNoDefenseXp(entity);
            return;
        }

        if (entity instanceof IronGolem golem && golem.isPlayerCreated()) {
            markTrapKill(entity, XpReductionReason.PLAYER_GOLEM);
            markNoDefenseXp(entity);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityTransform(EntityTransformEvent event) {
        Entity original = event.getEntity();
        boolean wasTrap = original.getPersistentDataContainer().has(trapKillKey, PersistentDataType.INTEGER);
        boolean wasNoDefenseXp = original.getPersistentDataContainer().has(noDefenseXpKey, PersistentDataType.INTEGER);
        CombatRecord originalRecord = combatRecords.get(original.getUniqueId());

        if (wasTrap || (originalRecord != null && originalRecord.isTainted())) {
            XpReductionReason reason = originalRecord != null && originalRecord.getTrapReason() != null
                    ? originalRecord.getTrapReason()
                    : (original instanceof LivingEntity livingOriginal ? getTrapReason(livingOriginal) : XpReductionReason.OTHER_TRAP);
            for (Entity transformed : event.getTransformedEntities()) {
                if (transformed instanceof LivingEntity living) {
                    markTrapKill(living, reason);
                }
            }
        }
        if (wasNoDefenseXp) {
            for (Entity transformed : event.getTransformedEntities()) {
                if (transformed instanceof LivingEntity living) {
                    markNoDefenseXp(living);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSlimeSplit(SlimeSplitEvent event) {
        LivingEntity original = event.getEntity();
        boolean wasTrap = original.getPersistentDataContainer().has(trapKillKey, PersistentDataType.INTEGER);
        boolean wasNoDefenseXp = original.getPersistentDataContainer().has(noDefenseXpKey, PersistentDataType.INTEGER);
        CombatRecord originalRecord = combatRecords.get(original.getUniqueId());

        if (wasTrap || (originalRecord != null && originalRecord.isTainted())) {
            XpReductionReason reason = originalRecord != null && originalRecord.getTrapReason() != null
                    ? originalRecord.getTrapReason() : getTrapReason(original);
            // Children will be spawned right after this event
            plugin.getScheduler().executeSync(() -> {
                Location loc = original.getLocation();
                Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0);
                for (Entity e : nearby) {
                    if (e instanceof Slime && e != original) {
                        markTrapKill((LivingEntity) e, reason);
                    }
                }
            });
        }
        if (wasNoDefenseXp) {
            plugin.getScheduler().executeSync(() -> {
                Location loc = original.getLocation();
                Collection<Entity> nearby = loc.getWorld().getNearbyEntities(loc, 3.0, 3.0, 3.0);
                for (Entity e : nearby) {
                    if (e instanceof Slime && e != original) {
                        markNoDefenseXp((LivingEntity) e);
                    }
                }
            });
        }
    }

    /**
     * 延焼の着火者を記録する（プレイヤー本人、またはプレイヤーの矢など）。燃焼の長さが過ぎたら無効になる。
     * プレイヤー以外による着火（ブロック・他のモブ・溶岩など）があれば、着火者を消す。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityCombust(EntityCombustEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !isCombatMob(entity)) return;
        if (event instanceof EntityCombustByEntityEvent byEntity) {
            Player igniter = resolvePlayerDamager(byEntity.getCombuster());
            if (igniter != null) {
                long now = System.currentTimeMillis();
                long until = now + (long) (Math.max(0.0f, event.getDuration()) * 1000.0f) + IGNITE_GRACE_MILLIS;
                getOrCreateRecord(entity).setIgniter(igniter.getUniqueId(), until);
                return;
            }
        }
        CombatRecord record = combatRecords.get(entity.getUniqueId());
        if (record != null) {
            record.clearIgniter();
        }
    }

    /**
     * ダメージの記録。MONITOR で、取り消されたダメージは数えず、他の処理による補正後の最終ダメージを読む。
     * 各ダメージは、その時点の残りHPで頭打ちにする。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamageTrack(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof LivingEntity entity) || !isCombatMob(entity)) {
            return;
        }

        double damage = CombatXpMath.capDamage(event.getFinalDamage(), entity.getHealth());
        if (damage <= 0.0) return;

        CombatRecord record = getOrCreateRecord(entity);

        // Immediate check: AI disabled
        if (!entity.hasAI() || (entity instanceof Mob mob && !mob.isAware())) {
            record.setTainted(true);
            record.setTrapReason(XpReductionReason.NO_AI);
            markTrapKill(entity, XpReductionReason.NO_AI);
            return;
        }

        EntityDamageEvent.DamageCause cause = event.getCause();
        boolean protectionEnabled = plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_ENABLED);

        Player playerDamager = null;
        boolean neutral = cause == EntityDamageEvent.DamageCause.THORNS;
        EntityXpSource.EntityDamagers damagerType = EntityXpSource.EntityDamagers.PLAYER;
        if (event instanceof EntityDamageByEntityEvent byEntityEvent) {
            if (!neutral) {
                playerDamager = resolvePlayerDamager(byEntityEvent.getDamager());
                // 反撃アビリティによるダメージは中立（分母にも分子にも入れない）
                if (playerDamager != null && playerDamager.hasMetadata("auraskills_thorns_counter")) {
                    playerDamager = null;
                    neutral = true;
                }
            }
            if (byEntityEvent.getDamager() instanceof Projectile) {
                damagerType = EntityXpSource.EntityDamagers.PROJECTILE;
            }
        }

        if (neutral) {
            record.addNeutralDamage(damage);
            return;
        }

        if (playerDamager != null) {
            XpReductionReason bad = protectionEnabled ? evaluateAttack(entity, playerDamager, cause) : null;
            // 直近の直接攻撃の判定結果を最新にする（正規なら不正の記録を消す）。判定できない派生ダメージが参照する
            record.setLastDirectReason(playerDamager.getUniqueId(), bad);
            if (bad == null) {
                record.addLegitDamage(playerDamager.getUniqueId(), damage, damagerType);
            } else {
                record.addIllegalDamage(playerDamager.getUniqueId(), damage, bad);
            }
            return;
        }

        // 発生源の無いダメージのうち、プレイヤー自身が与えたもの（出血・Apollo の貫通・延焼）
        UUID derived = resolveDerivedPlayer(entity, record, cause);
        if (derived != null) {
            // 派生ダメージは、入った時点のプレイヤーとモブの状況で判定する（判定できない時だけ直近の直接攻撃の結果）
            XpReductionReason derivedBad = protectionEnabled ? judgeDerived(entity, derived, record, cause) : null;
            if (derivedBad == null) {
                record.addLegitDamage(derived, damage, record.getPlayerDamagerType(derived));
            } else {
                record.addIllegalDamage(derived, damage, derivedBad);
            }
            return;
        }

        // Environmental, mob vs mob, pet damage, explosion, fall, etc.
        record.addEnvironmentDamage(damage);
    }

    @Nullable
    private UUID resolveDerivedPlayer(LivingEntity entity, CombatRecord record, EntityDamageEvent.DamageCause cause) {
        UUID attributed = attributedPlayer.get();
        if (attributed != null) {
            return attributed;
        }
        PersistentDataContainer container = entity.getPersistentDataContainer();
        NamespacedKey bleedKey = new NamespacedKey(plugin, FightingAbilities.BLEED_DAMAGER_KEY);
        String bleeder = container.get(bleedKey, PersistentDataType.STRING);
        if (bleeder != null) {
            try {
                return UUID.fromString(bleeder);
            } catch (IllegalArgumentException ignored) {
                // 壊れた値は無視する
            }
        }
        if (cause == EntityDamageEvent.DamageCause.FIRE_TICK || cause == EntityDamageEvent.DamageCause.FIRE) {
            // 溶岩に触れている間の炎は環境（溶岩の罠）。プレイヤーの着火とは数えない
            if (PaperCombatUtil.isInLava(entity)) {
                return null;
            }
            // 着火者は、着火の長さの間だけ有効
            return record.getIgniter(System.currentTimeMillis());
        }
        return null;
    }

    /**
     * 派生ダメージの判定。直接攻撃と同じ判定（evaluateAttack）を、プレイヤーの今の位置とモブの今の状態で行う。
     * プレイヤーがオフライン・別ワールド・遠い・別リージョン（Folia で触れない）などで判定できない時は、
     * 直近の直接攻撃の判定結果を使う。不正なら理由、正規なら null。
     */
    @Nullable
    private XpReductionReason judgeDerived(LivingEntity entity, UUID playerId, CombatRecord record,
                                           EntityDamageEvent.DamageCause cause) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && PaperCombatUtil.isOwnedByCurrentRegion(player) && player.isOnline()
                && Objects.equals(player.getWorld(), entity.getWorld())
                && player.getLocation().distanceSquared(entity.getLocation())
                <= DERIVED_JUDGE_MAX_DISTANCE * DERIVED_JUDGE_MAX_DISTANCE) {
            long now = System.currentTimeMillis();
            CombatRecord.DerivedJudgement cached = record.getDerivedJudgement(playerId);
            if (cached != null && now - cached.millis() <= DERIVED_JUDGE_TTL_MILLIS) {
                return cached.reason();
            }
            XpReductionReason reason = evaluateAttack(entity, player, cause);
            record.putDerivedJudgement(playerId, reason, now);
            return reason;
        }
        return record.getLastDirectReason(playerId);
    }

    /**
     * 撃破時の判定。確定的な罠（AI無効・プレイヤー製ゴーレム・連続撃破・LevelledMobs の撃破上限）だけを印付けし、
     * 比率による判定は行わない（比例配分は EntityLeveler が行う）。
     */
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

        CombatRecord record = getOrCreateRecord(entity);
        XpReductionReason trap = null;

        // 1. Check if already marked as trap
        if (isTrapKill(entity)) {
            trap = record.getTrapReason() != null ? record.getTrapReason() : getTrapReason(entity);
        }

        // 2. Check player created
        if (trap == null && entity instanceof IronGolem golem && golem.isPlayerCreated()) {
            trap = XpReductionReason.PLAYER_GOLEM;
        }

        if (trap == null && record.isTainted()) {
            trap = record.getTrapReason() != null ? record.getTrapReason() : XpReductionReason.OTHER_TRAP;
        }

        // 3. LevelledMobs chunk kill max
        if (trap == null && entity.getPersistentDataContainer().has(chunkKillMaxKey, PersistentDataType.INTEGER)) {
            trap = XpReductionReason.CHUNK_KILL_MAX;
        }

        // 4. Check stationary kills (bosses are neither counted nor judged)
        // 連続撃破は撃破者本人の行動が理由なので、モブ単位の罠印にせず、撃破者だけを0にする（EntityLeveler が読む）
        Player killer = entity.getKiller();
        if (trap == null && killer != null && !isBossLike(entity)) {
            if (isStationaryKill(killer)) {
                record.setStationaryPlayer(killer.getUniqueId());
            }
        }

        if (trap != null) {
            record.setTrapReason(trap);
            markTrapKill(entity, trap);
            if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_VANILLA_XP)) {
                event.setDroppedExp(0);
            }
            // 経験値が0になった参加プレイヤーへ理由を伝える
            Set<UUID> participants = new LinkedHashSet<>(record.getParticipants());
            if (killer != null) participants.add(killer.getUniqueId());
            BukkitLevelManager levelManager = (BukkitLevelManager) plugin.getLevelManager();
            for (UUID id : participants) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) {
                    levelManager.getXpReductionNotifier().notifyReduction(p, trap, 0);
                }
            }
            logJudgement(entity.getType(), trap, 0, participants);
        }
    }

    /** 判定理由をサーバーログへ1行出す（対AFKのログと同じ設定 anti_afk.logging_enabled に従う）。 */
    public void logJudgement(EntityType entityType, XpReductionReason reason, int percent, Collection<UUID> participants) {
        if (!plugin.configBoolean(Option.ANTI_AFK_LOGGING_ENABLED)) return;
        StringBuilder names = new StringBuilder();
        for (UUID id : participants) {
            Player p = Bukkit.getPlayer(id);
            if (names.length() > 0) names.append(',');
            names.append(p != null ? p.getName() : id.toString());
        }
        plugin.logger().info("[CombatXp] " + entityType + " 撃破: 理由=" + reason.name()
                + " 経験値=" + (percent <= 0 ? "0" : percent + "%") + " 参加者=" + names);
    }

    /**
     * Determines whether an attack by a player is legitimate combat or trap/cheese abuse.
     */
    public boolean isLegitAttack(LivingEntity entity, Player player, EntityDamageEvent event) {
        return evaluateAttack(entity, player, event.getCause()) == null;
    }

    /**
     * 攻撃が不正なら理由を、正規なら null を返す。ダメージの原因だけを使うので、イベントが無い派生ダメージにも使える。
     */
    @Nullable
    public XpReductionReason evaluateAttack(LivingEntity entity, Player player, EntityDamageEvent.DamageCause cause) {
        // 1. Vehicle cheese (mob trapped inside boat, minecart, etc.)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_VEHICLE_CHEESE)) {
            if (entity.isInsideVehicle() && !(entity.getVehicle() instanceof LivingEntity)) {
                return XpReductionReason.VEHICLE_TRAPPED;
            }

            // Player attacking land mob from a boat on water
            if (player.isInsideVehicle() && player.getVehicle() instanceof Boat) {
                if (!isAquaticOrFlyingMob(entity)) {
                    return XpReductionReason.FROM_BOAT;
                }
            }
        }

        // 2. Movement impeding block entrapment (cobweb, powder snow, sweet berry, honey block)
        if (isTrappedInImpedingBlock(entity)) {
            return XpReductionReason.HELD_IN_PLACE;
        }

        // 3. Entity cramming check (dense processing pit)
        int maxCrammed = plugin.configInt(Option.SOURCE_ENTITY_TRAP_PROTECTION_MAX_CRAMMED_ENTITIES);
        if (maxCrammed > 0 && isEntityCrammed(entity, maxCrammed)) {
            return XpReductionReason.CRAMMED;
        }

        // 4. Line-of-sight cheese (hitting through half-slab/trapdoor gaps where mob cannot see player)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_LOS_CHEESE)) {
            if (!hasCombatLineOfSight(entity, player)) {
                return XpReductionReason.NO_SIGHT;
            }
        }

        // 5. Water conveyor cheese
        if (isWaterConveyorTrapped(entity, player)) {
            return XpReductionReason.WATER_CURRENT;
        }

        // 6. Pathfinder & reachability check (pit, pillar, wall, ceiling height, arrow sniping from unreachable heights)
        if (plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_PATH_CHEESE)) {
            if (!canMobFightPlayer(entity, player, cause)) {
                return XpReductionReason.UNREACHABLE;
            }
        }

        return null;
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

    private boolean canMobFightPlayer(LivingEntity entity, Player player, EntityDamageEvent.DamageCause cause) {
        // Special case: Wither suffocation in bedrock
        if (entity instanceof Wither) {
            if (cause == EntityDamageEvent.DamageCause.SUFFOCATION) {
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
        long now = System.currentTimeMillis();

        PlayerStationaryRecord record = stationaryRecords.computeIfAbsent(player.getUniqueId(),
                u -> new PlayerStationaryRecord(currentLoc, now));

        // 前回数えた撃破から30秒を超えていたら、数え直す
        if (CombatXpMath.isStationaryWindowExpired(record.getLastCountedMillis(), now, CombatXpMath.STATIONARY_WINDOW_MILLIS)) {
            record.reset(currentLoc, now);
            return false;
        }

        boolean withinRadius = record.isWithinRadius(currentLoc, radius);
        boolean headMoved = record.hasHeadMoved(currentLoc);

        if (withinRadius) {
            record.increment(now);
            if (headMoved) {
                record.updateHead(currentLoc);
            }
            return record.getCount() > limit;
        } else {
            // Player moved outside the radius.
            // If the player did NOT move their head (e.g. pushed by water stream, slime launcher, piston),
            // it is an AFK movement exploit -> do NOT reset the kill count!
            if (!headMoved) {
                record.increment(now);
                return record.getCount() > limit;
            } else {
                record.reset(currentLoc, now);
                return false;
            }
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
     * ダメージは 正規（プレイヤー別）/ 不正（理由付き）/ 環境 / 中立 の4区分で記録する。
     */
    public static class CombatRecord {
        private final UUID entityId;
        private double environmentDamage = 0.0;
        private double neutralDamage = 0.0;
        private double illegalDamage = 0.0;
        private boolean tainted = false;
        private XpReductionReason trapReason = null;
        private volatile UUID igniter = null;
        private volatile long igniteUntilMillis = 0L;
        private volatile UUID stationaryPlayer = null;
        private volatile long lastUpdateMillis = System.currentTimeMillis();

        /** 派生ダメージの判定結果（使い回し用）。 */
        public record DerivedJudgement(@Nullable XpReductionReason reason, long millis) {
        }

        private final Map<UUID, DerivedJudgement> derivedJudgements = new ConcurrentHashMap<>();

        // 正規ダメージ（プレイヤー別）
        private final Map<UUID, Double> playerDamages = new ConcurrentHashMap<>();
        private final Map<UUID, EntityXpSource.EntityDamagers> playerDamagerTypes = new ConcurrentHashMap<>();
        // 不正ダメージ（理由別・プレイヤー別）
        private final Map<XpReductionReason, Double> illegalByReason = new ConcurrentHashMap<>();
        private final Map<UUID, Map<XpReductionReason, Double>> illegalByPlayer = new ConcurrentHashMap<>();
        // 直近の直接攻撃の判定結果（不正の理由。正規なら入っていない）
        private final Map<UUID, XpReductionReason> lastDirectReason = new ConcurrentHashMap<>();

        public CombatRecord(LivingEntity entity) {
            this(entity.getUniqueId());
        }

        public CombatRecord(UUID entityId) {
            this.entityId = entityId;
        }

        public synchronized void addLegitDamage(UUID playerId, double amount, EntityXpSource.EntityDamagers damagerType) {
            this.playerDamages.merge(playerId, amount, Double::sum);
            this.playerDamagerTypes.put(playerId, damagerType);
        }

        public synchronized void addIllegalDamage(UUID playerId, double amount, XpReductionReason reason) {
            this.illegalDamage += amount;
            this.illegalByReason.merge(reason, amount, Double::sum);
            this.illegalByPlayer.computeIfAbsent(playerId, u -> new ConcurrentHashMap<>()).merge(reason, amount, Double::sum);
        }

        /** 直近の直接攻撃の判定結果を最新にする。正規（null）なら不正の記録を消す。 */
        public void setLastDirectReason(UUID playerId, @Nullable XpReductionReason reason) {
            if (reason == null) {
                lastDirectReason.remove(playerId);
            } else {
                lastDirectReason.put(playerId, reason);
            }
        }

        @Nullable
        public XpReductionReason getLastDirectReason(UUID playerId) {
            return lastDirectReason.get(playerId);
        }

        @Nullable
        public DerivedJudgement getDerivedJudgement(UUID playerId) {
            return derivedJudgements.get(playerId);
        }

        public void putDerivedJudgement(UUID playerId, @Nullable XpReductionReason reason, long nowMillis) {
            derivedJudgements.put(playerId, new DerivedJudgement(reason, nowMillis));
        }

        public void touch() {
            this.lastUpdateMillis = System.currentTimeMillis();
        }

        public long getLastUpdateMillis() {
            return lastUpdateMillis;
        }

        /** 連続撃破と判定された撃破者（その人だけ経験値0）。 */
        @Nullable
        public UUID getStationaryPlayer() {
            return stationaryPlayer;
        }

        public void setStationaryPlayer(UUID playerId) {
            this.stationaryPlayer = playerId;
        }

        public synchronized void addEnvironmentDamage(double amount) {
            this.environmentDamage += amount;
        }

        public synchronized void addNeutralDamage(double amount) {
            this.neutralDamage += amount;
        }

        /** 正規ダメージ（プレイヤー別）。 */
        public Map<UUID, Double> getPlayerDamages() {
            return playerDamages;
        }

        public EntityXpSource.EntityDamagers getPlayerDamagerType(UUID playerId) {
            return playerDamagerTypes.getOrDefault(playerId, EntityXpSource.EntityDamagers.PLAYER);
        }

        public double getLegitDamage() {
            double sum = 0.0;
            for (double d : playerDamages.values()) sum += d;
            return sum;
        }

        public synchronized double getIllegalDamage() {
            return illegalDamage;
        }

        public synchronized double getEnvironmentDamage() {
            return environmentDamage;
        }

        public synchronized double getNeutralDamage() {
            return neutralDamage;
        }

        public Map<XpReductionReason, Double> getIllegalReasons() {
            return illegalByReason;
        }

        public Map<XpReductionReason, Double> getPlayerIllegalReasons(UUID playerId) {
            return illegalByPlayer.getOrDefault(playerId, Collections.emptyMap());
        }

        /** 正規・不正のどちらかでダメージを与えたプレイヤー。 */
        public Set<UUID> getParticipants() {
            Set<UUID> set = new LinkedHashSet<>(playerDamages.keySet());
            set.addAll(illegalByPlayer.keySet());
            return set;
        }

        /** 着火者。燃焼の長さが過ぎていれば null。 */
        @Nullable
        public UUID getIgniter(long nowMillis) {
            UUID id = igniter;
            if (id == null || nowMillis > igniteUntilMillis) return null;
            return id;
        }

        public void setIgniter(UUID igniter, long untilMillis) {
            this.igniteUntilMillis = untilMillis;
            this.igniter = igniter;
        }

        public void clearIgniter() {
            this.igniter = null;
        }

        public boolean isTainted() {
            return tainted;
        }

        public void setTainted(boolean tainted) {
            this.tainted = tainted;
        }

        @Nullable
        public XpReductionReason getTrapReason() {
            return trapReason;
        }

        public void setTrapReason(XpReductionReason trapReason) {
            this.trapReason = trapReason;
        }
    }

    /**
     * Tracks whether a player has stayed in the same stationary position killing mobs.
     */
    public static class PlayerStationaryRecord {
        private Location baseLocation;
        private float lastYaw;
        private float lastPitch;
        private int count = 0;
        private long lastCountedMillis;

        public PlayerStationaryRecord(Location baseLocation, long nowMillis) {
            this.baseLocation = baseLocation.clone();
            this.lastYaw = baseLocation.getYaw();
            this.lastPitch = baseLocation.getPitch();
            this.count = 1;
            this.lastCountedMillis = nowMillis;
        }

        public boolean isWithinRadius(Location loc, double radius) {
            if (!Objects.equals(loc.getWorld(), baseLocation.getWorld())) {
                return false;
            }
            return loc.distanceSquared(baseLocation) <= radius * radius;
        }

        public boolean hasHeadMoved(Location loc) {
            float yawDiff = Math.abs(loc.getYaw() - lastYaw);
            if (yawDiff > 180.0f) {
                yawDiff = 360.0f - yawDiff;
            }
            float pitchDiff = Math.abs(loc.getPitch() - lastPitch);
            return yawDiff > 5.0f || pitchDiff > 3.0f;
        }

        public void updateHead(Location loc) {
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
        }

        public void increment(long nowMillis) {
            this.count++;
            this.lastCountedMillis = nowMillis;
        }

        public int getCount() {
            return count;
        }

        public long getLastCountedMillis() {
            return lastCountedMillis;
        }

        public void reset(Location newLoc, long nowMillis) {
            this.baseLocation = newLoc.clone();
            this.lastYaw = newLoc.getYaw();
            this.lastPitch = newLoc.getPitch();
            this.count = 1;
            this.lastCountedMillis = nowMillis;
        }
    }
}
