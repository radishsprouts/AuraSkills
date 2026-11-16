package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.api.source.type.EntityXpSource;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatRecordTest {

    @Test
    void igniterExpiresAfterBurnDuration() {
        MobCombatTracker.CombatRecord record = new MobCombatTracker.CombatRecord(UUID.randomUUID());
        UUID player = UUID.randomUUID();
        record.setIgniter(player, 10_000L);

        assertEquals(player, record.getIgniter(5_000L));
        assertEquals(player, record.getIgniter(10_000L));
        // 燃焼の長さが過ぎたら、以後の炎のダメージはプレイヤーのものにしない
        assertNull(record.getIgniter(10_001L));
    }

    @Test
    void igniterIsClearedByNonPlayerIgnition() {
        MobCombatTracker.CombatRecord record = new MobCombatTracker.CombatRecord(UUID.randomUUID());
        record.setIgniter(UUID.randomUUID(), 10_000L);
        record.clearIgniter();
        assertNull(record.getIgniter(0L));
    }

    @Test
    void lastDirectReasonIsOverwrittenByLatestDirectAttack() {
        MobCombatTracker.CombatRecord record = new MobCombatTracker.CombatRecord(UUID.randomUUID());
        UUID player = UUID.randomUUID();

        assertNull(record.getLastDirectReason(player));
        record.setLastDirectReason(player, XpReductionReason.HELD_IN_PLACE);
        assertEquals(XpReductionReason.HELD_IN_PLACE, record.getLastDirectReason(player));

        // 正規の直接攻撃があったら最新にする（不正の記録は消える）
        record.setLastDirectReason(player, null);
        assertNull(record.getLastDirectReason(player));

        // 逆に、正規の後に不正な直接攻撃があれば不正が最新になる
        record.setLastDirectReason(player, XpReductionReason.NO_SIGHT);
        assertEquals(XpReductionReason.NO_SIGHT, record.getLastDirectReason(player));
    }

    @Test
    void stationaryPlayerIsOnlyTheKiller() {
        MobCombatTracker.CombatRecord record = new MobCombatTracker.CombatRecord(UUID.randomUUID());
        UUID killer = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        record.addLegitDamage(killer, 10, EntityXpSource.EntityDamagers.PLAYER);
        record.addLegitDamage(other, 10, EntityXpSource.EntityDamagers.PLAYER);
        record.setStationaryPlayer(killer);

        assertEquals(killer, record.getStationaryPlayer());
        // モブ単位の印（tainted）にはならない。他の参加者は比例配分のまま
        assertFalse(record.isTainted());
        assertNull(record.getTrapReason());
    }
}
