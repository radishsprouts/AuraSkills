package dev.aurelium.auraskills.bukkit.util;

import dev.aurelium.auraskills.paper.util.ReachMath;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReachMathTest {

    /** y=63 までが固体の平地。 */
    private static final ReachMath.Terrain FLAT = (x, y, z) -> y <= 63;

    @Test
    void openFlatGroundIsWalkable() {
        assertTrue(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 6.5, 64, 0.5, 2));
        assertTrue(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 3.5, 64, 3.5, 2));
    }

    @Test
    void pitBetweenIsNotWalkable() {
        ReachMath.Terrain pit = (x, y, z) -> y <= 63 && !(x == 3 && z == 0);
        assertFalse(ReachMath.isFlatWalkable(pit, 0.5, 64, 0.5, 6.5, 64, 0.5, 2));
    }

    @Test
    void wallBetweenIsNotWalkable() {
        ReachMath.Terrain wall = (x, y, z) -> y <= 63 || (x == 3 && z == 0 && y <= 65);
        assertFalse(ReachMath.isFlatWalkable(wall, 0.5, 64, 0.5, 6.5, 64, 0.5, 2));
    }

    @Test
    void lowCeilingBlocksTallMobOnly() {
        ReachMath.Terrain low = (x, y, z) -> y <= 63 || (x == 3 && y == 65);
        assertFalse(ReachMath.isFlatWalkable(low, 0.5, 64, 0.5, 6.5, 64, 0.5, 2));
        assertTrue(ReachMath.isFlatWalkable(low, 0.5, 64, 0.5, 6.5, 64, 0.5, 1));
    }

    @Test
    void fenceBetweenPathEndAndPlayerIsNotWalkable() {
        // 柵の列 x=1 の手前（x=0）で経路が止まり、プレイヤーは柵の向こう x=2 に立つ（終点から水平 2）
        ReachMath.Terrain fence = (x, y, z) -> y <= 63 || (x == 1 && y == 64);
        assertFalse(ReachMath.isFlatWalkable(fence, 0.5, 64, 0.5, 2.5, 64, 0.5, 2));
        // 柵が無ければ、同じ距離でも届く
        assertTrue(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 2.5, 64, 0.5, 2));
    }

    /** 幅 w・高さ h の当たり判定（足元の中心が x, y, z）。 */
    private static double[] box(double x, double y, double z, double w, double h) {
        return new double[]{x - w / 2, y, z - w / 2, x + w / 2, y + h, z + w / 2};
    }

    @Test
    void meleeRangeAcrossFenceWhenAdjacent() {
        // 柵（x=1 のブロック、太さ 0.25）に押し付けられたゾンビと、柵の反対側に密着したプレイヤー
        double[] zombie = box(1.5 - 0.125 - 0.3, 64, 0.5, 0.6, 1.95);
        double[] player = box(1.5 + 0.125 + 0.3, 64, 0.5, 0.6, 1.8);
        assertTrue(ReachMath.isWithinMeleeRange(zombie, player));
    }

    @Test
    void meleeRangeFailsTwoBlocksBehindFence() {
        // 柵に押し付けられたゾンビと、柵から 2 ブロック下がって弓を撃つプレイヤー
        double[] zombie = box(1.5 - 0.125 - 0.3, 64, 0.5, 0.6, 1.95);
        double[] player = box(3.5, 64, 0.5, 0.6, 1.8);
        assertFalse(ReachMath.isWithinMeleeRange(zombie, player));
    }

    @Test
    void meleeRangeNeedsVerticalOverlap() {
        // 2 ブロックの穴の底のゾンビは、穴の縁に立つプレイヤーに届かない
        double[] zombie = box(0.5, 62, 0.5, 0.6, 1.95);
        double[] player = box(1.5, 64, 0.5, 0.6, 1.8);
        assertFalse(ReachMath.isWithinMeleeRange(zombie, player));
        // 同じ高さなら届く
        assertTrue(ReachMath.isWithinMeleeRange(box(0.5, 64, 0.5, 0.6, 1.95), player));
    }

    @Test
    void bigHeightDifferenceOrFarIsNotWalkable() {
        assertFalse(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 3.5, 66, 0.5, 2));
        assertFalse(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 40.5, 64, 0.5, 2));
    }
}
