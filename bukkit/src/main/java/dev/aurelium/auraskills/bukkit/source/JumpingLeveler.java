package dev.aurelium.auraskills.bukkit.source;

import com.google.common.collect.Sets;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.source.type.JumpingXpSource;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.util.CompatUtil;
import dev.aurelium.auraskills.common.source.SourceTypes;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class JumpingLeveler extends SourceLeveler {

    private final Set<UUID> prevPlayersOnGround = Sets.newConcurrentHashSet();
    private final Map<UUID, JumpAfkRecord> jumpAfkRecords = new ConcurrentHashMap<>();

    public JumpingLeveler(AuraSkills plugin) {
        super(plugin, SourceTypes.JUMPING);
    }

    @EventHandler
    @SuppressWarnings("deprecation")
    public void onJump(PlayerMoveEvent event) {
        if (disabled()) return;
        Player player = event.getPlayer();

        handleJump(player, event);

        if (player.isOnGround()) {
            prevPlayersOnGround.add(player.getUniqueId());
        } else {
            prevPlayersOnGround.remove(player.getUniqueId());
        }
    }

    @SuppressWarnings("deprecation")
    private void handleJump(Player player, PlayerMoveEvent event) {
        if (player.getVelocity().getY() <= 0) {
            return;
        }

        double jumpVelocity = 0.42F;
        if (CompatUtil.hasEffect(player, Set.of("jump", "jump_boost"))) {
            PotionEffect effect = CompatUtil.getEffect(player, Set.of("jump", "jump_boost"));
            if (effect != null) {
                jumpVelocity += ((float) (effect.getAmplifier() + 1) * 0.1F);
            }
        }
        if (player.getLocation().getBlock().getType() == Material.LADDER || !prevPlayersOnGround.contains(player.getUniqueId())) {
            return;
        }
        if (player.isOnGround() || Double.compare(player.getVelocity().getY(), jumpVelocity) != 0) {
            return;
        }
        var skillSource = plugin.getSkillManager().getSingleSourceOfType(JumpingXpSource.class);
        if (skillSource == null) return;

        // Anti-AFK jump check (e.g. weighted key, constant jumping on the exact same block)
        JumpAfkRecord afkRecord = jumpAfkRecords.computeIfAbsent(player.getUniqueId(), u -> new JumpAfkRecord(player.getLocation()));
        if (afkRecord.recordJump(player.getLocation())) {
            return;
        }

        JumpingXpSource source = skillSource.source();
        Skill skill = skillSource.skill();

        if (player.hasMetadata("skillsJumps")) {
            player.setMetadata("skillsJumps", new FixedMetadataValue(plugin, player.getMetadata("skillsJumps").get(0).asInt() + 1));
            if (player.getMetadata("skillsJumps").get(0).asInt() >= source.getInterval()) {

                if (failsChecks(event, player, player.getLocation(), skill)) return;

                plugin.getLevelManager().addXp(plugin.getUser(player), skill, source, source.getXp());

                player.removeMetadata("skillsJumps", plugin);
            }
        } else {
            player.setMetadata("skillsJumps", new FixedMetadataValue(plugin, 1));
        }
    }

    public static class JumpAfkRecord {
        private Location anchorLocation;
        private float lastYaw;
        private float lastPitch;
        private int stationaryJumps = 0;

        public JumpAfkRecord(Location loc) {
            this.anchorLocation = loc.clone();
            this.lastYaw = loc.getYaw();
            this.lastPitch = loc.getPitch();
        }

        public boolean recordJump(Location loc) {
            boolean sameAnchor = Objects.equals(loc.getWorld(), anchorLocation.getWorld())
                    && loc.distanceSquared(anchorLocation) <= 0.36; // 0.6m horizontal threshold
            float yawDiff = Math.abs(loc.getYaw() - lastYaw);
            if (yawDiff > 180.0f) yawDiff = 360.0f - yawDiff;
            float pitchDiff = Math.abs(loc.getPitch() - lastPitch);
            boolean headMoved = yawDiff > 5.0f || pitchDiff > 3.0f;

            if (sameAnchor && !headMoved) {
                stationaryJumps++;
            } else {
                stationaryJumps = 0;
                anchorLocation = loc.clone();
                lastYaw = loc.getYaw();
                lastPitch = loc.getPitch();
            }

            return stationaryJumps > 15; // Block jump counting if jumped > 15 times on exact spot without looking/moving
        }
    }

}
