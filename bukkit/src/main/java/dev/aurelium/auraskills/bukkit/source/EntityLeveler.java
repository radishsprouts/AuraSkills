package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.source.SkillSource;
import dev.aurelium.auraskills.api.source.type.DamageXpSource;
import dev.aurelium.auraskills.api.source.type.EntityXpSource;
import dev.aurelium.auraskills.api.source.type.EntityXpSource.EntityDamagers;
import dev.aurelium.auraskills.api.source.type.EntityXpSource.EntityTriggers;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.hooks.LevelledMobsHook;
import dev.aurelium.auraskills.bukkit.hooks.mythicmobs.MythicMobsHook;
import dev.aurelium.auraskills.bukkit.level.BukkitLevelManager;
import dev.aurelium.auraskills.bukkit.skills.fighting.FightingAbilities;
import dev.aurelium.auraskills.bukkit.util.AttributeCompat;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.source.SourceTypes;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.paper.util.PaperCombatUtil;
import dev.aurelium.auraskills.common.util.data.Pair;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.*;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public class EntityLeveler extends SourceLeveler {

    private final NamespacedKey spawnerMobKey;
    private final NamespacedKey roseStackerSpawner;
    private final NamespacedKey chunkKillMaxKey;

    public EntityLeveler(AuraSkills plugin) {
        super(plugin, SourceTypes.ENTITY);
        this.spawnerMobKey = new NamespacedKey(plugin, "is_spawner_mob");
        this.roseStackerSpawner = NamespacedKey.fromString("rosestacker:spawner_spawned");
        this.chunkKillMaxKey = new NamespacedKey("levelledmobs", "chunk_kill_max_reached");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        LivingEntity entity = event.getEntity();
        BukkitLevelManager levelManager = (BukkitLevelManager) plugin.getLevelManager();
        MobCombatTracker tracker = levelManager.getMobCombatTracker();

        // 経験値を出さずに戻る場合も、戦闘の記録は必ず消す（溜まり続けない）
        if (disabled() || preventMythicXp(entity)) {
            if (tracker != null) tracker.removeCombatRecord(entity.getUniqueId());
            return;
        }

        // 確定的な罠（AI無効・プレイヤー製ゴーレムなど）は0。通知は MobCombatTracker が済ませている
        if (tracker != null && tracker.isTrapKill(entity)) {
            tracker.removeCombatRecord(entity.getUniqueId());
            return;
        }

        // Prevent XP if LevelledMobs chunk kill max was reached
        if (entity.getPersistentDataContainer().has(chunkKillMaxKey, PersistentDataType.INTEGER)) {
            if (tracker != null) tracker.removeCombatRecord(entity.getUniqueId());
            return;
        }

        MobCombatTracker.CombatRecord record = tracker != null ? tracker.removeCombatRecord(entity.getUniqueId()) : null;
        Map<UUID, Double> playerDamages = record != null ? record.getPlayerDamages() : Collections.emptyMap();
        boolean protectionEnabled = plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_ENABLED);

        Location deathLoc = entity.getLocation();
        DamageCause defaultDamageCause = DamageCause.ENTITY_ATTACK;
        if (entity.getLastDamageCause() != null) {
            defaultDamageCause = entity.getLastDamageCause().getCause();
        }

        // 分母に使う正規ダメージ合計は、範囲外・オフラインの人の分も含めた記録全体の合計。
        // 経験値を受け取れるのは範囲内（同じワールドで64ブロック以内）のオンラインの人だけで、範囲外の人の分は誰にも配らない
        Map<UUID, Double> legitDamages = new LinkedHashMap<>();
        double totalLegitDamage = 0.0;
        for (Map.Entry<UUID, Double> entry : playerDamages.entrySet()) {
            double dmg = entry.getValue();
            if (dmg > 0.0) {
                legitDamages.put(entry.getKey(), dmg);
                totalLegitDamage += dmg;
            }
        }

        double illegalDamage = record != null ? record.getIllegalDamage() : 0.0;
        double environmentDamage = record != null ? record.getEnvironmentDamage() : 0.0;
        boolean fallbackKiller = false;

        // 記録に正規・不正・環境のダメージが一切無い時だけ、entity.getKiller() を使う
        // （棘の鎧・反射による撃破は除く）。範囲外の人の正規ダメージがあるなら使わない
        if (CombatXpMath.useFallbackKiller(totalLegitDamage, illegalDamage, environmentDamage)
                && defaultDamageCause != DamageCause.THORNS) {
            Player killer = entity.getKiller();
            if (killer == null) {
                killer = getBleedDamager(entity);
            }
            if (killer != null) {
                legitDamages.put(killer.getUniqueId(), 1.0);
                totalLegitDamage = 1.0;
                fallbackKiller = true;
            }
        }

        // 棘の鎧などの中立のダメージだけで倒した時は経験値を出さない（0のまま）が、撃破者へ理由を知らせる
        if (CombatXpMath.isThornsOnlyKill(totalLegitDamage, illegalDamage, environmentDamage,
                defaultDamageCause == DamageCause.THORNS)) {
            Player thornsKiller = entity.getKiller();
            if (thornsKiller == null) {
                thornsKiller = getBleedDamager(entity);
            }
            if (thornsKiller != null) {
                levelManager.getXpReductionNotifier().notifyReduction(thornsKiller, XpReductionReason.THORNS_ONLY, 0);
                if (tracker != null) {
                    tracker.logJudgement(entity.getType(), XpReductionReason.THORNS_ONLY, 0, List.of(thornsKiller.getUniqueId()));
                }
            }
        }

        // 不正・環境による減額後の割合（複数人の山分けは含まない）。保護が無効なら減額しない
        double factor = 1.0;
        if (protectionEnabled && !fallbackKiller) {
            factor = CombatXpMath.reductionFactor(totalLegitDamage, illegalDamage, environmentDamage);
        }
        boolean preventVanilla = plugin.configBoolean(Option.SOURCE_ENTITY_TRAP_PROTECTION_PREVENT_VANILLA_XP);
        int totalDroppedExp = event.getDroppedExp();
        UUID stationaryId = record != null ? record.getStationaryPlayer() : null;

        // 減額の理由ごとの重み（不正の理由別 + 環境）
        Map<XpReductionReason, Double> weights = new EnumMap<>(XpReductionReason.class);
        if (record != null) {
            weights.putAll(record.getIllegalReasons());
            if (environmentDamage > 0.0) {
                weights.merge(XpReductionReason.ENVIRONMENT, environmentDamage, Double::sum);
            }
        }

        DeathContext ctx = new DeathContext(entity, entity.getType(), deathLoc.clone(), event, record, tracker,
                levelManager, protectionEnabled, fallbackKiller, factor, totalLegitDamage, preventVanilla,
                totalDroppedExp, stationaryId, weights);

        // 配る対象: 正規ダメージのある人、その人のパーティーメンバー、不正なダメージだけを与えた参加者（理由の通知用）
        Set<UUID> candidates = new LinkedHashSet<>(legitDamages.keySet());
        Map<UUID, Double> partyComputedShares = computePartyShares(legitDamages, totalLegitDamage, deathLoc, candidates);
        if (protectionEnabled && record != null) {
            candidates.addAll(record.getParticipants());
        }
        if (stationaryId != null) {
            candidates.add(stationaryId);
        }

        boolean legitRecipient = false;
        for (UUID id : candidates) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;

            // このスレッドが触れる（同じリージョン）プレイヤーは、ここで範囲を判定する。
            // 触れないプレイヤー（別リージョン）は、プレイヤー側のスレッドで範囲判定から行う
            boolean local = PaperCombatUtil.isOwnedByCurrentRegion(p);
            if (local && !isInRange(p, deathLoc)) continue;

            double legit = legitDamages.getOrDefault(id, 0.0);
            double share = partyComputedShares.getOrDefault(id, CombatXpMath.shareRatio(legit, totalLegitDamage));
            if (share > 0.0) legitRecipient = true;

            Award award = buildAward(entity, record, id, legit, share, defaultDamageCause);
            if (local) {
                giveAward(p, award, ctx, false);
            } else {
                plugin.getScheduler().executeAtEntity(p, task -> giveAward(p, award, ctx, true));
            }
        }

        // バニラ経験値の玉は、配る人がいる時と、配る人がいない（不正・環境・範囲外だけの）時に出さない
        if (totalDroppedExp > 0) {
            if (legitRecipient) {
                event.setDroppedExp(0); // Cancel ground orb drop to prevent snatching
            } else if (protectionEnabled && preventVanilla && !fallbackKiller
                    && (illegalDamage + environmentDamage > 0.0 || totalLegitDamage > 0.0)) {
                event.setDroppedExp(0);
            }
        }
    }

    /** 範囲内（同じワールドで死亡位置から64ブロック以内）か。プレイヤーに触れるスレッドで呼ぶこと。 */
    private boolean isInRange(Player player, Location deathLoc) {
        Location loc = player.getLocation();
        return Objects.equals(loc.getWorld(), deathLoc.getWorld())
                && deathLoc.distanceSquared(loc) <= 64.0 * 64.0;
    }

    private Map<UUID, Double> computePartyShares(
            Map<UUID, Double> legitDamages,
            double totalLegitDamage,
            Location deathLoc,
            Set<UUID> candidates
    ) {
        if (totalLegitDamage <= 0.0
                || !plugin.configBoolean(Option.PARTY_ENABLED)
                || !plugin.configBoolean(Option.PARTY_XP_SHARE_ENABLED)) {
            return Collections.emptyMap();
        }

        Map<UUID, PartyImpl> activeParties = new LinkedHashMap<>();
        Map<UUID, Double> partyDamageSums = new LinkedHashMap<>();

        for (Map.Entry<UUID, Double> entry : legitDamages.entrySet()) {
            UUID id = entry.getKey();
            double dmg = entry.getValue();
            if (dmg <= 0.0) continue;

            PartyImpl party = plugin.getPartyManager().getPartyImpl(id);
            if (party == null || party.getSize() <= 1) continue;

            activeParties.putIfAbsent(party.getId(), party);
            partyDamageSums.merge(party.getId(), dmg, Double::sum);
        }

        if (activeParties.isEmpty()) {
            return Collections.emptyMap();
        }

        double bonusPerMember = plugin.configDouble(Option.PARTY_XP_SHARE_COMBAT_BONUS_PER_MEMBER);
        double maxBonus = plugin.configDouble(Option.PARTY_XP_SHARE_COMBAT_MAX_BONUS);
        double equalWeight = plugin.configDouble(Option.PARTY_XP_SHARE_COMBAT_EQUAL_WEIGHT);

        Map<UUID, Double> result = new LinkedHashMap<>();
        for (Map.Entry<UUID, PartyImpl> entry : activeParties.entrySet()) {
            PartyImpl party = entry.getValue();
            double partyDmg = partyDamageSums.getOrDefault(party.getId(), 0.0);
            if (partyDmg <= 0.0) continue;

            List<UUID> eligibleMembers = new ArrayList<>();
            for (UUID memberId : party.getMembers()) {
                Player member = Bukkit.getPlayer(memberId);
                if (member == null || !member.isOnline() || member.isDead()) continue;
                if (PaperCombatUtil.isOwnedByCurrentRegion(member) && !isInRange(member, deathLoc)) {
                    continue;
                }
                eligibleMembers.add(memberId);
                candidates.add(memberId);
            }

            if (eligibleMembers.size() <= 1) continue;

            double partyBaseShare = CombatXpMath.shareRatio(partyDmg, totalLegitDamage);
            double partyBonus = CombatXpMath.combatPartyBonusMultiplier(eligibleMembers.size(), bonusPerMember, maxBonus);
            double boostedPartyShare = partyBaseShare * partyBonus;

            for (UUID memberId : eligibleMembers) {
                double memberDmg = legitDamages.getOrDefault(memberId, 0.0);
                double memberShare = CombatXpMath.combatPartyMemberShare(
                        boostedPartyShare, eligibleMembers.size(), memberDmg, partyDmg, equalWeight);
                result.put(memberId, memberShare);
            }
        }
        return result;
    }

    /** 撃破側のスレッドで、そのプレイヤーに配るスキル経験値の元になる情報を決める（モブに触れる処理はここで済ませる）。 */
    private Award buildAward(LivingEntity entity, MobCombatTracker.CombatRecord record, UUID id, double legit,
                             double share, DamageCause defaultDamageCause) {
        EntityXpSource.EntityDamagers damagerType = record != null
                ? record.getPlayerDamagerType(id)
                : EntityXpSource.EntityDamagers.PLAYER;

        DamageCause cause = defaultDamageCause;
        if (damagerType == EntityXpSource.EntityDamagers.PROJECTILE) {
            cause = DamageCause.PROJECTILE;
        }

        SkillSource<EntityXpSource> skillSource = getSource(entity, damagerType, EntityXpSource.EntityTriggers.DEATH, cause);
        if (skillSource == null && damagerType != EntityXpSource.EntityDamagers.PLAYER) {
            // Fallback to PLAYER damager if PROJECTILE source is not defined
            skillSource = getSource(entity, EntityXpSource.EntityDamagers.PLAYER, EntityXpSource.EntityTriggers.DEATH, DamageCause.ENTITY_ATTACK);
        }
        double baseAmount = 0.0;
        if (skillSource != null) {
            baseAmount = getSpawnerMultiplier(entity, skillSource.skill()) * getLevelledMobsMultiplier(entity)
                    * skillSource.source().getXp();
        }
        return new Award(id, legit, share, skillSource, baseAmount);
    }

    /**
     * 1人分の配分（バニラ経験値・スキル経験値・減額の通知）。プレイヤーに触れる処理なので、
     * そのプレイヤーを扱えるスレッド（同じリージョン、または executeAtEntity）で呼ぶ。
     *
     * @param checkRange 範囲の判定がまだなら true（別リージョンのプレイヤーを、プレイヤー側のスレッドで判定する）
     */
    private void giveAward(Player p, Award a, DeathContext c, boolean checkRange) {
        if (!p.isOnline()) return;
        if (checkRange && !isInRange(p, c.deathLoc())) return;

        boolean stationary = a.id().equals(c.stationaryId());
        // その人のスキル経験値の割合 = 個人またはパーティー配分 × 減額後の割合
        double share = a.share();
        // 連続撃破は撃破者本人だけを0にする
        double ratio = stationary ? 0.0 : share * c.factor();

        // 1. バニラ経験値（prevent_vanilla_xp が true の時は同じ割合）
        if (share > 0.0 && c.totalDroppedExp() > 0) {
            double vanillaRatio = c.preventVanilla() ? ratio : share;
            if (vanillaRatio > 0.0) {
                int playerXp = Math.max(1, (int) Math.round(c.totalDroppedExp() * vanillaRatio));
                p.giveExp(playerXp);
            }
        }

        // 2. スキル経験値
        SkillSource<EntityXpSource> skillSource = a.skillSource();
        if (skillSource == null) return;
        Skill skill = skillSource.skill();
        if (failsChecks(p, c.deathLoc(), skill)) return;

        if (ratio > 0.0) {
            User user = plugin.getUser(p);
            boolean granted = c.levelManager().addEntityXp(user, skill, skillSource.source(), a.baseAmount() * ratio,
                    c.entity(), p, c.event());
            // 対AFKなどで取り消された人には、減額の通知を出さない（対AFKは自分の通知を出す）
            if (!granted) return;
        }

        notifyReduction(p, a, c, stationary);
    }

    /** 実際に減額して付与した（または減額で0になった）人へだけ、理由と割合を通知する。山分けによる減少は減額ではない。 */
    private void notifyReduction(Player p, Award a, DeathContext c, boolean stationary) {
        if (!c.protectionEnabled() || c.fallbackKiller()) return;

        XpReductionReason reason;
        int percent;
        if (stationary) {
            reason = XpReductionReason.STATIONARY;
            percent = 0;
        } else if (a.share() > 0.0) {
            percent = CombatXpMath.displayPercent(c.factor());
            if (percent < 0 || percent >= 100) return;
            reason = CombatXpMath.pickReason(c.weights(), XpReductionReason::isZero);
        } else {
            // 不正なダメージしか与えていない参加者: 経験値なし（その人の不正の理由を優先）
            percent = 0;
            reason = c.record() != null
                    ? CombatXpMath.pickReason(c.record().getPlayerIllegalReasons(a.id()), XpReductionReason::isZero)
                    : null;
            if (reason == null) reason = CombatXpMath.pickReason(c.weights(), XpReductionReason::isZero);
        }
        if (reason == null) return;

        c.levelManager().getXpReductionNotifier().notifyReduction(p, reason, percent);
        if (c.tracker() != null) {
            c.tracker().logJudgement(c.entityType(), reason, percent, List.of(a.id()));
        }
    }

    /** 1人分の配分の元情報（撃破側のスレッドで作る）。 */
    private record Award(UUID id, double legitDamage, double share, @Nullable SkillSource<EntityXpSource> skillSource, double baseAmount) {
    }

    /** 1回の撃破で共通の情報。 */
    private record DeathContext(LivingEntity entity, EntityType entityType, Location deathLoc, EntityDeathEvent event,
                                @Nullable MobCombatTracker.CombatRecord record, @Nullable MobCombatTracker tracker,
                                BukkitLevelManager levelManager, boolean protectionEnabled, boolean fallbackKiller,
                                double factor, double totalLegit, boolean preventVanilla, int totalDroppedExp,
                                @Nullable UUID stationaryId, Map<XpReductionReason, Double> weights) {
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (disabled()) return;
        if (!(event.getEntity() instanceof LivingEntity entity) || event.getEntity() instanceof ArmorStand) {
            return;
        }

        if (preventMythicXp(entity)) return;

        MobCombatTracker tracker = ((BukkitLevelManager) plugin.getLevelManager()).getMobCombatTracker();
        if (tracker != null && tracker.isCombatMob(entity)) {
            if (tracker.isTrapKill(entity)) return;
        }

        // Get the player who damaged the entity and the damager type
        Pair<Player, EntityXpSource.EntityDamagers> damagerPair = resolveDamager(event.getDamager(), event.getCause());
        if (damagerPair == null) return;

        Player player = damagerPair.first();
        if (player.hasMetadata("NPC")) return;

        if (tracker != null && tracker.isCombatMob(entity)) {
            if (!tracker.isLegitAttack(entity, player, event)) {
                return;
            }
        }

        User user = plugin.getUser(player);
        EntityXpSource.EntityDamagers damager = damagerPair.second();

        // Get matching source with damage trigger
        SkillSource<EntityXpSource> skillSource = getSource(entity, damager, EntityXpSource.EntityTriggers.DAMAGE, event.getCause());
        if (skillSource == null) return;

        EntityXpSource source = skillSource.source();
        Skill skill = skillSource.skill();

        if (failsChecks(event, player, entity.getLocation(), skill)) return;

        double damageMultiplier = getDamageMultiplier(entity, source, event);
        plugin.getLevelManager().addEntityXp(user, skill, source, damageMultiplier * getSpawnerMultiplier(entity, skill) * getLevelledMobsMultiplier(entity) * source.getXp(),
                entity, event.getDamager(), event);
    }

    @EventHandler
    public void onBleedDamage(EntityDamageEvent event) {
        if (disabled()) return;
        if (!(event.getEntity() instanceof LivingEntity entity) || event.getEntity() instanceof ArmorStand) {
            return;
        }

        if (preventMythicXp(entity)) return;

        MobCombatTracker tracker = ((BukkitLevelManager) plugin.getLevelManager()).getMobCombatTracker();
        if (tracker != null && tracker.isCombatMob(entity) && tracker.isTrapKill(entity)) {
            return;
        }

        Player player = getBleedDamager(entity);
        if (player == null) return; // Was not damaged by Bleed

        if (player.hasMetadata("NPC")) return;

        User user = plugin.getUser(player);
        SkillSource<EntityXpSource> skillSource = getSource(entity, EntityDamagers.PLAYER, EntityTriggers.DAMAGE, DamageCause.CUSTOM);
        if (skillSource == null) return;

        EntityXpSource source = skillSource.source();
        Skill skill = skillSource.skill();

        if (failsChecks(player, entity.getLocation(), skill)) return;

        double damageMultiplier = getDamageMultiplier(entity, source, event);
        plugin.getLevelManager().addEntityXp(user, skill, source, damageMultiplier * getSpawnerMultiplier(entity, skill) * getLevelledMobsMultiplier(entity) * source.getXp(),
                entity, player, null);
    }

    @Nullable
    public Pair<Player, EntityXpSource.EntityDamagers> resolveDamager(Entity damager, EntityDamageEvent.DamageCause cause) {
        if (damager instanceof Player player) { // Player damager
            if (cause != EntityDamageEvent.DamageCause.ENTITY_ATTACK && cause != EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
                return null;
            }
            return new Pair<>(player, EntityXpSource.EntityDamagers.PLAYER);
        } else if (damager instanceof Projectile projectile) { // Projectile damager
            if (!(projectile.getShooter() instanceof Player player)) { // Make sure shooter is a player
                return null;
            }
            if (damager instanceof ThrownPotion) {
                // Mark as thrown potion if
                if (Skills.ALCHEMY.isEnabled() && plugin.configBoolean(Option.SOURCE_ENTITY_GIVE_ALCHEMY_ON_POTION_COMBAT)) {
                    return new Pair<>(player, EntityXpSource.EntityDamagers.THROWN_POTION);
                }
            }
            return new Pair<>(player, EntityXpSource.EntityDamagers.PROJECTILE);
        }
        return null;
    }

    @Nullable
    public SkillSource<EntityXpSource> getSource(LivingEntity entity, EntityXpSource.EntityDamagers eventDamager, EntityXpSource.EntityTriggers trigger, DamageCause damageCause) {
        var sources = plugin.getSkillManager().getSourcesOfType(EntityXpSource.class);
        sources = filterByTrigger(sources, trigger);

        for (SkillSource<EntityXpSource> entry : sources) {
            EntityXpSource source = entry.source();

            // Discard if entity type does not match
            String entityName = plugin.getPlatformUtil().convertEntityName(source.getEntity().toLowerCase(Locale.ROOT));
            if (!entityName.toUpperCase(Locale.ROOT).equals(entity.getType().toString())) {
                continue;
            }

            // Check if causes match
            DamageXpSource.DamageCause[] causes = source.getCauses();
            if (causes != null) {
                boolean matchingCause = false;
                for (DamageXpSource.DamageCause cause : causes) {
                    if (cause.toString().equals(damageCause.toString())) {
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
                    if (cause.toString().equals(damageCause.toString())) {
                        matchingCause = true;
                        break;
                    }
                }
                if (matchingCause) {
                    continue;
                }
            }

            // Give Alchemy XP if potion is thrown with option give_xp_on_potion_combat
            if (eventDamager == EntityXpSource.EntityDamagers.THROWN_POTION) {
                return new SkillSource<>(source, Skills.ALCHEMY);
            }

            // Return if damager matches
            for (EntityXpSource.EntityDamagers sourceDamager : source.getDamagers()) {
                if (sourceDamager == eventDamager) {
                    return entry;
                }
            }
        }
        return null;
    }

    private boolean preventMythicXp(LivingEntity entity) {
        if (plugin.getHookManager().isRegistered(MythicMobsHook.class)) {
            return plugin.getHookManager().getHook(MythicMobsHook.class).shouldPreventEntityXp(entity);
        }
        return false;
    }

    private List<SkillSource<EntityXpSource>> filterByTrigger(List<SkillSource<EntityXpSource>> sources, EntityXpSource.EntityTriggers trigger) {
        List<SkillSource<EntityXpSource>> filtered = new ArrayList<>();
        for (SkillSource<EntityXpSource> entry : sources) {
            EntityXpSource source = entry.source();
            // Check if trigger matches any of the source triggers
            for (EntityXpSource.EntityTriggers sourceTrigger : source.getTriggers()) {
                if (sourceTrigger == trigger) {
                    filtered.add(entry);
                    break;
                }
            }
        }
        return filtered;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (event.isCancelled()) return;
        if (event.getSpawnReason() != CreatureSpawnEvent.SpawnReason.SPAWNER) {
            return;
        }
        if (!Skills.FIGHTING.isEnabled() && !Skills.ARCHERY.isEnabled()) {
            return;
        }
        // Don't mark if multiplier is default
        if (Skills.FIGHTING.optionDouble("spawner_multiplier", 1) == 1.0 && Skills.ARCHERY.optionDouble("spawner_multiplier", 1) == 1.0) {
            return;
        }
        LivingEntity entity = event.getEntity();
        PersistentDataContainer data = entity.getPersistentDataContainer();
        data.set(spawnerMobKey, PersistentDataType.INTEGER, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobTransform(EntityTransformEvent event) {
        if (!(event.getTransformReason() == EntityTransformEvent.TransformReason.SPLIT || event.getTransformReason() == EntityTransformEvent.TransformReason.DROWNED)) return;

        Entity original = event.getEntity();
        // Ignore entities that aren't spawner mobs
        if (!original.getPersistentDataContainer().has(spawnerMobKey, PersistentDataType.INTEGER)) return;

        // Apply key to split entities
        for (Entity entity : event.getTransformedEntities()) {
            entity.getPersistentDataContainer().set(spawnerMobKey, PersistentDataType.INTEGER, 1);
        }
    }

    private double getSpawnerMultiplier(Entity entity, Skill skill) {
        if (isSpawnerSpawned(entity)) {
            return skill.optionDouble("spawner_multiplier", 1.0);
        } else {
            return 1.0;
        }
    }

    private boolean isSpawnerSpawned(Entity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        if (container.has(spawnerMobKey, PersistentDataType.INTEGER)) {
            return true;
        } else return container.has(roseStackerSpawner, PersistentDataType.INTEGER);
    }

    @Nullable
    private Player getBleedDamager(Entity entity) {
        PersistentDataContainer container = entity.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(plugin, FightingAbilities.BLEED_DAMAGER_KEY);
        if (container.has(key, PersistentDataType.STRING)) { // Handle damager from Bleed
            String uuidStr = container.get(key, PersistentDataType.STRING);
            if (uuidStr == null) return null;

            UUID uuid = UUID.fromString(uuidStr);
            return Bukkit.getPlayer(uuid);
        } else {
            return null;
        }
    }

    private double getDamageMultiplier(LivingEntity entity, EntityXpSource source, EntityDamageEvent event) {
        double damageDealt = Math.min(entity.getHealth(), event.getFinalDamage());
        if (source.scaleXpWithHealth()) {
            AttributeInstance healthAttribute = entity.getAttribute(AttributeCompat.maxHealth);
            if (healthAttribute != null) {
                double maxHealth = healthAttribute.getValue();
                return damageDealt / maxHealth; // XP gain is damage/maxHealth * sourceXp
            }
        }
        return damageDealt;
    }

    private double getLevelledMobsMultiplier(LivingEntity entity) {
        if (plugin.getHookManager().isRegistered(LevelledMobsHook.class)) {
            return plugin.getHookManager().getHook(LevelledMobsHook.class).getXpMultiplier(entity);
        }
        return 1.0;
    }

}
