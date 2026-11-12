package dev.aurelium.auraskills.bukkit.util;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class VersionUtils {

    private static final Pattern VERSION_PATTERN = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    public static final int MAJOR_VERSION = getMajorVersion(getVersionString(Bukkit.getBukkitVersion()));
    public static final int MINOR_VERSION = getMinorVersion(getVersionString(Bukkit.getBukkitVersion()));
    public static final int PATCH_VERSION = getPatchVersion(getVersionString(Bukkit.getBukkitVersion()));

    private static final int FALLBACK_MAJOR_VERSION = 20;
    private static final int FALLBACK_MINOR_VERSION = 0;

    public static boolean isAtLeastVersion(int version) {
        return MAJOR_VERSION >= version;
    }

    public static boolean isAtLeastVersion(int majorVersionReq, int minorVersionReq) {
        if (MAJOR_VERSION > majorVersionReq) {
            return true;
        } else if (MAJOR_VERSION == majorVersionReq) {
            return MINOR_VERSION >= minorVersionReq;
        } else {
            return false;
        }
    }

    public static boolean isAtLeastVersion(int majorVersionReq, int minorVersionReq, int patchVersionReq) {
        if (MAJOR_VERSION > majorVersionReq) {
            return true;
        } else if (MAJOR_VERSION == majorVersionReq) {
            if (MINOR_VERSION > minorVersionReq) {
                return true;
            } else {
                return PATCH_VERSION >= patchVersionReq;
            }
        } else {
            return false;
        }
    }

    private static int parseLeadingInt(String str, int fallback) {
        if (str == null || str.isEmpty()) {
            return fallback;
        }
        int i = 0;
        while (i < str.length() && Character.isDigit(str.charAt(i))) {
            i++;
        }
        if (i == 0) {
            return fallback;
        }
        try {
            return Integer.parseInt(str.substring(0, i));
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int getMinorVersion(String version) {
        try {
            if (version == null) {
                return FALLBACK_MINOR_VERSION;
            }
            String[] parts = version.split("\\.");
            if (parts.length == 0) {
                return FALLBACK_MINOR_VERSION;
            }
            if (parts[0].equals("1")) {
                if (parts.length > 2) {
                    return parseLeadingInt(parts[2], FALLBACK_MINOR_VERSION);
                } else {
                    return 0;
                }
            } else {
                if (parts.length > 1) {
                    return parseLeadingInt(parts[1], FALLBACK_MINOR_VERSION);
                } else {
                    return 0;
                }
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_MINOR_VERSION;
    }

    public static int getMajorVersion(String version) {
        try {
            if (version == null) {
                return FALLBACK_MAJOR_VERSION;
            }
            String[] parts = version.split("\\.");
            if (parts.length == 0) {
                return FALLBACK_MAJOR_VERSION;
            }
            if (parts[0].equals("1")) {
                if (parts.length > 1) {
                    return parseLeadingInt(parts[1], FALLBACK_MAJOR_VERSION);
                }
            } else {
                return parseLeadingInt(parts[0], FALLBACK_MAJOR_VERSION);
            }
        } catch (Exception ignored) {
        }
        return FALLBACK_MAJOR_VERSION;
    }

    public static int getPatchVersion(String version) {
        try {
            if (version == null) {
                return 0;
            }
            String[] parts = version.split("\\.");
            if (parts.length == 0 || parts[0].equals("1")) {
                return 0;
            } else {
                if (parts.length > 2) {
                    return parseLeadingInt(parts[2], 0);
                } else {
                    return 0;
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    public static String getVersionString(@Nullable String version) {
        if (version == null || version.isEmpty()) {
            return null;
        }

        // Bukkit.getVersion() often contains "(MC: 1.20.4)" or "(MC: 26.1.2)"
        int index = version.lastIndexOf("MC:");
        if (index != -1) {
            version = version.substring(index + 3);
        }

        Matcher matcher = VERSION_PATTERN.matcher(version);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Fallback: strip SNAPSHOT / build / extra words
        if (version.contains(".build.")) {
            int buildIndex = version.indexOf(".build.");
            version = version.substring(0, buildIndex);
        } else if (version.endsWith("SNAPSHOT") && version.contains("-")) {
            index = version.indexOf('-');
            version = version.substring(0, index);
        }

        return version.split(" ")[0];
    }

}
