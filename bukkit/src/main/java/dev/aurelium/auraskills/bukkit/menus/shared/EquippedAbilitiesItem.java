package dev.aurelium.auraskills.bukkit.menus.shared;

import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.user.User;
import dev.aurelium.auraskills.common.util.text.TextUtil;
import dev.aurelium.slate.builder.MenuBuilder;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EquippedAbilitiesItem {

    private final AuraSkills plugin;

    public EquippedAbilitiesItem(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void register(MenuBuilder menu, String menuName) {
        for (int i = 0; i < 3; i++) {
            final int slotIndex = i;
            String itemName = "equipped_slot_" + (slotIndex + 1);
            menu.item(itemName, item -> {
                item.modify(info -> {
                    Player player = info.player();
                    User user = plugin.getUser(player);
                    List<Ability> equipped = user.getEquippedAbilities();

                    ItemStack is = info.item();
                    ItemMeta meta = is.getItemMeta();
                    if (meta == null) return is;

                    if (slotIndex < equipped.size()) {
                        Ability ability = equipped.get(slotIndex);
                        is.setType(Material.EMERALD);
                        meta.setDisplayName("§a装備スロット #" + (slotIndex + 1) + ": " + ability.getDisplayName(user.getLocale(), false));

                        List<String> lore = new ArrayList<>();
                        lore.add("§7スキル: §b" + ability.getSkill().getDisplayName(user.getLocale(), false));
                        lore.add("");

                        int level = Math.max(1, user.getAbilityLevel(ability));
                        String desc = plugin.getAbilityManager().getBaseDescription(ability, user, false);
                        if (desc != null) {
                            desc = TextUtil.replace(desc,
                                    "{value}", NumberUtil.format1(ability.getValue(level)),
                                    "{value_2}", NumberUtil.format1(ability.getSecondaryValue(level)),
                                    "{chance_value}", plugin.getAbilityManager().getChanceValue(ability, level),
                                    "{guaranteed_value}", plugin.getAbilityManager().getGuaranteedValue(ability, level));
                            for (String line : desc.split("\n")) {
                                lore.add("§7" + line);
                            }
                        }
                        lore.add("");
                        lore.add("§e[セット中] クリックで解除");

                        meta.setLore(lore);
                        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    } else {
                        is.setType(Material.GRAY_DYE);
                        meta.setDisplayName("§8装備スロット #" + (slotIndex + 1) + ": (空き)");
                        List<String> lore = new ArrayList<>();
                        lore.add("§8スキルメニューからパッシブスキルを");
                        lore.add("§8クリックしてセットできます (最大3個)");
                        meta.setLore(lore);
                        meta.removeEnchant(Enchantment.UNBREAKING);
                    }
                    is.setItemMeta(meta);
                    return is;
                });

                item.onClick(c -> {
                    Player player = c.player();
                    User user = plugin.getUser(player);
                    List<Ability> equipped = user.getEquippedAbilities();

                    if (slotIndex < equipped.size()) {
                        Ability ability = equipped.get(slotIndex);
                        if (user.isInCombat()) {
                            long remainingMs = 5000L - (System.currentTimeMillis() - user.getLastCombatTime());
                            double sec = Math.max(0.1, remainingMs / 1000.0);
                            player.sendMessage("§c戦闘中はパッシブスキルを変更できません！ (残り " + String.format(Locale.ROOT, "%.1f", sec) + "秒)");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                            return;
                        }

                        user.unequipAbility(ability);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_REMOVE_ITEM, SoundCategory.PLAYERS, 0.7f, 1.2f);
                        plugin.getSlate().openMenu(player, menuName, c.menu().getProperties());
                    }
                });
            });
        }
    }

}
