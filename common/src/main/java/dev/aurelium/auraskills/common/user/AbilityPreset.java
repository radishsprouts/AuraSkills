package dev.aurelium.auraskills.common.user;

import dev.aurelium.auraskills.api.ability.Ability;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class AbilityPreset {

    public static final int MAX_PRESETS = 5;
    public static final int MAX_NAME_LENGTH = 16;

    private static final String[] DEFAULT_NAMES = {
            "戦闘セット",
            "農業セット",
            "採掘セット",
            "伐採・探索",
            "汎用セット"
    };

    private static final String[] DEFAULT_ICONS = {
            "DIAMOND_SWORD",
            "GOLDEN_HOE",
            "IRON_PICKAXE",
            "IRON_AXE",
            "NETHER_STAR"
    };

    public static final String[] AVAILABLE_ICONS = {
            "DIAMOND_SWORD",
            "BOW",
            "SHIELD",
            "GOLDEN_HOE",
            "IRON_PICKAXE",
            "IRON_AXE",
            "IRON_SHOVEL",
            "FISHING_ROD",
            "FEATHER",
            "BREWING_STAND",
            "ENCHANTING_TABLE",
            "GOLDEN_APPLE",
            "TOTEM_OF_UNDYING",
            "NETHER_STAR"
    };

    private final int index;
    private volatile String name;
    private volatile String iconMaterial;
    private final List<Ability> abilities = new CopyOnWriteArrayList<>();

    public AbilityPreset(int index) {
        this.index = index;
        this.name = (index >= 0 && index < DEFAULT_NAMES.length) ? DEFAULT_NAMES[index] : "プリセット #" + (index + 1);
        this.iconMaterial = (index >= 0 && index < DEFAULT_ICONS.length) ? DEFAULT_ICONS[index] : "BOOK";
    }

    public int getIndex() {
        return index;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        if (name == null || name.isBlank()) {
            this.name = (index >= 0 && index < DEFAULT_NAMES.length) ? DEFAULT_NAMES[index] : "プリセット #" + (index + 1);
            return;
        }
        String sanitized = name.replace("|", "").replace("\n", "").replace("\r", "").trim();
        if (sanitized.length() > MAX_NAME_LENGTH) {
            sanitized = sanitized.substring(0, MAX_NAME_LENGTH);
        }
        this.name = sanitized.isEmpty() ? DEFAULT_NAMES[index % DEFAULT_NAMES.length] : sanitized;
    }

    public String getIconMaterial() {
        return iconMaterial;
    }

    public void setIconMaterial(String iconMaterial) {
        if (iconMaterial == null || iconMaterial.isBlank()) {
            this.iconMaterial = DEFAULT_ICONS[index % DEFAULT_ICONS.length];
            return;
        }
        this.iconMaterial = iconMaterial.toUpperCase(java.util.Locale.ROOT);
    }

    public void cycleIcon(boolean forward) {
        int currentIdx = 0;
        for (int i = 0; i < AVAILABLE_ICONS.length; i++) {
            if (AVAILABLE_ICONS[i].equalsIgnoreCase(this.iconMaterial)) {
                currentIdx = i;
                break;
            }
        }
        int nextIdx = forward
                ? (currentIdx + 1) % AVAILABLE_ICONS.length
                : (currentIdx - 1 + AVAILABLE_ICONS.length) % AVAILABLE_ICONS.length;
        this.iconMaterial = AVAILABLE_ICONS[nextIdx];
    }

    public List<Ability> getAbilities() {
        return Collections.unmodifiableList(abilities);
    }

    public void setAbilities(Collection<Ability> newAbilities) {
        abilities.clear();
        if (newAbilities == null) return;
        List<Ability> dedup = new ArrayList<>(User.MAX_EQUIPPED_ABILITIES);
        for (Ability a : newAbilities) {
            if (a != null && !dedup.contains(a) && dedup.size() < User.MAX_EQUIPPED_ABILITIES) {
                dedup.add(a);
            }
        }
        abilities.addAll(dedup);
    }

    public void clearAbilities() {
        abilities.clear();
    }

    public boolean isDefaultAndEmpty() {
        String defName = (index >= 0 && index < DEFAULT_NAMES.length) ? DEFAULT_NAMES[index] : "プリセット #" + (index + 1);
        String defIcon = (index >= 0 && index < DEFAULT_ICONS.length) ? DEFAULT_ICONS[index] : "BOOK";
        return abilities.isEmpty() && defName.equals(this.name) && defIcon.equalsIgnoreCase(this.iconMaterial);
    }
}
