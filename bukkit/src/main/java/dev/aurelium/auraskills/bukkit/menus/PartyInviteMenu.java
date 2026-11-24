package dev.aurelium.auraskills.bukkit.menus;

import dev.aurelium.auraskills.bukkit.AuraSkills;
import dev.aurelium.auraskills.bukkit.menus.shared.GlobalItems;
import dev.aurelium.auraskills.bukkit.party.BukkitPartyNotifier;
import dev.aurelium.auraskills.common.party.PartyActionResult;
import dev.aurelium.auraskills.common.party.PartyImpl;
import dev.aurelium.auraskills.common.party.PartyManagerImpl;
import dev.aurelium.slate.builder.MenuBuilder;
import dev.aurelium.slate.inv.content.SlotPos;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.*;

public class PartyInviteMenu {

    private static final int PLAYERS_PER_PAGE = 36;
    private final AuraSkills plugin;

    public PartyInviteMenu(AuraSkills plugin) {
        this.plugin = plugin;
    }

    public void build(MenuBuilder menu) {
        var globalItems = new GlobalItems(plugin);
        menu.item("back", globalItems::back);
        menu.item("close", globalItems::close);
        menu.item("previous_page", globalItems::previousPage);
        menu.item("next_page", globalItems::nextPage);
        menu.fillItem(globalItems::fill);

        menu.properties(m -> Map.of("previous_menu", m.menu().getProperty("previous_menu", "party")));

        menu.replaceTitle("current_page", p -> String.valueOf(p.menu().getCurrentPage() + 1));
        menu.replaceTitle("total_pages", p -> String.valueOf(Math.max(1, p.menu().getTotalPages())));

        menu.pages(m -> {
            List<UUID> candidates = getInvitablePlayers(m.player());
            return Math.max(1, (candidates.size() - 1) / PLAYERS_PER_PAGE + 1);
        });

        menu.item("no_candidates", item -> item.modify(info -> {
            if (!getInvitablePlayers(info.player()).isEmpty()) return null;
            return info.item();
        }));

        menu.template("candidate", UUID.class, template -> {
            template.definedContexts(m -> {
                List<UUID> candidates = getInvitablePlayers(m.player());
                int page = m.menu().getCurrentPage();
                int start = page * PLAYERS_PER_PAGE;
                int end = Math.min(candidates.size(), start + PLAYERS_PER_PAGE);
                if (start >= candidates.size()) return Collections.emptySet();
                return new LinkedHashSet<>(candidates.subList(start, end));
            });

            template.slotPos(c -> {
                List<UUID> candidates = getInvitablePlayers(c.player());
                int page = c.menu().getCurrentPage();
                int start = page * PLAYERS_PER_PAGE;
                int idx = candidates.indexOf(c.value()) - start;
                if (idx < 0) idx = 0;
                int row = 1 + (idx / 9);
                int col = idx % 9;
                return SlotPos.of(row, col);
            });

            template.modify(info -> {
                UUID targetId = info.value();
                Player target = Bukkit.getPlayer(targetId);
                if (target == null || !target.isOnline()) return null;

                ItemStack is = info.item();
                is.setType(Material.PLAYER_HEAD);
                ItemMeta rawMeta = is.getItemMeta();
                if (!(rawMeta instanceof SkullMeta meta)) return is;

                meta.setOwningPlayer(target);
                PartyManagerImpl pm = plugin.getPartyManager();
                PartyImpl party = pm.getPartyImpl(info.player().getUniqueId());
                boolean alreadyInvited = party != null && pm.hasPendingInviteFromParty(targetId, party.getId());

                meta.setDisplayName("§b" + target.getName());
                List<String> lore = new ArrayList<>();
                lore.add("§7パワーレベル: §e" + plugin.getUser(target).getPowerLevel());
                lore.add("");
                if (alreadyInvited) {
                    lore.add("§a✔ 招待送信済み");
                } else {
                    lore.add("§e▶ クリックしてパーティーに招待");
                }
                meta.setLore(lore);
                is.setItemMeta(meta);
                return is;
            });

            template.onClick(c -> {
                Player viewer = c.player();
                UUID targetId = c.value();
                Player target = Bukkit.getPlayer(targetId);
                String targetName = target != null ? target.getName() : targetId.toString().substring(0, 8);

                PartyActionResult res = plugin.getPartyManager().invitePlayer(viewer.getUniqueId(), targetId);
                if (res == PartyActionResult.SUCCESS) {
                    plugin.getSlate().openMenu(viewer, "party_invite", c.menu().getProperties(), c.menu().getCurrentPage());
                } else {
                    new BukkitPartyNotifier(plugin).sendActionError(viewer, res, targetName);
                }
            });
        });
    }

    private List<UUID> getInvitablePlayers(Player viewer) {
        PartyManagerImpl pm = plugin.getPartyManager();
        List<UUID> list = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getUniqueId().equals(viewer.getUniqueId())) continue;
            if (p.hasMetadata("NPC")) continue;
            if (pm.isInParty(p.getUniqueId())) continue;
            if (pm.isInviteBlocked(p.getUniqueId())) continue;
            list.add(p.getUniqueId());
        }
        list.sort(Comparator.comparing(id -> {
            Player p = Bukkit.getPlayer(id);
            return p != null ? p.getName().toLowerCase(Locale.ROOT) : "";
        }));
        return list;
    }

}
