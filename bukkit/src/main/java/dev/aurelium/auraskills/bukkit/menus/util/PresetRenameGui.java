package dev.aurelium.auraskills.bukkit.menus.util;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.common.user.AbilityPreset;
import dev.aurelium.auraskills.common.user.User;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MenuType;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.view.AnvilView;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 金床（Anvil）を使ったプリセット名入力システム。
 * KadoUtils の AnvilPrompt 設計を踏襲し、MenuType.ANVIL を用いて
 * 入力欄の紙の文字変更をリアルタイムで検知・検証し、出力欄クリックで確定、Escでキャンセルを行う。
 */
public class PresetRenameGui implements Listener {

    private static final int OUTPUT_SLOT = 2;
    private final AuraSkills plugin;
    private final NamespacedKey markKey;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public record Check(boolean ok, String value, String errorReason) {
        public static Check good(String value) {
            return new Check(true, value, null);
        }

        public static Check bad(String value, String errorReason) {
            return new Check(false, value, errorReason);
        }
    }

    @FunctionalInterface
    public interface Validator {
        Check check(String input);
    }

    public record Spec(
            String title,
            String initial,
            Validator validator,
            Consumer<String> onConfirm,
            Runnable onCancel
    ) {}

    private record Session(Spec spec, AnvilView view) {}

    public PresetRenameGui(AuraSkills plugin) {
        this.plugin = plugin;
        this.markKey = new NamespacedKey(plugin, "gui_anvil_input");
    }

    /**
     * プリセット名変更の金床画面を開く。
     */
    public void openPresetRename(Player player, int presetIndex, Map<String, Object> returnProperties) {
        User user = plugin.getUser(player);
        AbilityPreset preset = user.getPreset(presetIndex);
        String initial = preset.getName();

        Spec spec = new Spec(
                "プリセット #" + (presetIndex + 1) + " の名前を入力",
                initial,
                input -> {
                    String cleaned = cleanName(input);
                    if (cleaned.isBlank()) {
                        return Check.bad(cleaned, "プリセット名を入力してください");
                    }
                    if (cleaned.length() > AbilityPreset.MAX_NAME_LENGTH) {
                        return Check.bad(cleaned, AbilityPreset.MAX_NAME_LENGTH + "文字以内で入力してください");
                    }
                    return Check.good(cleaned);
                },
                confirmedName -> {
                    preset.setName(confirmedName);
                    player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
                    player.sendMessage("§aプリセット #" + (presetIndex + 1) + " の名前を「§f" + preset.getName() + "§a」に変更しました。");
                    plugin.getSlate().openMenu(player, "presets", returnProperties);
                },
                () -> plugin.getSlate().openMenu(player, "presets", returnProperties)
        );

        open(player, spec);
    }

    public void open(Player player, Spec spec) {
        plugin.getScheduler().executeAtEntity(player, task -> {
            AnvilView view = MenuType.ANVIL.create(player, spec.title());
            if (view == null) {
                player.sendMessage("§c金床画面の作成に失敗しました。");
                return;
            }

            String initialText = spec.initial() != null ? spec.initial() : "";
            Check firstCheck = spec.validator().check(initialText);

            view.setItem(0, createInputPaper(initialText));
            view.setItem(OUTPUT_SLOT, createOutputItem(firstCheck));
            view.setRepairCost(0);
            view.setMaximumRepairCost(0);

            sessions.put(player.getUniqueId(), new Session(spec, view));
            player.openInventory(view);
        });
    }

    public boolean isOpen(Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareAnvil(PrepareAnvilEvent event) {
        if (!(event.getView().getPlayer() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || !(event.getView() instanceof AnvilView anvil)) {
            return;
        }

        anvil.setRepairCost(0);
        anvil.setMaximumRepairCost(0);

        String text = getTypedText(session, anvil);
        Check check = session.spec().validator().check(text);
        event.setResult(createOutputItem(check));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        Session session = sessions.get(player.getUniqueId());
        if (session == null || event.getView().getType() != InventoryType.ANVIL) {
            return;
        }

        event.setCancelled(true);

        if (event.getRawSlot() != OUTPUT_SLOT || !(event.getView() instanceof AnvilView anvil)) {
            return;
        }

        String text = getTypedText(session, anvil);
        Check check = session.spec().validator().check(text);
        if (!check.ok()) {
            player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return;
        }

        // 確定処理
        sessions.remove(player.getUniqueId());
        anvil.setItem(0, null);
        anvil.setItem(OUTPUT_SLOT, null);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, SoundCategory.MASTER, 1.0f, 1.0f);
        player.closeInventory();
        sweep(player);

        String confirmedValue = check.value();
        // 画面遷移の同期安定のため1tick遅らせる (Folia EntityScheduler)
        plugin.getScheduler().scheduleAtEntity(player, () -> {
            session.spec().onConfirm().accept(confirmedValue);
        }, 50L, TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && sessions.containsKey(player.getUniqueId())
                && event.getView().getType() == InventoryType.ANVIL) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || event.getView().getType() != InventoryType.ANVIL) {
            return;
        }
        Session removed = sessions.remove(player.getUniqueId());
        if (removed == null) {
            return;
        }

        removed.view().setItem(0, null);
        removed.view().setItem(OUTPUT_SLOT, null);
        sweep(player);

        // 1tick 後に直前の画面復帰などのキャンセル処理を実行
        plugin.getScheduler().scheduleAtEntity(player, () -> {
            sweep(player);
            removed.spec().onCancel().run();
        }, 50L, TimeUnit.MILLISECONDS);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        if (sessions.remove(player.getUniqueId()) != null) {
            sweep(player);
        }
    }

    private static String getTypedText(Session session, AnvilView view) {
        String text = view.getRenameText();
        return text == null ? session.spec().initial() : text;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createInputPaper(String initial) {
        ItemStack paper = new ItemStack(Material.PAPER);
        ItemMeta meta = paper.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(initial);
            meta.getPersistentDataContainer().set(markKey, PersistentDataType.BYTE, (byte) 1);
            paper.setItemMeta(meta);
        }
        return paper;
    }

    @SuppressWarnings("deprecation")
    private ItemStack createOutputItem(Check check) {
        if (check.ok()) {
            ItemStack item = new ItemStack(Material.NAME_TAG);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setDisplayName("§a§l✔ 確定: 「" + check.value() + "」にする");
                meta.setLore(List.of("§7クリックして確定します"));
                item.setItemMeta(meta);
            }
            return item;
        } else {
            ItemStack item = new ItemStack(Material.GRAY_CONCRETE);
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                String reason = check.errorReason() != null ? check.errorReason() : "入力内容が無効です";
                meta.setDisplayName("§c§l✖ " + reason);
                meta.setLore(List.of("§7文字を修正してください"));
                item.setItemMeta(meta);
            }
            return item;
        }
    }

    /**
     * 金床の入力用紙がプレイヤーインベントリやカーソルに残っていたら消去する。
     */
    public void sweep(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int i = 0; i < inventory.getSize(); i++) {
            if (isMarked(inventory.getItem(i))) {
                inventory.setItem(i, null);
            }
        }
        if (isMarked(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private boolean isMarked(ItemStack stack) {
        if (stack == null || stack.getType() != Material.PAPER || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        return meta != null && meta.getPersistentDataContainer().has(markKey, PersistentDataType.BYTE);
    }

    @SuppressWarnings("deprecation")
    private String cleanName(String input) {
        if (input == null) return "";
        String stripped = ChatColor.stripColor(input);
        if (stripped == null) return "";
        return stripped.replace("|", "").trim();
    }
}
