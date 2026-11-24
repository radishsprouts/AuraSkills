package dev.aurelium.auraskills.common.party;

import java.util.Collection;
import java.util.UUID;

/**
 * Platform-independent notification hook for party lifecycle events.
 * Implementations on Bukkit/Folia must dispatch player actions safely on entity schedulers.
 */
public interface PartyNotifier {

    PartyNotifier NO_OP = new PartyNotifier() {};

    default void onInviteSent(UUID inviterId, UUID targetId, PartyImpl party) {}

    default void onMemberJoined(PartyImpl party, UUID joinedPlayerId) {}

    default void onMemberLeft(PartyImpl party, UUID leftPlayerId, boolean timedOut) {}

    default void onMemberKicked(PartyImpl party, UUID kickedPlayerId) {}

    default void onLeaderPromoted(PartyImpl party, UUID newLeaderId, boolean autoPromoted) {}

    default void onPartyDisbanded(Collection<UUID> formerMembers, UUID leaderId) {}

    default void onFriendlyFireToggled(PartyImpl party, boolean protectedState) {}

    default void onMemberInviteToggled(PartyImpl party, boolean allowed) {}

}
