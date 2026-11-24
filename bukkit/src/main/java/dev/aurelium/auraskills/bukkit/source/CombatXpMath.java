package dev.aurelium.auraskills.bukkit.source;

import java.util.Map;

/**
 * モブ戦闘の経験値配分に使う純粋な計算。Bukkit に依存しない（単体試験の対象）。
 */
public final class CombatXpMath {

    /** 連続撃破の数え直しまでの時間（ミリ秒）。前回数えた撃破からこれを超えたら数え直す。 */
    public static final long STATIONARY_WINDOW_MILLIS = 30_000L;

    private CombatXpMath() {
    }

    /** ダメージを、その時点の残りHPで頭打ちにする（過剰ダメージを数えない）。 */
    public static double capDamage(double damage, double remainingHealth) {
        if (damage <= 0.0 || remainingHealth <= 0.0) return 0.0;
        return Math.min(damage, remainingHealth);
    }

    /** 配分の分母。正規合計 + 不正 + 環境（中立は含めない）。 */
    public static double denominator(double legitTotal, double illegal, double environment) {
        return legitTotal + illegal + environment;
    }

    /** プレイヤーの経験値の割合 = 正規ダメージ ÷ 分母。分母が0なら0。 */
    public static double xpRatio(double playerLegit, double legitTotal, double illegal, double environment) {
        double denom = denominator(legitTotal, illegal, environment);
        if (denom <= 0.0 || playerLegit <= 0.0) return 0.0;
        return playerLegit / denom;
    }

    /** 山分けだけを見た割合（不正・環境による減額を含まない）。 */
    public static double shareRatio(double playerLegit, double legitTotal) {
        if (legitTotal <= 0.0 || playerLegit <= 0.0) return 0.0;
        return playerLegit / legitTotal;
    }

    /** 不正・環境による減額後の割合（0〜1）。複数人の山分けは含まない。分母が0なら0。 */
    public static double reductionFactor(double legitTotal, double illegal, double environment) {
        double denom = denominator(legitTotal, illegal, environment);
        if (denom <= 0.0 || legitTotal <= 0.0) return 0.0;
        return legitTotal / denom;
    }

    /**
     * 表示するパーセント。四捨五入し、0より大きければ最小1。100なら -1（表示しない）。
     * 0以下は 0（「経験値なし」表示）。
     */
    public static int displayPercent(double factor) {
        if (factor <= 0.0) return 0;
        long pct = Math.round(factor * 100.0);
        if (pct >= 100) return -1;
        return (int) Math.max(1, pct);
    }

    /**
     * 撃破者（entity.getKiller()）を代わりに使ってよいか。記録全体（範囲外・オフラインの人も含む）に
     * 正規・不正・環境のダメージが一切無い時だけ true。範囲外の人の正規ダメージがあるなら false。
     */
    public static boolean useFallbackKiller(double legitTotalAll, double illegal, double environment) {
        return legitTotalAll <= 0.0 && illegal + environment <= 0.0;
    }

    /**
     * 棘の鎧などの中立のダメージだけで倒され、誰にも経験値が配られない撃破か（理由の通知を出す対象）。
     * 記録に正規・不正・環境のダメージが無く、最後の死因が棘（THORNS）の時だけ true。
     */
    public static boolean isThornsOnlyKill(double legitTotalAll, double illegal, double environment, boolean lastCauseThorns) {
        return lastCauseThorns && useFallbackKiller(legitTotalAll, illegal, environment);
    }

    /** 記録が古いか（最後の更新から ttl を超えたら true）。 */
    public static boolean isStale(long lastUpdateMillis, long nowMillis, long ttlMillis) {
        return nowMillis - lastUpdateMillis > ttlMillis;
    }

    /** 連続撃破の数え直し判定。前回数えた時刻から窓を超えていたら true。 */
    public static boolean isStationaryWindowExpired(long lastCountedMillis, long nowMillis, long windowMillis) {
        return nowMillis - lastCountedMillis > windowMillis;
    }

    /**
     * 理由の選択。0になる理由があればそれを優先し、なければ重み（ダメージ量）が最大のもの1つ。
     * 重みが全て0以下なら null。
     */
    public static <R extends Enum<R>> R pickReason(Map<R, Double> weights, java.util.function.Predicate<R> isZero) {
        R best = null;
        double bestWeight = 0.0;
        for (Map.Entry<R, Double> e : weights.entrySet()) {
            double w = e.getValue();
            if (w <= 0.0) continue;
            if (best == null) {
                best = e.getKey();
                bestWeight = w;
                continue;
            }
            boolean bestZero = isZero.test(best);
            boolean curZero = isZero.test(e.getKey());
            if (curZero && !bestZero || (curZero == bestZero && w > bestWeight)) {
                best = e.getKey();
                bestWeight = w;
            }
        }
        return best;
    }

    /**
     * 一般スキルのパーティー本人ボーナス倍率を計算する。
     * 例: nearbyOtherCount=2, bonusPerMember=0.05, maxBonus=0.15 -> 1.10
     */
    public static double partySelfBonusMultiplier(int nearbyOtherCount, double bonusPerMember, double maxBonus) {
        if (nearbyOtherCount <= 0 || bonusPerMember <= 0.0 || maxBonus <= 0.0) return 1.0;
        double bonus = Math.min(maxBonus, bonusPerMember * nearbyOtherCount);
        return 1.0 + Math.max(0.0, bonus);
    }

    /**
     * 一般スキルの周囲メンバー1人あたりへのXP共有率を計算する（大人数インフレ防止キャップ付き）。
     * 例: nearbyOtherCount=5, shareRatio=0.35, maxTotalShareRatio=1.05 -> 1.05 / 5 = 0.21
     */
    public static double partySharedXpRatioPerMember(int nearbyOtherCount, double shareRatio, double maxTotalShareRatio) {
        if (nearbyOtherCount <= 0 || shareRatio <= 0.0) return 0.0;
        if (maxTotalShareRatio <= 0.0) return shareRatio;
        return Math.min(shareRatio, maxTotalShareRatio / nearbyOtherCount);
    }

    /**
     * 戦闘共闘時のパーティー全体ボーナス倍率を計算する。
     * nearbyPartyMembers が 2人以上なら、1人追加ごとに bonusPerMember 加算（上限 maxBonus）。
     */
    public static double combatPartyBonusMultiplier(int nearbyPartyMembers, double bonusPerMember, double maxBonus) {
        if (nearbyPartyMembers <= 1 || bonusPerMember <= 0.0 || maxBonus <= 0.0) return 1.0;
        double bonus = Math.min(maxBonus, bonusPerMember * (nearbyPartyMembers - 1));
        return 1.0 + Math.max(0.0, bonus);
    }

    /**
     * 戦闘共闘時のパーティー内メンバー1人の配分割合を計算する。
     * partyTotalShare * ((equalWeight / partyMemberCount) + ((1 - equalWeight) * (memberDamage / partyTotalDamage)))
     */
    public static double combatPartyMemberShare(
            double partyTotalShare,
            int partyMemberCount,
            double memberDamage,
            double partyTotalDamage,
            double equalWeight
    ) {
        if (partyTotalShare <= 0.0 || partyMemberCount <= 0) return 0.0;
        double clampedEqual = Math.max(0.0, Math.min(1.0, equalWeight));
        double equalPart = clampedEqual / partyMemberCount;
        double damagePart = partyTotalDamage > 0.0
                ? (1.0 - clampedEqual) * (Math.max(0.0, memberDamage) / partyTotalDamage)
                : (1.0 - clampedEqual) / partyMemberCount;
        return partyTotalShare * (equalPart + damagePart);
    }
}
