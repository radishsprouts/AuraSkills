package dev.aurelium.auraskills.bukkit.skills.farming;

import dev.aurelium.auraskills.api.ability.Abilities;
import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.damage.DamageModifier;
import dev.aurelium.auraskills.api.damage.DamageType;
import dev.aurelium.auraskills.api.event.damage.DamageEvent;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.ability.BukkitAbilityImpl;
import dev.aurelium.auraskills.bukkit.util.ChainBreakHelper;
import dev.aurelium.auraskills.bukkit.util.ItemUtils;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class FarmingAbilities extends BukkitAbilityImpl {

    private final Random random = new Random();
    private final java.util.Set<Location> activeAutoHarvestLocations = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());

    public FarmingAbilities(AuraSkills plugin) {
        super(plugin, Abilities.BOUNTIFUL_HARVEST, Abilities.FARMER, Abilities.GENETICIST, Abilities.SCYTHE_MASTER, Abilities.GROWTH_AURA,
                Abilities.AUTO_REPLANT, Abilities.GREEN_THUMB, Abilities.GOURMET_FEAST, Abilities.GOLDEN_HARVEST);
    }

    @EventHandler
    public void geneticist(PlayerItemConsumeEvent event) {
        var ability = Abilities.GENETICIST;

        if (isDisabled(ability)) return;

        Player player = event.getPlayer();
        if (failsChecks(player, ability)) return;

        Material mat = event.getItem().getType();
        if (isPlantBased(mat)) { // Only allow plant based foods
            User user = plugin.getUser(player);

            float amount = (float) getValue(ability, user) / 10;
            player.setSaturation(player.getSaturation() + amount);
        }
    }

    private boolean isPlantBased(Material mat) {
        return mat.equals(Material.BREAD) || mat.equals(Material.APPLE) || mat.equals(Material.GOLDEN_APPLE) || mat.equals(Material.POTATO)
                || mat.equals(Material.BAKED_POTATO) || mat.equals(Material.CARROT) || mat.equals(Material.GOLDEN_CARROT) || mat.equals(Material.MELON)
                || mat.equals(Material.PUMPKIN_PIE) || mat.equals(Material.BEETROOT) || mat.equals(Material.BEETROOT_SOUP) || mat.equals(Material.MUSHROOM_STEW)
                || mat.equals(Material.POISONOUS_POTATO);
    }

    private DamageModifier scytheMaster(Player player, User user) {
        var ability = Abilities.SCYTHE_MASTER;

        if (isDisabled(ability) || failsChecks(player, ability)) return DamageModifier.none();

        if (user.getAbilityLevel(ability) <= 0) return DamageModifier.none();

        return new DamageModifier(getValue(ability, user) / 100, DamageModifier.Operation.ADD_COMBINED);
    }

    @Override
    public String replaceDescPlaceholders(String input, Ability ability, User user) {
        if (ability.equals(Abilities.GROWTH_AURA)) {
            return TextUtil.replace(input, "{radius}", String.valueOf(ability.optionInt("radius", 30)));
        }
        return input;
    }

    @EventHandler(ignoreCancelled = true)
    public void damageListener(DamageEvent event) {
        var meta = event.getDamageMeta();
        var attacker = meta.getAttackerAsPlayer();

        if (attacker != null) {
            if (meta.getDamageType() == DamageType.HOE) {
                var user = plugin.getUser(attacker);
                meta.addAttackModifier(scytheMaster(attacker, user));
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onCropGrowth(BlockGrowEvent event) {
        var ability = Abilities.GROWTH_AURA;

        if (isDisabled(ability)) return;

        Block block = event.getBlock();
        Location ogLoc = block.getLocation();
        int radius = ability.optionInt("radius", 30);

        // Check for players nearby
        Collection<Entity> entities = block.getWorld().getNearbyEntities(ogLoc, radius, radius, radius);
        List<Player> playerList = entities.stream().filter(e -> e instanceof Player).map(e -> (Player) e).toList();

        for (Player player : playerList) {
            handleGrowthAura(player, block, event.getNewState());
        }
    }

    private void handleGrowthAura(Player player, Block block, BlockState state) {
        var ability = Abilities.GROWTH_AURA;

        if (failsChecks(player, ability)) return;
        User user = plugin.getUser(player);

        int extraStages = rollExtraStages(user);
        if (extraStages == 0) return;

        if (state.getBlockData() instanceof Ageable ageable) {
            // Add growth stages with 1 tick delay
            plugin.getScheduler().scheduleAtLocation(block.getLocation(), () -> {
                if (block.getType() != state.getType()) return;

                ageable.setAge(Math.min(ageable.getAge() + extraStages, ageable.getMaximumAge()));
                block.setBlockData(ageable);
            }, 50, TimeUnit.MILLISECONDS);
        }
    }

    private int rollExtraStages(User user) {
        double value = getValue(Abilities.GROWTH_AURA, user);
        int guaranteed = (int) (value / 100);
        double chance = (value - guaranteed * 100) / 100;
        int extra = random.nextDouble() < chance ? 1 : 0;
        return guaranteed + extra;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCropBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Location loc = block.getLocation();

        if (activeAutoHarvestLocations.contains(loc)) {
            handleReplant(block, block.getType());
            return;
        }

        if (!(block.getBlockData() instanceof Ageable ageable) || ageable.getAge() < ageable.getMaximumAge()) {
            return;
        }

        Player player = event.getPlayer();
        User user = plugin.getUser(player);

        // Auto replant & 3x3 harvest
        var replantAbility = Abilities.AUTO_REPLANT;
        if (!isDisabled(replantAbility) && !failsChecks(player, replantAbility)) {
            handleReplant(block, block.getType());

            if (player.getInventory().getItemInMainHand().getType().name().endsWith("_HOE")) {
                double chance = getValue(replantAbility, user) / 100.0;
                if (random.nextDouble() < chance) {
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            if (dx == 0 && dz == 0) continue;
                            Block adj = block.getRelative(dx, 0, dz);
                            if (adj.getBlockData() instanceof Ageable adjAgeable && adjAgeable.getAge() >= adjAgeable.getMaximumAge()) {
                                Material adjType = adj.getType();
                                activeAutoHarvestLocations.add(adj.getLocation());
                                try {
                                    player.breakBlock(adj);
                                } finally {
                                    activeAutoHarvestLocations.remove(adj.getLocation());
                                }
                                handleReplant(adj, adjType);
                            }
                        }
                    }
                }
            }
        }

        // Golden harvest
        var goldenAbility = Abilities.GOLDEN_HARVEST;
        if (!isDisabled(goldenAbility) && !failsChecks(player, goldenAbility)) {
            double chance = getValue(goldenAbility, user) / 100.0;
            if (random.nextDouble() < chance) {
                double roll = random.nextDouble();
                ItemStack drop = roll < 0.70 ? new ItemStack(Material.GOLD_NUGGET, 1 + random.nextInt(3))
                        : roll < 0.95 ? new ItemStack(Material.GOLDEN_CARROT)
                        : new ItemStack(Material.GOLDEN_APPLE);
                block.getWorld().dropItemNaturally(loc.clone().add(0.5, 0.5, 0.5), drop);
                block.getWorld().spawn(loc.clone().add(0.5, 0.5, 0.5), ExperienceOrb.class, orb -> orb.setExperience(3));
                block.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, loc.clone().add(0.5, 0.5, 0.5), 8, 0.2, 0.2, 0.2, 0.05);
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.6f, 1.8f);
            }
        }
    }

    private void handleReplant(Block block, Material cropType) {
        Location loc = block.getLocation();
        plugin.getScheduler().scheduleAtLocation(loc, () -> {
            Block target = loc.getBlock();
            // プレイヤーが別の固形ブロック等を意図的に設置していない限り、再植え付けを実行
            if (target.getType().isAir() || isCrop(target.getType())) {
                Block below = target.getRelative(BlockFace.DOWN);
                boolean isFarmland = below.getType() == Material.FARMLAND;
                // 万一、バニラの水分抜けや接触判定で土に戻ってしまっていた場合、FARMLANDに再耕起して保護
                if (!isFarmland && (below.getType() == Material.DIRT || below.getType() == Material.DIRT_PATH) && cropType != Material.NETHER_WART && cropType != Material.COCOA) {
                    below.setType(Material.FARMLAND);
                    isFarmland = true;
                }
                boolean canPlant = (cropType == Material.NETHER_WART && below.getType() == Material.SOUL_SAND)
                        || (cropType != Material.COCOA && isFarmland)
                        || (cropType == Material.COCOA);
                if (canPlant) {
                    target.setType(cropType);
                    if (target.getBlockData() instanceof Ageable newAgeable) {
                        newAgeable.setAge(0);
                        target.setBlockData(newAgeable);
                        target.getWorld().playSound(loc, Sound.ITEM_CROP_PLANT, SoundCategory.PLAYERS, 0.7f, 1.2f);
                    }
                }
            }
        }, 100, TimeUnit.MILLISECONDS);
    }

    private boolean isCrop(Material mat) {
        return mat == Material.WHEAT || mat == Material.CARROTS || mat == Material.POTATOES
                || mat == Material.BEETROOTS || mat == Material.NETHER_WART || mat == Material.COCOA
                || mat == Material.TORCHFLOWER_CROP || mat == Material.PITCHER_CROP;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGreenThumbPlace(BlockPlaceEvent event) {
        var ability = Abilities.GREEN_THUMB;
        if (isDisabled(ability)) return;
        Player player = event.getPlayer();
        if (failsChecks(player, ability)) return;

        Block block = event.getBlockPlaced();
        if (block.getBlockData() instanceof Ageable) {
            User user = plugin.getUser(player);
            if (random.nextDouble() < getValue(ability, user) / 100.0) {
                plugin.getScheduler().scheduleAtLocation(block.getLocation(), () -> {
                    if (block.getBlockData() instanceof Ageable ageable) {
                        int bonus = random.nextBoolean() ? 2 : 1;
                        ageable.setAge(Math.min(ageable.getAge() + bonus, ageable.getMaximumAge()));
                        block.setBlockData(ageable);
                        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, block.getLocation().add(0.5, 0.5, 0.5), 6, 0.2, 0.2, 0.2, 0.02);
                    }
                }, 50, TimeUnit.MILLISECONDS);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGreenThumbBonemeal(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        var ability = Abilities.GREEN_THUMB;
        if (isDisabled(ability)) return;
        Player player = event.getPlayer();
        if (failsChecks(player, ability)) return;

        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.BONE_MEAL) return;
        Block clicked = event.getClickedBlock();
        if (clicked == null || !(clicked.getBlockData() instanceof Ageable)) return;

        User user = plugin.getUser(player);
        if (random.nextDouble() < getValue(ability, user) / 100.0) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    Block adj = clicked.getRelative(dx, 0, dz);
                    if (adj.getBlockData() instanceof Ageable ageable && ageable.getAge() < ageable.getMaximumAge()) {
                        ageable.setAge(Math.min(ageable.getAge() + 1, ageable.getMaximumAge()));
                        adj.setBlockData(ageable);
                        adj.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, adj.getLocation().add(0.5, 0.5, 0.5), 4, 0.2, 0.2, 0.2, 0.02);
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGourmetFeast(PlayerItemConsumeEvent event) {
        var ability = Abilities.GOURMET_FEAST;
        if (isDisabled(ability)) return;
        Player player = event.getPlayer();
        if (failsChecks(player, ability)) return;

        Material mat = event.getItem().getType();
        if (isFarmingFood(mat)) {
            User user = plugin.getUser(player);
            double boostPercent = getValue(ability, user);
            player.setSaturation((float) Math.min(20, player.getSaturation() + player.getSaturation() * (boostPercent / 100.0) + 1));
            PotionEffectType buff = random.nextBoolean() ? PotionEffectType.SPEED : PotionEffectType.STRENGTH;
            player.addPotionEffect(new PotionEffect(buff, 200, 0, false, true, true));
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_BURP, SoundCategory.PLAYERS, 0.8f, 1.2f);
        }
    }

    private boolean isFarmingFood(Material mat) {
        return switch (mat) {
            case BREAD, BAKED_POTATO, CARROT, GOLDEN_CARROT, PUMPKIN_PIE, BEETROOT_SOUP,
                 APPLE, GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE, COOKIE, MELON_SLICE,
                 MUSHROOM_STEW, RABBIT_STEW, SUSPICIOUS_STEW, SWEET_BERRIES, GLOW_BERRIES -> true;
            default -> false;
        };
    }

}
