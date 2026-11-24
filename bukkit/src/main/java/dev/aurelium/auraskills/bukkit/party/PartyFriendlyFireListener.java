package dev.aurelium.auraskills.bukkit.party;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.config.Option;
import dev.aurelium.auraskills.common.party.PartyImpl;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.AreaEffectCloudApplyEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PotionSplashEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;

public class PartyFriendlyFireListener implements Listener {

    private static final Set<PotionEffectType> HARMFUL_EFFECTS = Set.of(
            PotionEffectType.POISON,
            PotionEffectType.WITHER,
            PotionEffectType.INSTANT_DAMAGE,
            PotionEffectType.SLOWNESS,
            PotionEffectType.WEAKNESS,
            PotionEffectType.BLINDNESS,
            PotionEffectType.HUNGER,
            PotionEffectType.MINING_FATIGUE,
            PotionEffectType.LEVITATION,
            PotionEffectType.NAUSEA,
            PotionEffectType.DARKNESS,
            PotionEffectType.BAD_OMEN
    );

    private final AuraSkills plugin;

    public PartyFriendlyFireListener(AuraSkills plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!plugin.configBoolean(Option.PARTY_ENABLED)) return;

        UUID attackerOwner = resolveAttackerOwner(event.getDamager());
        if (attackerOwner == null) return;

        UUID victimOwner = resolveVictimOwner(event.getEntity());
        if (victimOwner == null || attackerOwner.equals(victimOwner)) return;

        if (isFriendlyFireProtected(attackerOwner, victimOwner)) {
            event.setCancelled(true);
            if (event.getDamager() instanceof Projectile projectile) {
                projectile.remove();
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPotionSplash(PotionSplashEvent event) {
        if (!plugin.configBoolean(Option.PARTY_ENABLED)) return;
        if (!(event.getPotion().getShooter() instanceof Player thrower)) return;
        if (!hasHarmfulEffect(event.getPotion().getEffects())) return;

        UUID throwerId = thrower.getUniqueId();
        for (LivingEntity affected : event.getAffectedEntities()) {
            UUID victimOwner = resolveVictimOwner(affected);
            if (victimOwner != null && !throwerId.equals(victimOwner) && isFriendlyFireProtected(throwerId, victimOwner)) {
                event.setIntensity(affected, 0.0);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAreaEffectCloudApply(AreaEffectCloudApplyEvent event) {
        if (!plugin.configBoolean(Option.PARTY_ENABLED)) return;
        AreaEffectCloud cloud = event.getEntity();
        if (!(cloud.getSource() instanceof Player sourcePlayer)) return;
        if (!hasHarmfulCloudEffect(cloud)) return;

        UUID sourceId = sourcePlayer.getUniqueId();
        event.getAffectedEntities().removeIf(affected -> {
            UUID victimOwner = resolveVictimOwner(affected);
            return victimOwner != null && !sourceId.equals(victimOwner) && isFriendlyFireProtected(sourceId, victimOwner);
        });
    }

    public static boolean shouldProtectTarget(AuraSkills plugin, Player attacker, Entity target) {
        if (!plugin.configBoolean(Option.PARTY_ENABLED)) return false;
        UUID victimOwner = resolveVictimOwner(target);
        if (victimOwner == null || attacker.getUniqueId().equals(victimOwner)) return false;
        PartyImpl party = plugin.getPartyManager().getPartyImpl(attacker.getUniqueId());
        return party != null && party.isMember(victimOwner) && party.isFriendlyFireProtected();
    }

    public boolean isFriendlyFireProtected(UUID playerA, UUID playerB) {
        PartyImpl party = plugin.getPartyManager().getPartyImpl(playerA);
        return party != null && party.isMember(playerB) && party.isFriendlyFireProtected();
    }

    private boolean hasHarmfulEffect(Iterable<PotionEffect> effects) {
        for (PotionEffect effect : effects) {
            if (HARMFUL_EFFECTS.contains(effect.getType())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHarmfulCloudEffect(AreaEffectCloud cloud) {
        if (hasHarmfulEffect(cloud.getCustomEffects())) {
            return true;
        }
        if (cloud.getBasePotionType() != null) {
            return hasHarmfulEffect(cloud.getBasePotionType().getPotionEffects());
        }
        return false;
    }

    @Nullable
    public static UUID resolveAttackerOwner(Entity damager) {
        if (damager instanceof Player player) {
            return player.getUniqueId();
        }
        if (damager instanceof Projectile projectile) {
            if (projectile.getShooter() instanceof Player shooter) {
                return shooter.getUniqueId();
            }
            if (projectile.getShooter() instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null) {
                return tameable.getOwner().getUniqueId();
            }
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source) {
            return source.getUniqueId();
        }
        if (damager instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null) {
            return tameable.getOwner().getUniqueId();
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source.getUniqueId();
        }
        return null;
    }

    @Nullable
    public static UUID resolveVictimOwner(Entity victim) {
        if (victim instanceof Player player) {
            return player.getUniqueId();
        }
        if (victim instanceof Tameable tameable && tameable.isTamed() && tameable.getOwner() != null) {
            return tameable.getOwner().getUniqueId();
        }
        return null;
    }

}
