package dev.aurelium.auraskills.bukkit.menus.shared;

import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.util.NumberUtil;
import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.user.AbilityPreset;
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
        registerEquippedSlots(menu, menuName);
        registerPresetManagerButton(menu);
        registerPresetSlots(menu, menuName);
    }

    private void registerEquippedSlots(MenuBuilder menu, String menuName) {
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

    private void registerPresetManagerButton(MenuBuilder menu) {
        menu.item("preset_manager", item -> {
            item.modify(info -> {
                ItemStack is = info.item();
                if (is.getType() == Material.AIR) {
                    is.setType(Material.NETHER_STAR);
                }
                ItemMeta meta = is.getItemMeta();
                if (meta == null) return is;
                meta.setDisplayName("§6§lパッシブ一括編成・プリセット管理");
                List<String> lore = new ArrayList<>();
                lore.add("§7全スキルの解放済みパッシブを1画面で");
                lore.add("§7確認・一括編成し、プリセットとして保存できます。");
                lore.add("");
                lore.add("§e▶ クリックして開く");
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            item.onClick(c -> {
                Player player = c.player();
                player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.7f, 1.0f);
                plugin.getSlate().openMenu(player, "presets");
            });
        });
    }

    private void registerPresetSlots(MenuBuilder menu, String menuName) {
        for (int i = 0; i < AbilityPreset.MAX_PRESETS; i++) {
            final int presetIndex = i;
            String itemName = "preset_slot_" + (presetIndex + 1);
            menu.item(itemName, item -> {
                item.modify(info -> {
                    Player player = info.player();
                    User user = plugin.getUser(player);
                    AbilityPreset preset = user.getPreset(presetIndex);

                    ItemStack is = info.item();
                    Material mat = Material.matchMaterial(preset.getIconMaterial());
                    if (mat != null && mat.isItem()) {
                        is.setType(mat);
                    } else {
                        is.setType(Material.BOOK);
                    }

                    ItemMeta meta = is.getItemMeta();
                    if (meta == null) return is;

                    boolean matching = user.isPresetMatchingEquipped(presetIndex);
                    String header = (matching ? "§a§l[適用中] " : "") + "§bプリセット #" + (presetIndex + 1) + ": §f" + preset.getName();
                    meta.setDisplayName(header);

                    List<String> lore = new ArrayList<>();
                    lore.add("§7登録スキル:");
                    List<Ability> abilities = preset.getAbilities();
                    if (abilities.isEmpty()) {
                        lore.add("  §8(未登録)");
                    } else {
                        for (Ability ab : abilities) {
                            lore.add("  §a✔ §f" + ab.getDisplayName(user.getLocale(), false) + " §7(§b" + ab.getSkill().getDisplayName(user.getLocale(), false) + "§7)");
                        }
                    }
                    lore.add("");
                    lore.add("§e▶ 左クリック: §fこのプリセットを装備");
                    lore.add("§e▶ 右クリック: §f現在のスキル構成を上書き保存");
                    lore.add("§e▶ Shift+左クリック: §f一括編成画面を開く");
                    lore.add("§c▶ Shift+右クリック: §7このプリセットを初期化");

                    meta.setLore(lore);
                    if (matching) {
                        meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
                    } else {
                        meta.removeEnchant(Enchantment.UNBREAKING);
                    }
                    is.setItemMeta(meta);
                    return is;
                });

                item.onClick(c -> {
                    Player player = c.player();
                    User user = plugin.getUser(player);
                    AbilityPreset preset = user.getPreset(presetIndex);

                    boolean shift = c.event().isShiftClick();
                    boolean right = c.event().isRightClick();

                    if (shift && !right) {
                        // Shift + 左クリック: 一括編成画面を開く
                        user.setSelectedPresetIndex(presetIndex);
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 0.7f, 1.0f);
                        plugin.getSlate().openMenu(player, "presets");
                        return;
                    }

                    if (shift && right) {
                        // Shift + 右クリック: プリセット初期化
                        preset.clearAbilities();
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, SoundCategory.PLAYERS, 0.7f, 1.0f);
                        player.sendMessage("§eプリセット #" + (presetIndex + 1) + "「§f" + preset.getName() + "§e」の登録スキルを初期化しました。");
                        plugin.getSlate().openMenu(player, menuName, c.menu().getProperties());
                        return;
                    }

                    if (right) {
                        // 通常右クリック: 現在のスキル構成を上書き保存
                        List<Ability> currentEquipped = user.getEquippedAbilities();
                        if (currentEquipped.isEmpty()) {
                            player.sendMessage("§c現在装備中のパッシブスキルがありません。");
                            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                            return;
                        }
                        user.saveCurrentToPreset(presetIndex);
                        player.playSound(player.getLocation(), Sound.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 0.7f, 1.2f);
                        player.sendMessage("§aプリセット #" + (presetIndex + 1) + "「§f" + preset.getName() + "§a」に現在のスキル構成を保存しました！");
                        plugin.getSlate().openMenu(player, menuName, c.menu().getProperties());
                        return;
                    }

                    // 通常左クリック: プリセットを装備
                    if (preset.getAbilities().isEmpty()) {
                        player.sendMessage("§eプリセット #" + (presetIndex + 1) + " にはまだスキルが登録されていません。右クリックで現在の構成を保存するか、Shift+左クリックで編成画面を開けます。");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                        return;
                    }

                    if (user.isInCombat()) {
                        long remainingMs = 5000L - (System.currentTimeMillis() - user.getLastCombatTime());
                        double sec = Math.max(0.1, remainingMs / 1000.0);
                        player.sendMessage("§c戦闘中はパッシブスキルを変更できません！ (残り " + String.format(Locale.ROOT, "%.1f", sec) + "秒)");
                        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, SoundCategory.PLAYERS, 0.7f, 0.8f);
                        return;
                    }

                    user.applyPreset(presetIndex);
                    player.playSound(player.getLocation(), Sound.ITEM_ARMOR_EQUIP_DIAMOND, SoundCategory.PLAYERS, 0.7f, 1.0f);
                    player.sendMessage("§aプリセット #" + (presetIndex + 1) + "「§f" + preset.getName() + "§a」を装備しました！");
                    plugin.getSlate().openMenu(player, menuName, c.menu().getProperties());
                });
            });
        }
    }
}
