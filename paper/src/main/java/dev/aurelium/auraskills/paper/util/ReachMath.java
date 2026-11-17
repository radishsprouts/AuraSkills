package dev.aurelium.auraskills.paper.util;

/** 到達判定の純粋な計算（ブロックの有無だけを使う。試験しやすいように Bukkit に依存しない）。 */
public final class ReachMath {

    /** バニラの近接攻撃の届く幅（Mob.DEFAULT_ATTACK_REACH = sqrt(2.04) - 0.6）。当たり判定を水平にこの分だけ広げる。 */
    public static final double MELEE_REACH = Math.sqrt(2.04) - 0.6;

    /** 経路の行き止まりがプレイヤーのブロックか隣（斜めを含む）なら、着いたとみなす水平距離。 */
    public static final double PATH_ARRIVED_HORIZONTAL = 1.5;

    /** 行き止まりがここまで近ければ、残りの区間が塞がれていない時に限り届くとみなす水平距離。 */
    public static final double PATH_NEAR_HORIZONTAL = 2.5;

    /** 行き止まりと目標の高低差の許容。 */
    public static final double PATH_VERTICAL = 1.5;

    private ReachMath() {
    }

    /**
     * モブがその場で目標を殴れる位置にいるか（バニラの MeleeAttackGoal と同じく、モブの当たり判定を
     * 水平に {@link #MELEE_REACH} 広げた箱が、目標の当たり判定と重なるか）。視線は呼び出し側で確かめる。
     * 箱は {minX, minY, minZ, maxX, maxY, maxZ}。
     */
    public static boolean isWithinMeleeRange(double[] mobBox, double[] targetBox) {
        double minX = mobBox[0] - MELEE_REACH, maxX = mobBox[3] + MELEE_REACH;
        double minZ = mobBox[2] - MELEE_REACH, maxZ = mobBox[5] + MELEE_REACH;
        return minX < targetBox[3] && maxX > targetBox[0]
                && mobBox[1] < targetBox[4] && mobBox[4] > targetBox[1]
                && minZ < targetBox[5] && maxZ > targetBox[2];
    }

    /** ブロック (x, y, z) が固体かを返す。 */
    @FunctionalInterface
    public interface Terrain {
        boolean isSolid(int x, int y, int z);
    }

    /**
     * モブの位置から目標まで、同じ高さの平地を障害物なしで歩いて行けるか。
     * 直線上を0.5ブロックごとに調べ、足元の下が固体・モブの背丈の分の空間が空きであることを要求する。
     * 高低差が1ブロックを超える・距離が32を超える場合は false（段差・穴・柱は経路探索に任せる）。
     */
    public static boolean isFlatWalkable(Terrain terrain, double mx, double my, double mz,
                                         double tx, double ty, double tz, int mobHeightBlocks) {
        if (Math.abs(ty - my) > 1.0) return false;
        double dx = tx - mx;
        double dz = tz - mz;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist > 32.0) return false;
        int feetY = (int) Math.floor(my + 0.01);
        int height = Math.max(1, mobHeightBlocks);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.5));
        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            int bx = (int) Math.floor(mx + dx * f);
            int bz = (int) Math.floor(mz + dz * f);
            if (!terrain.isSolid(bx, feetY - 1, bz)) return false;
            for (int h = 0; h < height; h++) {
                if (terrain.isSolid(bx, feetY + h, bz)) return false;
            }
        }
        return true;
    }
}
