package dev.aurelium.auraskills.bukkit.source;

import dev.aurelium.auraskills.paper.util.PaperCombatUtil;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CombatXpMathTest {

    @Test
    void capDamageLimitsToRemainingHealth() {
        assertEquals(5.0, CombatXpMath.capDamage(20.0, 5.0), 1e-9);
        assertEquals(3.0, CombatXpMath.capDamage(3.0, 5.0), 1e-9);
        assertEquals(0.0, CombatXpMath.capDamage(3.0, 0.0), 1e-9);
        assertEquals(0.0, CombatXpMath.capDamage(-1.0, 5.0), 1e-9);
    }

    @Test
    void xpRatioIsProportionalToLegitDamage() {
        // 正規 40、不正 40、環境 20 → 正規の割合は 40%
        assertEquals(0.4, CombatXpMath.xpRatio(40, 40, 40, 20), 1e-9);
        // 正規だけなら 100%
        assertEquals(1.0, CombatXpMath.xpRatio(100, 100, 0, 0), 1e-9);
        // 複数人: 40 と 60 が正規のみ → それぞれ 0.4 と 0.6
        assertEquals(0.4, CombatXpMath.xpRatio(40, 100, 0, 0), 1e-9);
        assertEquals(0.6, CombatXpMath.xpRatio(60, 100, 0, 0), 1e-9);
    }

    @Test
    void zeroDenominatorGivesZero() {
        assertEquals(0.0, CombatXpMath.xpRatio(0, 0, 0, 0), 1e-9);
        assertEquals(0.0, CombatXpMath.reductionFactor(0, 0, 0), 1e-9);
        assertEquals(0.0, CombatXpMath.shareRatio(5, 0), 1e-9);
    }

    @Test
    void thornsNeutralDamageIsNotPartOfDenominator() {
        // 中立ダメージは引数に入れない（呼び出し側が分母へ足さない）ので、比率は変わらない
        assertEquals(CombatXpMath.xpRatio(50, 50, 0, 0), 1.0, 1e-9);
    }

    @Test
    void reductionFactorExcludesSharing() {
        assertEquals(0.5, CombatXpMath.reductionFactor(50, 25, 25), 1e-9);
        assertEquals(1.0, CombatXpMath.reductionFactor(50, 0, 0), 1e-9);
    }

    @Test
    void outOfRangePlayersStayInDenominator() {
        // 範囲内の人 50、範囲外（遠く・オフライン）の人 50。分母は記録全体の合計 100 なので、範囲内の人は 50%
        double totalAll = 50 + 50;
        double ratio = CombatXpMath.shareRatio(50, totalAll) * CombatXpMath.reductionFactor(totalAll, 0, 0);
        assertEquals(0.5, ratio, 1e-9);
        // 範囲内の合計（50）を分母にした誤った計算なら満額になってしまう
        assertEquals(1.0, CombatXpMath.shareRatio(50, 50), 1e-9);
        assertNotEquals(1.0, ratio, 1e-9);
        // 範囲内 40、範囲外 40、不正 20 → 範囲内の人は 40÷100 = 40%（xpRatio と一致）
        double mixed = CombatXpMath.shareRatio(40, 80) * CombatXpMath.reductionFactor(80, 20, 0);
        assertEquals(0.4, mixed, 1e-9);
        assertEquals(CombatXpMath.xpRatio(40, 80, 20, 0), mixed, 1e-9);
        // 範囲内の人と範囲外の人の割合の和が 1 を超えない（範囲外の分は誰にも配らない）
        double outside = CombatXpMath.shareRatio(40, 80) * CombatXpMath.reductionFactor(80, 20, 0);
        assertEquals(0.8, mixed + outside, 1e-9);
    }

    @Test
    void fallbackKillerOnlyWhenRecordIsCompletelyEmpty() {
        assertTrue(CombatXpMath.useFallbackKiller(0, 0, 0));
        // 範囲外の人の正規ダメージがあるなら使わない
        assertFalse(CombatXpMath.useFallbackKiller(30, 0, 0));
        assertFalse(CombatXpMath.useFallbackKiller(0, 5, 0));
        assertFalse(CombatXpMath.useFallbackKiller(0, 0, 5));
    }

    @Test
    void thornsOnlyKillRequiresEmptyRecordAndThornsCause() {
        assertTrue(CombatXpMath.isThornsOnlyKill(0, 0, 0, true));
        // 死因が棘でなければ対象外（撃破者の代用で経験値が入る）
        assertFalse(CombatXpMath.isThornsOnlyKill(0, 0, 0, false));
        // 正規・不正・環境のダメージがあるなら対象外
        assertFalse(CombatXpMath.isThornsOnlyKill(30, 0, 0, true));
        assertFalse(CombatXpMath.isThornsOnlyKill(0, 5, 0, true));
        assertFalse(CombatXpMath.isThornsOnlyKill(0, 0, 5, true));
    }

    @Test
    void staleRecordIsDetectedAfterTtl() {
        long ttl = 5 * 60_000L;
        assertFalse(CombatXpMath.isStale(1000, 1000 + ttl, ttl));
        assertTrue(CombatXpMath.isStale(1000, 1001 + ttl, ttl));
        assertFalse(CombatXpMath.isStale(1000, 2000, ttl));
    }

    @Test
    void displayPercentRoundsAndClamps() {
        assertEquals(-1, CombatXpMath.displayPercent(1.0));
        assertEquals(-1, CombatXpMath.displayPercent(0.996));
        assertEquals(99, CombatXpMath.displayPercent(0.99));
        assertEquals(1, CombatXpMath.displayPercent(0.001));
        assertEquals(40, CombatXpMath.displayPercent(0.4));
        assertEquals(0, CombatXpMath.displayPercent(0.0));
    }

    @Test
    void stationaryWindowExpiresAfterThirtySeconds() {
        long window = CombatXpMath.STATIONARY_WINDOW_MILLIS;
        assertEquals(30_000L, window);
        assertFalse(CombatXpMath.isStationaryWindowExpired(1000, 1000 + window, window));
        assertTrue(CombatXpMath.isStationaryWindowExpired(1000, 1001 + window, window));
        assertFalse(CombatXpMath.isStationaryWindowExpired(1000, 5000, window));
    }

    @Test
    void pickReasonPrefersZeroThenLargest() {
        Map<XpReductionReason, Double> w = new EnumMap<>(XpReductionReason.class);
        w.put(XpReductionReason.ENVIRONMENT, 30.0);
        w.put(XpReductionReason.NO_SIGHT, 10.0);
        assertEquals(XpReductionReason.ENVIRONMENT, CombatXpMath.pickReason(w, XpReductionReason::isZero));

        w.put(XpReductionReason.AFK_POSITION, 1.0);
        assertEquals(XpReductionReason.AFK_POSITION, CombatXpMath.pickReason(w, XpReductionReason::isZero));

        assertNull(CombatXpMath.pickReason(new EnumMap<>(XpReductionReason.class), XpReductionReason::isZero));
    }

    @Test
    void rateLimiterAllowsOncePerThreeSeconds() {
        XpNoticeRateLimiter limiter = new XpNoticeRateLimiter(3000);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(limiter.tryAcquire(a, "X", 0));
        assertFalse(limiter.tryAcquire(a, "X", 2999));
        assertTrue(limiter.tryAcquire(a, "Y", 100));   // 別の理由は別枠
        assertTrue(limiter.tryAcquire(b, "X", 100));   // 別のプレイヤーは別枠
        assertTrue(limiter.tryAcquire(a, "X", 3000));
    }

    @Test
    void ceilingOffsetIsOneLessThanCeilingOfHeight() {
        assertEquals(2, PaperCombatUtil.maxCeilingOffset(2.9));  // エンダーマン: +1,+2 まで
        assertEquals(2, PaperCombatUtil.maxCeilingOffset(2.7));  // アイアンゴーレム
        assertEquals(3, PaperCombatUtil.maxCeilingOffset(3.1));
        assertEquals(1, PaperCombatUtil.maxCeilingOffset(2.0));
    }

    @Test
    void partySelfBonusAndInflationCapWorkCorrectly() {
        assertEquals(1.0, CombatXpMath.partySelfBonusMultiplier(0, 0.05, 0.15), 1e-9);
        assertEquals(1.10, CombatXpMath.partySelfBonusMultiplier(2, 0.05, 0.15), 1e-9);
        assertEquals(1.15, CombatXpMath.partySelfBonusMultiplier(5, 0.05, 0.15), 1e-9);

        // 2人の周囲メンバー -> 1人あたり 0.35 (合計 0.70 <= 1.05)
        assertEquals(0.35, CombatXpMath.partySharedXpRatioPerMember(2, 0.35, 1.05), 1e-9);
        // 5人の周囲メンバー -> 上限 1.05 / 5 = 0.21
        assertEquals(0.21, CombatXpMath.partySharedXpRatioPerMember(5, 0.35, 1.05), 1e-9);
    }

    @Test
    void combatPartyBonusAndEqualDamageSplitWorkCorrectly() {
        assertEquals(1.0, CombatXpMath.combatPartyBonusMultiplier(1, 0.10, 0.30), 1e-9);
        assertEquals(1.20, CombatXpMath.combatPartyBonusMultiplier(3, 0.10, 0.30), 1e-9);
        assertEquals(1.30, CombatXpMath.combatPartyBonusMultiplier(6, 0.10, 0.30), 1e-9);

        // 2人パーティーで Aが100dmg, Bが0dmg, partyTotalShare=1.10, equalWeight=0.50
        // A: 1.10 * (0.25 + 0.50 * 1.0) = 0.825
        // B: 1.10 * (0.25 + 0.50 * 0.0) = 0.275
        double shareA = CombatXpMath.combatPartyMemberShare(1.10, 2, 100.0, 100.0, 0.50);
        double shareB = CombatXpMath.combatPartyMemberShare(1.10, 2, 0.0, 100.0, 0.50);
        assertEquals(0.825, shareA, 1e-9);
        assertEquals(0.275, shareB, 1e-9);
        assertEquals(1.10, shareA + shareB, 1e-9);
    }
}
