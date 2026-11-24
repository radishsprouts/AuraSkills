package dev.aurelium.auraskills.common.party;

import dev.aurelium.auraskills.api.party.Party;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class PartyImpl implements Party {

    private final UUID id;
    private volatile UUID leader;
    private final CopyOnWriteArraySet<UUID> members = new CopyOnWriteArraySet<>();
    private final ConcurrentHashMap<UUID, Long> offlineSince = new ConcurrentHashMap<>();
    private final long createdAtMillis;
    private volatile boolean friendlyFireProtected;
    private volatile boolean memberInviteAllowed;

    public PartyImpl(UUID id, UUID leader, boolean friendlyFireProtected, boolean memberInviteAllowed, long createdAtMillis) {
        this.id = id;
        this.leader = leader;
        this.friendlyFireProtected = friendlyFireProtected;
        this.memberInviteAllowed = memberInviteAllowed;
        this.createdAtMillis = createdAtMillis;
        this.members.add(leader);
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public UUID getLeader() {
        return leader;
    }

    public void setLeader(UUID leader) {
        this.leader = leader;
    }

    @Override
    public Set<UUID> getMembers() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(members));
    }

    public boolean addMember(UUID playerId) {
        offlineSince.remove(playerId);
        return members.add(playerId);
    }

    public boolean removeMember(UUID playerId) {
        offlineSince.remove(playerId);
        return members.remove(playerId);
    }

    @Override
    public boolean isMember(UUID playerId) {
        return members.contains(playerId);
    }

    @Override
    public boolean isLeader(UUID playerId) {
        return leader.equals(playerId);
    }

    @Override
    public boolean isFriendlyFireProtected() {
        return friendlyFireProtected;
    }

    public void setFriendlyFireProtected(boolean friendlyFireProtected) {
        this.friendlyFireProtected = friendlyFireProtected;
    }

    @Override
    public boolean isMemberInviteAllowed() {
        return memberInviteAllowed;
    }

    public void setMemberInviteAllowed(boolean memberInviteAllowed) {
        this.memberInviteAllowed = memberInviteAllowed;
    }

    @Override
    public int getSize() {
        return members.size();
    }

    @Override
    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public void markOffline(UUID playerId, long timestampMillis) {
        if (members.contains(playerId)) {
            offlineSince.putIfAbsent(playerId, timestampMillis);
        }
    }

    public void markOnline(UUID playerId) {
        offlineSince.remove(playerId);
    }

    public boolean isOffline(UUID playerId) {
        return offlineSince.containsKey(playerId);
    }

    @Nullable
    public Long getOfflineSince(UUID playerId) {
        return offlineSince.get(playerId);
    }

    public Map<UUID, Long> getOfflineSnapshot() {
        return Map.copyOf(offlineSince);
    }

}
