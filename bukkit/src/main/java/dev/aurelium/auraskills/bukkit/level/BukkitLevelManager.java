package dev.aurelium.auraskills.bukkit.level;

import com.google.common.collect.Sets;
import dev.aurelium.auraskills.api.event.skill.DamageXpGainEvent;
import dev.aurelium.auraskills.api.event.skill.EntityXpGainEvent;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.source.XpSource;
import dev.aurelium.auraskills.api.source.type.DamageXpSource.DamageCause;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.source.*;
import dev.aurelium.auraskills.bukkit.user.BukkitUser;
import dev.aurelium.auraskills.bukkit.util.SoundUtil;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.level.LevelManager;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.paper.util.PaperCombatUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class BukkitLevelManager extends LevelManager {

    private final AuraSkills plugin;
    private final Set<SourceLeveler> levelers;
    private final MobCombatTracker mobCombatTracker;
    private final XpReductionNotifier xpReductionNotifier;

    public BukkitLevelManager(AuraSkills plugin) {
        super(plugin);
        this.plugin = plugin;
        this.levelers = Sets.newConcurrentHashSet();
        this.mobCombatTracker = new MobCombatTracker(plugin);
        this.xpReductionNotifier = new XpReductionNotifier(plugin);
    }

    public XpReductionNotifier getXpReductionNotifier() {
        return xpReductionNotifier;
    }

    public MobCombatTracker getMobCombatTracker() {
        return mobCombatTracker;
    }

    public void loadXpRequirements() {
        XpRequirementsLoader loader = new XpRequirementsLoader(plugin, xpRequirements);
        loader.load();
    }

    public void registerLevelers() {
        levelers.clear();
        mobCombatTracker.start();
        Bukkit.getPluginManager().registerEvents(mobCombatTracker, plugin);
        registerLeveler(new AnvilLeveler(plugin));
        registerLeveler(new BlockLeveler(plugin));
        registerLeveler(new BrewingLeveler(plugin));
        registerLeveler(new DamageLeveler(plugin));
        registerLeveler(new EnchantingLeveler(plugin));
        registerLeveler(new EntityLeveler(plugin));
        registerLeveler(new FishingLeveler(plugin));
        registerLeveler(new GrindstoneLeveler(plugin));
        registerLeveler(new ItemConsumeLeveler(plugin));
        registerLeveler(new JumpingLeveler(plugin));
        registerLeveler(new ManaAbilityUseLeveler(plugin));
        registerLeveler(new PotionSplashLeveler(plugin));
        registerLeveler(new StatisticLeveler(plugin));
    }

    private void registerLeveler(SourceLeveler leveler) {
        this.levelers.add(leveler);
        Bukkit.getPluginManager().registerEvents(leveler, plugin);
    }

    @SuppressWarnings("unchecked")
    public <T extends SourceLeveler> T getLeveler(Class<T> levelerClass) {
        for (SourceLeveler leveler : levelers) {
            if (levelerClass.isInstance(leveler)) {
                return (T) leveler;
            }
        }
        // No leveler found
        throw new IllegalArgumentException("Leveler " + levelerClass.getSimpleName() + " is not registered!");
    }

    /**
     * @return 経験値を実際に付与したら true（量が0・イベントが取り消された場合は false）
     */
    public boolean addEntityXp(User user, Skill skill, @NotNull XpSource source, double amount,
            LivingEntity attacked, Entity damager, EntityEvent originalEvent) {
        if (amount == 0) return false; // Ignore if source amount is 0

        double amountToAdd = amount * calculateMultiplier(user, skill);

        EntityXpGainEvent event = new EntityXpGainEvent(BukkitUser.getPlayer(user.toApi()), user.toApi(), skill, source, amountToAdd, attacked, damager, originalEvent);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        addXpRaw(user, skill, event.getAmount(), source);
        return true;
    }

    public void addDamageXp(User user, Skill skill, @NotNull XpSource source, double amount,
            DamageCause cause, Entity damager, EntityEvent originalEvent) {
        if (amount == 0) return;

        double amountToAdd = amount * calculateMultiplier(user, skill);

        DamageXpGainEvent event = new DamageXpGainEvent(BukkitUser.getPlayer(user.toApi()), user.toApi(), skill, source, amountToAdd, cause, damager, originalEvent);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        addXpRaw(user, skill, event.getAmount(), source);
    }

    private boolean isPartyXpShareEligible(Skill skill) {
        if (!plugin.configBoolean(Option.PARTY_ENABLED) || !plugin.configBoolean(Option.PARTY_XP_SHARE_ENABLED)) {
            return false;
        }
        String skillName = skill.getId().getKey().toLowerCase(Locale.ROOT);
        for (String excluded : plugin.configStringList(Option.PARTY_XP_SHARE_EXCLUDED_SKILLS)) {
            if (excluded.equalsIgnoreCase(skillName) || excluded.equalsIgnoreCase(skill.getId().toString())) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected double getPartySelfBonusMultiplier(User user, Skill skill) {
        if (!isPartyXpShareEligible(skill)) return 1.0;
        PartyImpl party = plugin.getPartyManager().getPartyImpl(user.getUuid());
        if (party == null || party.getSize() <= 1) return 1.0;
        Player player = ((BukkitUser) user).getPlayer();
        if (player == null || !player.isOnline()) return 1.0;

        Location origin = player.getLocation();
        double radius = plugin.configDouble(Option.PARTY_XP_SHARE_RADIUS);
        double radiusSq = radius * radius;
        int nearbyCount = 0;

        for (UUID memberId : party.getMembers()) {
            if (memberId.equals(user.getUuid())) continue;
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline() || member.isDead()) continue;
            if (PaperCombatUtil.isOwnedByCurrentRegion(member)) {
                Location memberLoc = member.getLocation();
                if (Objects.equals(origin.getWorld(), memberLoc.getWorld()) && origin.distanceSquared(memberLoc) <= radiusSq) {
                    nearbyCount++;
                }
            }
        }

        return CombatXpMath.partySelfBonusMultiplier(
                nearbyCount,
                plugin.configDouble(Option.PARTY_XP_SHARE_BONUS_PER_MEMBER),
                plugin.configDouble(Option.PARTY_XP_SHARE_MAX_BONUS));
    }

    @Override
    protected void sharePartyXp(User user, Skill skill, @Nullable XpSource source, double baseAmount) {
        if (baseAmount <= 0.0 || !isPartyXpShareEligible(skill)) return;
        PartyImpl party = plugin.getPartyManager().getPartyImpl(user.getUuid());
        if (party == null || party.getSize() <= 1) return;

        Player earner = ((BukkitUser) user).getPlayer();
        if (earner == null || !earner.isOnline()) return;

        Location origin = earner.getLocation().clone();
        double radius = plugin.configDouble(Option.PARTY_XP_SHARE_RADIUS);
        double radiusSq = radius * radius;

        List<Player> candidates = new ArrayList<>();
        for (UUID memberId : party.getMembers()) {
            if (memberId.equals(user.getUuid())) continue;
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline() || member.isDead()) continue;
            if (PaperCombatUtil.isOwnedByCurrentRegion(member)) {
                Location memberLoc = member.getLocation();
                if (!Objects.equals(origin.getWorld(), memberLoc.getWorld()) || origin.distanceSquared(memberLoc) > radiusSq) {
                    continue;
                }
            }
            candidates.add(member);
        }
        if (candidates.isEmpty()) return;

        double perMemberRatio = CombatXpMath.partySharedXpRatioPerMember(
                candidates.size(),
                plugin.configDouble(Option.PARTY_XP_SHARE_RATIO),
                plugin.configDouble(Option.PARTY_XP_SHARE_MAX_TOTAL_RATIO));
        double sharedAmount = baseAmount * perMemberRatio;
        if (sharedAmount <= 0.0) return;

        for (Player member : candidates) {
            if (PaperCombatUtil.isOwnedByCurrentRegion(member)) {
                grantSharedXp(member, origin, radiusSq, skill, source, sharedAmount);
            } else {
                plugin.getScheduler().executeAtEntity(member, task ->
                        grantSharedXp(member, origin, radiusSq, skill, source, sharedAmount));
            }
        }
    }

    private void grantSharedXp(Player member, Location origin, double radiusSq, Skill skill, @Nullable XpSource source, double sharedAmount) {
        if (!member.isOnline() || member.isDead()) return;
        Location memberLoc = member.getLocation();
        if (!Objects.equals(origin.getWorld(), memberLoc.getWorld()) || origin.distanceSquared(memberLoc) > radiusSq) {
            return;
        }
        if (plugin.getWorldManager().isInDisabledWorld(memberLoc)) return;
        if (!member.hasPermission("auraskills.skill." + skill.getId().getKey())) return;

        User memberUser = plugin.getUser(member);
        isSharingPartyXp.set(true);
        try {
            addXp(memberUser, skill, source, sharedAmount);
        } finally {
            isSharingPartyXp.set(false);
        }
    }

    @Override
    public void playLevelUpSound(@NotNull User user) {
        Player player = ((BukkitUser) user).getPlayer();
        if (player == null) return;
        String soundName = plugin.configString(Option.LEVELER_SOUND_TYPE);
        try {
            player.playSound(player.getLocation(), SoundUtil.getFromEitherName(soundName),
                    SoundCategory.valueOf(plugin.configString(Option.LEVELER_SOUND_CATEGORY)),
                    (float) plugin.configDouble(Option.LEVELER_SOUND_VOLUME), (float) plugin.configDouble(Option.LEVELER_SOUND_PITCH));
        } catch (Exception e) {
            plugin.logger().warn("Error playing level up sound: " + soundName + ", played the default sound instead");
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.MASTER, 1f, 0.5f);
        }
    }

}
