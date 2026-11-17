package dev.aurelium.auraskills.paper.util;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
        return evaluateReach(mob, player).reachable();
    }

    /** 到達判定の結果。detail は、落ちた条件の説明（判定ログ用）。 */
    public record ReachResult(boolean reachable, String detail) {
    }

    /**
     * canMobReachPlayer の本体。どの条件で落ちたかを detail に入れて返す。
     * 判定できない瞬間（モブが地面に立っていない等）は「届かない」にせず到達可能として返す。
     */
    public static ReachResult evaluateReach(Mob mob, Player player) {
        Location playerLoc = player.getLocation();
        Location mobLoc = mob.getLocation();
        if (!PaperUtil.IS_PAPER) {
            double dy = playerLoc.getY() - mobLoc.getY();
            return new ReachResult(!(dy > 2.2) && !(dy < -2.5), "non-paper dy=" + fmt(dy));
        }

        // 0. モブがその場でプレイヤーを殴れる（近接攻撃の範囲内で、視線が通る）なら、地形に関係なく届く
        if (canMeleeNow(mob, player)) {
            return new ReachResult(true, "近接攻撃の範囲内");
        }

        Location groundLoc = getGroundLocation(playerLoc);
        if (groundLoc == null) {
            if (isInWater(playerLoc)) {
                // 水中・水面のプレイヤーには地面が無いので、地面の代わりにプレイヤーの位置を目標にする
                groundLoc = playerLoc;
            } else {
                // Player is airborne or on a high pillar without solid ground directly beneath
                return new ReachResult(false, "ground=null(プレイヤー足元に地面なし) playerY=" + fmt(playerLoc.getY()));
            }
        }

        // 1. Check for big mob restriction (low ceiling or tight passage where mob cannot fit)
        if (isMobTooBigForPlayerSpace(mob, player, groundLoc)) {
            return new ReachResult(false, "大型モブ制限(天井または狭い通路) h=" + fmt(mob.getHeight()) + " w=" + fmt(mob.getWidth()));
        }

        // 2. 経路探索は、モブが地面・液体・乗り物のどれにも居ない瞬間（ノックバック・落下中）は結果を返さない。
        //    その瞬間は判定できないので「届かない」とは決めつけない
        boolean onGround = mob.isOnGround();
        boolean inLiquid = mob.isInWater() || isInLava(mob);
        if (!onGround && !inLiquid && !mob.isInsideVehicle()) {
            return new ReachResult(true, "判定不能(モブが接地していない)");
        }

        // 3. Paper Pathfinder check
        String pathDetail;
        try {
            Pathfinder pathfinder = mob.getPathfinder();
            Pathfinder.PathResult path = pathfinder.findPath(groundLoc);
            if (path == null) {
                pathDetail = "path=null";
            } else {
                Location finalPoint = path.getFinalPoint();
                if (finalPoint == null) {
                    pathDetail = "finalPoint=null";
                } else {
                    // 終点はブロックの位置なので、ブロックの中心で比べる（目標もブロックの中心）
                    double endX = finalPoint.getBlockX() + 0.5;
                    double endY = finalPoint.getBlockY();
                    double endZ = finalPoint.getBlockZ() + 0.5;
                    double horizontalDist = Math.hypot(endX - groundLoc.getX(), endZ - groundLoc.getZ());
                    double dy = Math.abs(endY - groundLoc.getY());
                    String endText = " 終点=" + fmt(endX) + "," + fmt(endY) + "," + fmt(endZ);
                    if (dy <= ReachMath.PATH_VERTICAL && horizontalDist <= ReachMath.PATH_ARRIVED_HORIZONTAL) {
                        // プレイヤーのブロックか、その隣まで行ける
                        return new ReachResult(true, "path ok");
                    }
                    if (dy <= ReachMath.PATH_VERTICAL && horizontalDist <= ReachMath.PATH_NEAR_HORIZONTAL) {
                        // 少し手前で止まった。残りが塞がれていない平地なら届く。柵・壁・ガラス板などで
                        // 仕切られている（モブが柵に押し付けられて止まっている）なら届かない
                        World w = groundLoc.getWorld();
                        boolean gapOpen = w != null && ReachMath.isFlatWalkable(
                                (x, y, z) -> w.getBlockAt(x, y, z).getType().isSolid(),
                                endX, endY, endZ,
                                groundLoc.getX(), groundLoc.getY(), groundLoc.getZ(),
                                (int) Math.ceil(mob.getHeight()));
                        if (gapOpen) {
                            return new ReachResult(true, "path ok(終点の先は平地)");
                        }
                        pathDetail = "終点の先が塞がれている(柵・壁など) 水平=" + fmt(horizontalDist)
                                + " 高低差=" + fmt(dy) + endText;
                    } else {
                        pathDetail = "終点が遠い 水平=" + fmt(horizontalDist) + " 高低差=" + fmt(dy) + endText;
                    }
                }
            }
        } catch (Throwable t) {
            // In case of any pathfinding exception, default to direct distance sanity check
            double dy = playerLoc.getY() - mobLoc.getY();
            return new ReachResult(!(dy > 2.2) && !(dy < -2.5),
                    "経路探索で例外 " + t.getClass().getSimpleName() + " dy=" + fmt(dy));
        }

        // 4. 経路探索が「届かない」と言っても、同じ高さの途切れない平地を障害物なしで歩いて行けるなら届く
        //    （モブが動けない・探索が打ち切られた場合の誤判定を避ける。穴・段差・壁・低い天井は通れないと判定する）
        World world = mobLoc.getWorld();
        if (world != null && world.equals(groundLoc.getWorld())) {
            boolean walkable = ReachMath.isFlatWalkable(
                    (x, y, z) -> world.getBlockAt(x, y, z).getType().isSolid(),
                    mobLoc.getX(), mobLoc.getY(), mobLoc.getZ(),
                    groundLoc.getX(), groundLoc.getY(), groundLoc.getZ(),
                    (int) Math.ceil(mob.getHeight()));
            if (walkable) {
                return new ReachResult(true, "経路探索は失敗(" + pathDetail + ")だが平地を直進できる");
            }
        }
        double hd = Math.hypot(groundLoc.getX() - mobLoc.getX(), groundLoc.getZ() - mobLoc.getZ());
        return new ReachResult(false, pathDetail + " 接地=" + onGround + " 液体=" + inLiquid
                + " モブ→目標 水平=" + fmt(hd) + " 高低差=" + fmt(groundLoc.getY() - mobLoc.getY()));
    }

    /** モブが今この場でプレイヤーを殴れるか（バニラの近接攻撃と同じ範囲と、視線が通ること）。 */
    private static boolean canMeleeNow(Mob mob, Player player) {
        if (!mob.getWorld().equals(player.getWorld())) return false;
        BoundingBox mb = mob.isInsideVehicle() && mob.getVehicle() != null
                ? mob.getVehicle().getBoundingBox() : mob.getBoundingBox();
        BoundingBox pb = player.getBoundingBox();
        boolean inRange = ReachMath.isWithinMeleeRange(
                new double[]{mb.getMinX(), mb.getMinY(), mb.getMinZ(), mb.getMaxX(), mb.getMaxY(), mb.getMaxZ()},
                new double[]{pb.getMinX(), pb.getMinY(), pb.getMinZ(), pb.getMaxX(), pb.getMaxY(), pb.getMaxZ()});
        return inRange && mob.hasLineOfSight(player);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.1f", v);
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
