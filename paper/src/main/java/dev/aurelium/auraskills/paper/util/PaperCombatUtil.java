package dev.aurelium.auraskills.paper.util;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.UUID;
import java.util.function.Consumer;

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
            if (isInWater(playerLoc)) {
                // 水中・水面のプレイヤーには地面が無いので、地面の代わりにプレイヤーの位置を目標にする
                groundLoc = playerLoc;
            } else {
                // Player is airborne or on a high pillar without solid ground directly beneath
                return false;
            }
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
            int maxOffset = maxCeilingOffset(mobHeight);
            for (int y = 1; y <= maxOffset; y++) {
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

    /**
     * 高さ mobHeight のモブが立つのに必要な天井の確認範囲の上端（足元からのブロック数）。
     * 足元のブロック（+0）にモブが立ち、+1 から +(ceil(height)-1) までが空いている必要がある
     * （例: 高さ2.9なら +1・+2 まで。+3 は要求しない）。
     */
    public static int maxCeilingOffset(double mobHeight) {
        return Math.max(0, (int) Math.ceil(mobHeight) - 1);
    }

    /** プレイヤーの足元（足のブロック）が水・泡の柱か。 */
    public static boolean isInWater(Location playerLoc) {
        Material type = playerLoc.getBlock().getType();
        return type == Material.WATER || type == Material.BUBBLE_COLUMN;
    }

    private static boolean isPassable(Block block) {
        Material type = block.getType();
        return type.isAir() || type == Material.WATER || type == Material.LAVA || !type.isSolid();
    }

    /**
     * 現在のスレッド（Folia ではリージョンのスレッド）が、そのエンティティを触ってよいか。
     * Folia 以外（単一のメインスレッド）では常に true。
     */
    public static boolean isOwnedByCurrentRegion(org.bukkit.entity.Entity entity) {
        if (!PaperUtil.IS_PAPER) return true;
        return Bukkit.isOwnedByCurrentRegion(entity);
    }

    /** エンティティが溶岩に触れているか（足元または目の位置のブロックが溶岩）。 */
    public static boolean isInLava(org.bukkit.entity.LivingEntity entity) {
        return entity.getLocation().getBlock().getType() == Material.LAVA
                || entity.getEyeLocation().getBlock().getType() == Material.LAVA;
    }

    /**
     * モブがワールドから取り除かれた時に、そのモブの UUID を渡すリスナーを登録する（Paper の EntityRemoveFromWorldEvent）。
     * Paper 以外、またはイベントが無い版では何もせず false を返す。
     *
     * @return 登録できたら true
     */
    public static boolean registerEntityRemoveListener(Plugin plugin, Consumer<UUID> onRemove) {
        if (!PaperUtil.IS_PAPER) return false;
        try {
            Bukkit.getPluginManager().registerEvents(new EntityRemoveListener(onRemove), plugin);
            return true;
        } catch (LinkageError e) {
            return false;
        }
    }

    private static final class EntityRemoveListener implements Listener {
        private final Consumer<UUID> onRemove;

        private EntityRemoveListener(Consumer<UUID> onRemove) {
            this.onRemove = onRemove;
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onRemoveFromWorld(EntityRemoveFromWorldEvent event) {
            onRemove.accept(event.getEntity().getUniqueId());
        }
    }
}
