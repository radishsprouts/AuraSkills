package dev.aurelium.auraskills.bukkit.skills.excavation;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.damage.DamageType;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.util.ChainBreakHelper;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Random;

public class ExcavationAbilities extends BukkitAbilityImpl {

    private final Random random = new Random();

    public ExcavationAbilities(AuraSkills plugin) {
        super(plugin, Abilities.METAL_DETECTOR, Abilities.EXCAVATOR, Abilities.SPADE_MASTER, Abilities.BIGGER_SCOOP, Abilities.LUCKY_SPADES,
                Abilities.EXCAVATION_SWEEP, Abilities.SIFTER, Abilities.ARCHEOLOGIST, Abilities.EARTH_ARMOR);
    }

    private DamageModifier spadeMaster(Player player, User user) {
        var ability = Abilities.SPADE_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            if (meta.getDamageType() == DamageType.SHOVEL) {
                var user = plugin.getUser(attacker);
                meta.addAttackModifier(spadeMaster(attacker, user));
            }
        }
    }

    private boolean isShovel(Material mat) {
        return mat.name().endsWith("_SHOVEL");
    }

    private boolean isSoftBlock(Material mat) {
        return switch (mat) {
            case DIRT, GRASS_BLOCK, SAND, RED_SAND, GRAVEL, CLAY, SOUL_SAND, SOUL_SOIL,
                 MUD, MUDDY_MANGROVE_ROOTS, COARSE_DIRT, ROOTED_DIRT, PODZOL, MYCELIUM -> true;
            default -> false;
        };
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (!isShovel(hand.getType())) return;

        Block block = event.getBlock();
        Material mat = block.getType();
        if (!isSoftBlock(mat)) return;

        User user = plugin.getUser(player);

        // Excavation Sweep (breaks up to N adjacent soft blocks)
        var sweepAbility = Abilities.EXCAVATION_SWEEP;
        if (!isDisabled(sweepAbility) && !failsChecks(player, sweepAbility)) {
            int maxBlocks = (int) Math.round(getValue(sweepAbility, user));
            if (maxBlocks > 0) {
                ChainBreakHelper.breakConnectedBlocks(plugin, player, block, maxBlocks, b -> isSoftBlock(b.getType()));
            }
        }

        // Sifter (chance when digging gravel/sand/mud for bonus items)
        var sifterAbility = Abilities.SIFTER;
        if (!isDisabled(sifterAbility) && !failsChecks(player, sifterAbility)) {
            if (mat == Material.GRAVEL || mat == Material.SAND || mat == Material.RED_SAND || mat == Material.MUD) {
                double chance = getValue(sifterAbility, user) / 100.0;
                if (random.nextDouble() < chance) {
                    double roll = random.nextDouble();
                    ItemStack bonus = roll < 0.40 ? new ItemStack(Material.FLINT, 1 + random.nextInt(2))
                            : roll < 0.70 ? new ItemStack(Material.CLAY_BALL, 1 + random.nextInt(2))
                            : roll < 0.90 ? new ItemStack(Material.IRON_NUGGET, 1 + random.nextInt(3))
                            : roll < 0.98 ? new ItemStack(Material.COPPER_INGOT)
                            : new ItemStack(Material.PRISMARINE_CRYSTALS);
                    block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), bonus);
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.5f, 1.6f);
                }
            }
        }

        // Archeologist (bonus XP and chance to find antique relic/treasure)
        var archeoAbility = Abilities.ARCHEOLOGIST;
        if (!isDisabled(archeoAbility) && !failsChecks(player, archeoAbility)) {
            double chance = getValue(archeoAbility, user) / 100.0;
            if (random.nextDouble() < chance) {
                double roll = random.nextDouble();
                ItemStack treasure = roll < 0.40 ? new ItemStack(Material.BONE, 1 + random.nextInt(3))
                        : roll < 0.70 ? new ItemStack(Material.EMERALD)
                        : roll < 0.90 ? new ItemStack(Material.NAME_TAG)
                        : new ItemStack(Material.ANGLER_POTTERY_SHERD);
                block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), treasure);
                block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.05);
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, SoundCategory.PLAYERS, 0.4f, 1.8f);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEarthArmorDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        var ability = Abilities.EARTH_ARMOR;
        if (isDisabled(ability) || failsChecks(player, ability)) return;

        Block standBlock = player.getLocation().clone().subtract(0, 0.2, 0).getBlock();
        if (isSoftBlock(standBlock.getType()) || standBlock.getType() == Material.STONE) {
            User user = plugin.getUser(player);
            double reductionPercent = getValue(ability, user);
            double newDamage = Math.max(0.0, event.getDamage() * (1.0 - (reductionPercent / 100.0)));
            event.setDamage(newDamage);
            player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0, 0.5, 0), 5, 0.2, 0.2, 0.2, standBlock.getBlockData());
        }
    }

}
