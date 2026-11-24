package dev.aurelium.auraskills.common.party;

import dev.aurelium.auraskills.api.party.Party;
import dev.aurelium.auraskills.api.party.PartyManager;
import dev.aurelium.auraskills.common.AuraSkillsPlugin;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.scheduler.Task;
import dev.aurelium.auraskills.common.scheduler.TaskRunnable;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

public class PartyManagerImpl implements PartyManager {

    public interface PartyConfig {
        boolean enabled();
        int maxSize();
        int inviteExpireSeconds();
        int offlineGraceSeconds();
        boolean defaultFriendlyFirePrevent();
    }

    private static final long CLEANUP_INTERVAL_SECONDS = 10L;

    private final AuraSkillsPlugin plugin;
    private final PartyConfig config;
    private final LongSupplier clock;
    private final Object stateLock = new Object();

    private final ConcurrentHashMap<UUID, PartyImpl> partiesById = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, UUID> partyByPlayer = new ConcurrentHashMap<>();
    // key: target player UUID -> (partyId -> PartyInvite)
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<UUID, PartyInvite>> pendingInvites = new ConcurrentHashMap<>();
    private final Set<UUID> inviteBlockedPlayers = ConcurrentHashMap.newKeySet();

    private volatile PartyNotifier notifier = PartyNotifier.NO_OP;
    private Task cleanupTask;

    public PartyManagerImpl(AuraSkillsPlugin plugin) {
        this(plugin, new PartyConfig() {
            @Override public boolean enabled() { return plugin.configBoolean(Option.PARTY_ENABLED); }
            @Override public int maxSize() { return plugin.configInt(Option.PARTY_MAX_SIZE); }
            @Override public int inviteExpireSeconds() { return plugin.configInt(Option.PARTY_INVITE_EXPIRE_SECONDS); }
            @Override public int offlineGraceSeconds() { return plugin.configInt(Option.PARTY_OFFLINE_GRACE_SECONDS); }
            @Override public boolean defaultFriendlyFirePrevent() { return plugin.configBoolean(Option.PARTY_FRIENDLY_FIRE_DEFAULT_PREVENT); }
        }, System::currentTimeMillis);
    }

    public PartyManagerImpl(PartyConfig config, LongSupplier clock) {
        this(null, config, clock);
    }

    private PartyManagerImpl(@Nullable AuraSkillsPlugin plugin, PartyConfig config, LongSupplier clock) {
        this.plugin = plugin;
        this.config = config;
        this.clock = clock;
    }

    public void setNotifier(@Nullable PartyNotifier notifier) {
        this.notifier = notifier != null ? notifier : PartyNotifier.NO_OP;
    }

    public void start() {
        if (plugin == null) return;
        stop();
        cleanupTask = plugin.getScheduler().timerAsync(new TaskRunnable() {
            @Override
            public void run() {
                tickCleanup();
            }
        }, CLEANUP_INTERVAL_SECONDS, CLEANUP_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void stop() {
        if (cleanupTask != null) {
            cleanupTask.cancel();
            cleanupTask = null;
        }
    }

    public void clearAll() {
        synchronized (stateLock) {
            partiesById.clear();
            partyByPlayer.clear();
            pendingInvites.clear();
            inviteBlockedPlayers.clear();
        }
    }

    public boolean isEnabled() {
        return config.enabled();
    }

    @Override
    @Nullable
    public Party getParty(UUID playerId) {
        return getPartyImpl(playerId);
    }

    @Nullable
    public PartyImpl getPartyImpl(UUID playerId) {
        if (playerId == null) return null;
        UUID partyId = partyByPlayer.get(playerId);
        return partyId != null ? partiesById.get(partyId) : null;
    }

    @Nullable
    public PartyImpl getPartyById(UUID partyId) {
        return partyId != null ? partiesById.get(partyId) : null;
    }

    @Override
    public boolean isInParty(UUID playerId) {
        return getPartyImpl(playerId) != null;
    }

    @Override
    public boolean isInSameParty(UUID playerA, UUID playerB) {
        if (playerA == null || playerB == null) return false;
        UUID partyA = partyByPlayer.get(playerA);
        return partyA != null && partyA.equals(partyByPlayer.get(playerB));
    }

    @Override
    public Set<UUID> getPartyMembers(UUID playerId) {
        PartyImpl party = getPartyImpl(playerId);
        return party != null ? party.getMembers() : Collections.emptySet();
    }

    @Override
    public int getMaxPartySize() {
        return Math.max(0, config.maxSize());
    }

    public boolean isInviteBlocked(UUID playerId) {
        return inviteBlockedPlayers.contains(playerId);
    }

    public boolean toggleInviteBlock(UUID playerId) {
        if (inviteBlockedPlayers.contains(playerId)) {
            inviteBlockedPlayers.remove(playerId);
            return false;
        } else {
            inviteBlockedPlayers.add(playerId);
            return true;
        }
    }

    public PartyActionResult createParty(UUID leaderId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        synchronized (stateLock) {
            if (partyByPlayer.containsKey(leaderId)) {
                return PartyActionResult.ALREADY_IN_PARTY;
            }
            UUID partyId = UUID.randomUUID();
            boolean defaultFf = config.defaultFriendlyFirePrevent();
            PartyImpl party = new PartyImpl(partyId, leaderId, defaultFf, false, clock.getAsLong());
            partiesById.put(partyId, party);
            partyByPlayer.put(leaderId, partyId);
            return PartyActionResult.SUCCESS;
        }
    }

    public PartyActionResult invitePlayer(UUID inviterId, UUID targetId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;
        if (inviterId.equals(targetId)) return PartyActionResult.CANNOT_TARGET_SELF;

        PartyImpl partyToNotify;
        synchronized (stateLock) {
            PartyImpl party = getPartyImpl(inviterId);
            if (party == null) {
                // Auto-create party on invite for convenience
                PartyActionResult createRes = createParty(inviterId);
                if (createRes != PartyActionResult.SUCCESS) {
                    return createRes;
                }
                party = getPartyImpl(inviterId);
            }
            if (party == null) return PartyActionResult.NOT_IN_PARTY;

            if (!party.isLeader(inviterId) && !party.isMemberInviteAllowed()) {
                return PartyActionResult.NO_INVITE_PERMISSION;
            }
            if (partyByPlayer.containsKey(targetId)) {
                return PartyActionResult.TARGET_ALREADY_IN_PARTY;
            }
            if (inviteBlockedPlayers.contains(targetId)) {
                return PartyActionResult.TARGET_INVITE_BLOCKED;
            }
            int maxSize = getMaxPartySize();
            if (maxSize > 0 && party.getSize() >= maxSize) {
                return PartyActionResult.PARTY_FULL;
            }

            long now = clock.getAsLong();
            long expireMillis = Math.max(5, config.inviteExpireSeconds()) * 1000L;
            ConcurrentHashMap<UUID, PartyInvite> targetInvites = pendingInvites.computeIfAbsent(targetId, k -> new ConcurrentHashMap<>());
            PartyInvite existing = targetInvites.get(party.getId());
            if (existing != null && !existing.isExpired(now)) {
                return PartyActionResult.ALREADY_INVITED;
            }
            targetInvites.put(party.getId(), new PartyInvite(party.getId(), inviterId, targetId, now + expireMillis));
            partyToNotify = party;
        }

        notifier.onInviteSent(inviterId, targetId, partyToNotify);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult acceptInvite(UUID targetId, @Nullable UUID partyIdOrInviterId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        PartyImpl joinedParty;
        synchronized (stateLock) {
            if (partyByPlayer.containsKey(targetId)) {
                return PartyActionResult.ALREADY_IN_PARTY;
            }
            ConcurrentHashMap<UUID, PartyInvite> targetInvites = pendingInvites.get(targetId);
            if (targetInvites == null || targetInvites.isEmpty()) {
                return PartyActionResult.NO_PENDING_INVITE;
            }

            PartyInvite invite = findInvite(targetInvites, partyIdOrInviterId);
            if (invite == null) {
                return PartyActionResult.NO_PENDING_INVITE;
            }
            if (invite.isExpired(clock.getAsLong())) {
                targetInvites.remove(invite.partyId());
                return PartyActionResult.INVITE_EXPIRED;
            }

            PartyImpl party = partiesById.get(invite.partyId());
            if (party == null) {
                targetInvites.remove(invite.partyId());
                return PartyActionResult.NO_PENDING_INVITE;
            }

            int maxSize = getMaxPartySize();
            if (maxSize > 0 && party.getSize() >= maxSize) {
                return PartyActionResult.PARTY_FULL;
            }

            party.addMember(targetId);
            partyByPlayer.put(targetId, party.getId());
            pendingInvites.remove(targetId);
            joinedParty = party;
        }

        notifier.onMemberJoined(joinedParty, targetId);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult denyInvite(UUID targetId, @Nullable UUID partyIdOrInviterId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        synchronized (stateLock) {
            ConcurrentHashMap<UUID, PartyInvite> targetInvites = pendingInvites.get(targetId);
            if (targetInvites == null || targetInvites.isEmpty()) {
                return PartyActionResult.NO_PENDING_INVITE;
            }
            PartyInvite invite = findInvite(targetInvites, partyIdOrInviterId);
            if (invite == null) {
                return PartyActionResult.NO_PENDING_INVITE;
            }
            targetInvites.remove(invite.partyId());
            if (targetInvites.isEmpty()) {
                pendingInvites.remove(targetId);
            }
            return PartyActionResult.SUCCESS;
        }
    }

    @Nullable
    private PartyInvite findInvite(Map<UUID, PartyInvite> invites, @Nullable UUID partyIdOrInviterId) {
        if (partyIdOrInviterId == null) {
            PartyInvite latest = null;
            for (PartyInvite inv : invites.values()) {
                if (latest == null || inv.expiresAtMillis() > latest.expiresAtMillis()) {
                    latest = inv;
                }
            }
            return latest;
        }
        PartyInvite byParty = invites.get(partyIdOrInviterId);
        if (byParty != null) return byParty;
        for (PartyInvite inv : invites.values()) {
            if (inv.inviterId().equals(partyIdOrInviterId)) {
                return inv;
            }
        }
        return null;
    }

    public List<PartyInvite> getPendingInvites(UUID targetId) {
        ConcurrentHashMap<UUID, PartyInvite> map = pendingInvites.get(targetId);
        if (map == null || map.isEmpty()) return Collections.emptyList();
        long now = clock.getAsLong();
        List<PartyInvite> list = new ArrayList<>();
        for (PartyInvite inv : map.values()) {
            if (!inv.isExpired(now) && partiesById.containsKey(inv.partyId())) {
                list.add(inv);
            }
        }
        list.sort(Comparator.comparingLong(PartyInvite::expiresAtMillis).reversed());
        return list;
    }

    public boolean hasPendingInviteFromParty(UUID targetId, UUID partyId) {
        ConcurrentHashMap<UUID, PartyInvite> map = pendingInvites.get(targetId);
        if (map == null) return false;
        PartyInvite inv = map.get(partyId);
        return inv != null && !inv.isExpired(clock.getAsLong());
    }

    public PartyActionResult leaveParty(UUID playerId) {
        return removeMemberInternal(playerId, false);
    }

    public PartyActionResult kickMember(UUID actorId, UUID targetId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;
        if (actorId.equals(targetId)) return PartyActionResult.CANNOT_TARGET_SELF;

        PartyImpl party;
        synchronized (stateLock) {
            party = getPartyImpl(actorId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;
            if (!party.isLeader(actorId)) return PartyActionResult.NOT_LEADER;
            if (!party.isMember(targetId)) return PartyActionResult.TARGET_NOT_IN_PARTY;
            if (party.isLeader(targetId)) return PartyActionResult.CANNOT_KICK_LEADER;

            party.removeMember(targetId);
            partyByPlayer.remove(targetId);
        }

        notifier.onMemberKicked(party, targetId);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult promoteLeader(UUID actorId, UUID targetId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;
        if (actorId.equals(targetId)) return PartyActionResult.CANNOT_TARGET_SELF;

        PartyImpl party;
        synchronized (stateLock) {
            party = getPartyImpl(actorId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;
            if (!party.isLeader(actorId)) return PartyActionResult.NOT_LEADER;
            if (!party.isMember(targetId)) return PartyActionResult.TARGET_NOT_IN_PARTY;

            party.setLeader(targetId);
        }

        notifier.onLeaderPromoted(party, targetId, false);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult disbandParty(UUID actorId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        Set<UUID> formerMembers;
        synchronized (stateLock) {
            PartyImpl party = getPartyImpl(actorId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;
            if (!party.isLeader(actorId)) return PartyActionResult.NOT_LEADER;

            formerMembers = party.getMembers();
            disbandInternal(party);
        }

        notifier.onPartyDisbanded(formerMembers, actorId);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult toggleFriendlyFire(UUID actorId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        PartyImpl party;
        boolean newState;
        synchronized (stateLock) {
            party = getPartyImpl(actorId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;
            if (!party.isLeader(actorId)) return PartyActionResult.NOT_LEADER;

            newState = !party.isFriendlyFireProtected();
            party.setFriendlyFireProtected(newState);
        }

        notifier.onFriendlyFireToggled(party, newState);
        return PartyActionResult.SUCCESS;
    }

    public PartyActionResult toggleMemberInvite(UUID actorId) {
        if (!isEnabled()) return PartyActionResult.DISABLED;

        PartyImpl party;
        boolean newState;
        synchronized (stateLock) {
            party = getPartyImpl(actorId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;
            if (!party.isLeader(actorId)) return PartyActionResult.NOT_LEADER;

            newState = !party.isMemberInviteAllowed();
            party.setMemberInviteAllowed(newState);
        }

        notifier.onMemberInviteToggled(party, newState);
        return PartyActionResult.SUCCESS;
    }

    public void onPlayerJoin(UUID playerId) {
        synchronized (stateLock) {
            PartyImpl party = getPartyImpl(playerId);
            if (party != null) {
                party.markOnline(playerId);
            }
        }
    }

    public void onPlayerQuit(UUID playerId) {
        synchronized (stateLock) {
            PartyImpl party = getPartyImpl(playerId);
            if (party != null) {
                party.markOffline(playerId, clock.getAsLong());
            }
        }
    }

    public void tickCleanup() {
        long now = clock.getAsLong();
        long graceMillis = Math.max(0, config.offlineGraceSeconds()) * 1000L;

        List<UUID> timedOutMembers = new ArrayList<>();
        synchronized (stateLock) {
            // 1. Purge expired invites
            Iterator<Map.Entry<UUID, ConcurrentHashMap<UUID, PartyInvite>>> invIt = pendingInvites.entrySet().iterator();
            while (invIt.hasNext()) {
                Map.Entry<UUID, ConcurrentHashMap<UUID, PartyInvite>> entry = invIt.next();
                entry.getValue().values().removeIf(inv -> inv.isExpired(now) || !partiesById.containsKey(inv.partyId()));
                if (entry.getValue().isEmpty()) {
                    invIt.remove();
                }
            }

            // 2. Collect members exceeding offline grace period
            for (PartyImpl party : partiesById.values()) {
                for (Map.Entry<UUID, Long> offlineEntry : party.getOfflineSnapshot().entrySet()) {
                    if (now - offlineEntry.getValue() >= graceMillis) {
                        timedOutMembers.add(offlineEntry.getKey());
                    }
                }
            }
        }

        for (UUID timedOutId : timedOutMembers) {
            removeMemberInternal(timedOutId, true);
        }
    }

    private PartyActionResult removeMemberInternal(UUID playerId, boolean timedOut) {
        PartyImpl party;
        boolean disbanded = false;
        Set<UUID> formerMembers = Collections.emptySet();
        UUID formerLeader = null;
        UUID autoPromotedLeader = null;

        synchronized (stateLock) {
            party = getPartyImpl(playerId);
            if (party == null) return PartyActionResult.NOT_IN_PARTY;

            boolean wasLeader = party.isLeader(playerId);
            formerMembers = party.getMembers();
            formerLeader = party.getLeader();

            party.removeMember(playerId);
            partyByPlayer.remove(playerId);

            if (party.getSize() == 0) {
                disbandInternal(party);
                disbanded = true;
            } else if (wasLeader) {
                UUID nextLeader = selectNextLeader(party);
                if (nextLeader != null) {
                    party.setLeader(nextLeader);
                    autoPromotedLeader = nextLeader;
                } else {
                    disbandInternal(party);
                    disbanded = true;
                }
            }
        }

        if (disbanded) {
            notifier.onPartyDisbanded(formerMembers, formerLeader);
        } else {
            notifier.onMemberLeft(party, playerId, timedOut);
            if (autoPromotedLeader != null) {
                notifier.onLeaderPromoted(party, autoPromotedLeader, true);
            }
        }
        return PartyActionResult.SUCCESS;
    }

    @Nullable
    private UUID selectNextLeader(PartyImpl party) {
        UUID fallback = null;
        for (UUID memberId : party.getMembers()) {
            if (fallback == null) {
                fallback = memberId;
            }
            if (!party.isOffline(memberId)) {
                return memberId;
            }
        }
        return fallback;
    }

    private void disbandInternal(PartyImpl party) {
        partiesById.remove(party.getId());
        for (UUID memberId : party.getMembers()) {
            partyByPlayer.remove(memberId, party.getId());
        }
        for (ConcurrentHashMap<UUID, PartyInvite> map : pendingInvites.values()) {
            map.remove(party.getId());
        }
    }

}
