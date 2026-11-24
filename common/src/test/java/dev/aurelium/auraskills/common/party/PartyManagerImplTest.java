package dev.aurelium.auraskills.common.party;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class PartyManagerImplTest {

    private PartyManagerImpl newManager(AtomicLong clock, int maxSize) {
        return new PartyManagerImpl(new PartyManagerImpl.PartyConfig() {
            @Override public boolean enabled() { return true; }
            @Override public int maxSize() { return maxSize; }
            @Override public int inviteExpireSeconds() { return 60; }
            @Override public int offlineGraceSeconds() { return 300; }
            @Override public boolean defaultFriendlyFirePrevent() { return true; }
        }, clock::get);
    }

    @Test
    void createInviteAcceptLeaveAndDisbandFlow() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PartyManagerImpl pm = newManager(clock, 0);

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();
        UUID third = UUID.randomUUID();

        assertEquals(PartyActionResult.SUCCESS, pm.createParty(leader));
        assertTrue(pm.isInParty(leader));
        assertEquals(PartyActionResult.ALREADY_IN_PARTY, pm.createParty(leader));

        // 招待と承認
        assertEquals(PartyActionResult.SUCCESS, pm.invitePlayer(leader, member));
        assertEquals(PartyActionResult.ALREADY_INVITED, pm.invitePlayer(leader, member));
        assertEquals(1, pm.getPendingInvites(member).size());

        // デフォルトでは非リーダーの招待は拒否
        assertEquals(PartyActionResult.SUCCESS, pm.acceptInvite(member, leader));
        assertTrue(pm.isInSameParty(leader, member));
        assertEquals(PartyActionResult.NO_INVITE_PERMISSION, pm.invitePlayer(member, third));

        // メンバー招待許可ONなら招待できる
        assertEquals(PartyActionResult.SUCCESS, pm.toggleMemberInvite(leader));
        assertEquals(PartyActionResult.SUCCESS, pm.invitePlayer(member, third));
        assertEquals(PartyActionResult.SUCCESS, pm.acceptInvite(third, null));
        assertEquals(3, pm.getParty(leader).getSize());

        // リーダー委譲と脱退
        assertEquals(PartyActionResult.SUCCESS, pm.promoteLeader(leader, member));
        assertTrue(pm.getParty(leader).isLeader(member));
        assertEquals(PartyActionResult.SUCCESS, pm.leaveParty(leader));
        assertFalse(pm.isInParty(leader));
        assertEquals(2, pm.getParty(member).getSize());

        // 解散
        assertEquals(PartyActionResult.SUCCESS, pm.disbandParty(member));
        assertFalse(pm.isInParty(member));
        assertFalse(pm.isInParty(third));
    }

    @Test
    void inviteBlockExpireAndMaxSizeEnforcement() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PartyManagerImpl pm = newManager(clock, 2);

        UUID leader = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        UUID p3 = UUID.randomUUID();

        pm.createParty(leader);

        // 招待ブロック
        assertTrue(pm.toggleInviteBlock(p2));
        assertEquals(PartyActionResult.TARGET_INVITE_BLOCKED, pm.invitePlayer(leader, p2));
        assertFalse(pm.toggleInviteBlock(p2));

        // 招待期限切れ (60秒)
        assertEquals(PartyActionResult.SUCCESS, pm.invitePlayer(leader, p2));
        clock.addAndGet(61_000L);
        assertEquals(PartyActionResult.INVITE_EXPIRED, pm.acceptInvite(p2, leader));

        // 再招待 -> 参加 -> 上限2人で3人目は招待不可
        assertEquals(PartyActionResult.SUCCESS, pm.invitePlayer(leader, p2));
        assertEquals(PartyActionResult.SUCCESS, pm.acceptInvite(p2, leader));
        assertEquals(PartyActionResult.PARTY_FULL, pm.invitePlayer(leader, p3));
    }

    @Test
    void offlineGraceTimeoutAndAutoPromote() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PartyManagerImpl pm = newManager(clock, 0);

        UUID leader = UUID.randomUUID();
        UUID member = UUID.randomUUID();

        pm.createParty(leader);
        pm.invitePlayer(leader, member);
        pm.acceptInvite(member, leader);

        // リーダーがログアウト -> 200秒経過時点ではまだ在籍
        pm.onPlayerQuit(leader);
        clock.addAndGet(200_000L);
        pm.tickCleanup();
        assertTrue(pm.isInParty(leader));

        // さらに100秒経過（合計300秒）-> 自動離脱＆オンラインメンバーへ自動委譲
        clock.addAndGet(100_000L);
        pm.tickCleanup();
        assertFalse(pm.isInParty(leader));
        assertTrue(pm.isInParty(member));
        assertTrue(pm.getParty(member).isLeader(member));
    }

}
