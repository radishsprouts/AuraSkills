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
    void bigHeightDifferenceOrFarIsNotWalkable() {
        assertFalse(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 3.5, 66, 0.5, 2));
        assertFalse(ReachMath.isFlatWalkable(FLAT, 0.5, 64, 0.5, 40.5, 64, 0.5, 2));
    }
}
