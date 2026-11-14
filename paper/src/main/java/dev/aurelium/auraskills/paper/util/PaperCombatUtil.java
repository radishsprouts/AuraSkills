package dev.aurelium.auraskills.paper.util;

import com.destroystokyo.paper.entity.Pathfinder;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;

public class PaperCombatUtil {

    /**
     * Determines whether a ground-based mob can physically reach and fight the player.
     * Evaluates space clearances (ceiling height, passage width for large mobs) and pathfinding.
     *
     * @param mob    The attacking or defender mob
     * @param player The target player
     * @return true if the mob can pathfind or directly engage the player, false if obstructed/trapped
     */
    public static boolean canMobReachPlayer(Mob mob, Player player) {
        if (!PaperUtil.IS_PAPER) {
            // Fallback for non-Paper environments: simple vertical and direct distance check
            Location mobLoc = mob.getLocation();
            Location playerLoc = player.getLocation();
            double dy = playerLoc.getY() - mobLoc.getY();
            return !(dy > 2.2) && !(dy < -2.5);
        }

        Location playerLoc = player.getLocation();
        Location groundLoc = getGroundLocation(playerLoc);
        if (groundLoc == null) {
            // Player is airborne or on a high pillar without solid ground directly beneath
            return false;
        }

        // 1. Check for big mob restriction (low ceiling or tight passage where mob cannot fit)
        if (isMobTooBigForPlayerSpace(mob, player, groundLoc)) {
            return false;
        }

        // 2. Paper Pathfinder check
        try {
            Pathfinder pathfinder = mob.getPathfinder();
            Pathfinder.PathResult path = pathfinder.findPath(groundLoc);
            if (path == null) {
                return false;
            }

            Location finalPoint = path.getFinalPoint();
            if (finalPoint == null) {
                return false;
            }

            double dx = finalPoint.getX() - groundLoc.getX();
            double dz = finalPoint.getZ() - groundLoc.getZ();
            double horizontalDistSq = dx * dx + dz * dz;

            // Final path point must be within 2.5 blocks horizontally of target
            if (horizontalDistSq > 2.5 * 2.5) {
                return false;
            }

            // Vertical height difference must be within 1.5 blocks
            double dy = Math.abs(finalPoint.getY() - groundLoc.getY());
            if (dy > 1.5) {
                return false;
            }

            return true;
        } catch (Throwable t) {
            // In case of any pathfinding exception, default to direct distance sanity check
            double dy = playerLoc.getY() - mob.getLocation().getY();
            return !(dy > 2.2) && !(dy < -2.5);
        }
    }

    /**
     * Finds the solid ground location directly below the player (within 1.5 blocks).
     */
    public static Location getGroundLocation(Location playerLoc) {
        Block feetBlock = playerLoc.getBlock();
        if (isPassable(feetBlock)) {
            Block below1 = feetBlock.getRelative(0, -1, 0);
            if (below1.getType().isSolid()) {
                return below1.getLocation().add(0.5, 1.0, 0.5);
            }
            Block below2 = feetBlock.getRelative(0, -2, 0);
            if (below2.getType().isSolid()) {
                double dist = playerLoc.getY() - (below2.getY() + 1.0);
                if (dist <= 1.5) {
                    return below2.getLocation().add(0.5, 1.0, 0.5);
                }
            }
            // Check if player is standing on slab/carpet/chest etc.
            if (feetBlock.getY() - playerLoc.getY() <= 0.5 && below1.getType().isSolid()) {
                return below1.getLocation().add(0.5, 1.0, 0.5);
            }
            return null; // Airborne
        } else {
            return feetBlock.getLocation().add(0.5, 0.0, 0.5);
        }
    }

    /**
     * Checks if a large mob (like Iron Golem, Enderman, Ravager, Warden) cannot fit into the player's space.
     */
    private static boolean isMobTooBigForPlayerSpace(Mob mob, Player player, Location groundLoc) {
        double mobHeight = mob.getHeight();
        double mobWidth = mob.getWidth();

        // If mob height > 2.3 blocks (e.g. Enderman 2.9, Iron Golem 2.7, Warden 2.9), check vertical clearance above player
        if (mobHeight > 2.3) {
            Block groundBlock = groundLoc.getBlock();
            int requiredHeight = (int) Math.ceil(mobHeight);
            for (int y = 1; y <= requiredHeight; y++) {
                Block checkBlock = groundBlock.getRelative(0, y, 0);
                if (checkBlock.getType().isSolid()) {
                    // Solid ceiling blocks the large mob from entering player's space
                    return true;
                }
            }
        }

        // If mob width > 1.0 block (e.g. Iron Golem 1.4, Ravager 1.95), check if player is in a 1-block wide trench/passage
        if (mobWidth > 1.0) {
            Block groundBlock = groundLoc.getBlock();
            boolean northSolid = groundBlock.getRelative(0, 1, -1).getType().isSolid();
            boolean southSolid = groundBlock.getRelative(0, 1, 1).getType().isSolid();
            boolean eastSolid = groundBlock.getRelative(1, 1, 0).getType().isSolid();
            boolean westSolid = groundBlock.getRelative(-1, 1, 0).getType().isSolid();

            if ((northSolid && southSolid) || (eastSolid && westSolid)) {
                // Player is inside a 1-wide choke point where wide mob cannot physically fit
                return true;
            }
        }

        return false;
    }

    private static boolean isPassable(Block block) {
        Material type = block.getType();
        return type.isAir() || type == Material.WATER || type == Material.LAVA || !type.isSolid();
    }
}
