/*
 * Flight
 * Copyright 2022 Kiran Hart
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package ca.tweetzy.flight.comp.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.apache.commons.lang.ArrayUtils;
import org.bukkit.Bukkit;

@AllArgsConstructor
public enum ServerVersion {
    UNKNOWN("Unknown"),
    V1_7("1.7"),
    V1_8("1.8"),
    V1_9("1.9"),
    V1_10("1.10"),
    V1_11("1.11"),
    V1_12("1.12"),
    V1_13("1.13"),
    V1_14("1.14"),
    V1_15("1.15"),
    V1_16("1.16"),
    V1_17("1.17"),
    V1_18("1.18"),
    V1_19("1.19"),
    V1_20("1.20"),
    V1_21("1.21"),
    V1_22("1.22"),
    V1_23("1.23"),
    V26_1("26.1"),
    V26_2("26.2");

    @Getter
    private final String versionName;

    /**
     * Minecraft version string such as {@code 1.21.4} or {@code 26.2}.
     */
    public static String getMinecraftVersionString() {
        String version = Bukkit.getVersion();
        if (version != null && !version.isEmpty()) {
            int index = version.lastIndexOf("MC:");
            if (index != -1) {
                return version.substring(index + 4, version.length() - 1).trim();
            }
        }

        String bukkitVersion = Bukkit.getBukkitVersion();
        int dash = bukkitVersion.indexOf('-');
        return dash == -1 ? bukkitVersion : bukkitVersion.substring(0, dash);
    }

    /**
     * Number comparable to old 1.x minor versions.
     * {@code 1.21.4} → {@code 21}, {@code 26.2} → {@code 26}.
     */
    public static int getFeatureVersion() {
        String[] parts = getMinecraftVersionString().split("\\.");
        int major = Integer.parseInt(parts[0]);
        if (major == 1 && parts.length > 1) {
            return Integer.parseInt(parts[1]);
        }
        return major;
    }

    public static ServerVersion getVersion() {
        String[] versionPkgRaw = getMinecraftVersionString().split("\\.");
        String versionPkg = versionPkgRaw[0] + "." + versionPkgRaw[1];

        for (ServerVersion version : values()) {
            if (versionPkg.equalsIgnoreCase(version.getVersionName())) {
                return version;
            }
        }

        return UNKNOWN;
    }

    public boolean isLessThan(ServerVersion other) {
        return this.ordinal() < other.ordinal();
    }

    public boolean isAtOrBelow(ServerVersion other) {
        return this.ordinal() <= other.ordinal();
    }

    public boolean isGreaterThan(ServerVersion other) {
        return this.ordinal() > other.ordinal();
    }

    public boolean isAtLeast(ServerVersion other) {
        return this.ordinal() >= other.ordinal();
    }

    public static boolean isServerVersion(ServerVersion version) {
        return getVersion() == version;
    }

    public static boolean isServerVersion(ServerVersion... versions) {
        return ArrayUtils.contains(versions, getVersion());
    }

    public static boolean isServerVersionAbove(ServerVersion version) {
        return getVersion().ordinal() > version.ordinal();
    }

    public static boolean isServerVersionAtLeast(ServerVersion version) {
        return getVersion().ordinal() >= version.ordinal();
    }

    public static boolean isServerVersionAtOrBelow(ServerVersion version) {
        return getVersion().ordinal() <= version.ordinal();
    }

    public static boolean isServerVersionBelow(ServerVersion version) {
        return getVersion().ordinal() < version.ordinal();
    }
}
