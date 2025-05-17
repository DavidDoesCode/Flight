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

package ca.tweetzy.flight.utils;

import ca.tweetzy.flight.comp.enums.CompMaterial;
import ca.tweetzy.flight.comp.enums.ServerVersion;
import ca.tweetzy.flight.hooks.PlaceholderAPIHook;
import com.cryptomorin.xseries.XEnchantment;
import com.cryptomorin.xseries.XItemFlag;
import com.cryptomorin.xseries.profiles.builder.XSkull;
import com.cryptomorin.xseries.profiles.objects.ProfileInputType;
import com.cryptomorin.xseries.profiles.objects.Profileable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import de.tr7zw.changeme.nbtapi.NBT;
import lombok.Getter;
import lombok.NonNull;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.OfflinePlayer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.material.MaterialData;
import java.util.logging.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Date Created: April 07 2022
 * Time Created: 1:58 p.m.
 *
 * @author Kiran Hart
 */
public final class QuickItem {

    private ItemStack item;

    private ItemMeta meta;

    private CompMaterial material;

    private int amount = -1;

    private static String fallbackTexture = "https://textures.minecraft.net/texture/ecc58cb55b1a11e6d88c2d4d1a6366c23887dee26304bda412c4a51825f199";

    @Getter
    private String name;

    @Getter
    private final List<String> lores = new ArrayList<>();

    private final Map<Enchantment, Integer> enchants = new HashMap<>();

    private final List<ItemFlag> flags = new ArrayList<>();

    private boolean unbreakable = false;

    private Color color;

    private boolean hideTags = false;

    private Integer modelData;

    private boolean glow = false;

    private final Map<String, String> tags = new HashMap<>();

    // ----------------------------------------------------------------------------------------
    // Builder methods
    // ----------------------------------------------------------------------------------------

    /**
     * Set the ItemStack for this item. We will reapply all other properties
     * on this ItemStack, make sure they are compatible (such as skullOwner requiring a skull ItemStack, etc.)
     *
     * @param item
     *
     * @return
     */
    public QuickItem item(ItemStack item) {
        this.item = item;

        return this;
    }

    /**
     * Set the ItemMeta we use to start building. All other properties in this
     * class will build on this meta and take priority.
     *
     * @param meta
     *
     * @return
     */
    public QuickItem meta(ItemMeta meta) {
        this.meta = meta;

        return this;
    }

    /**
     * Set the Material for the item. If an itemstack has already been set,
     * this material will take priority.
     *
     * @param material
     *
     * @return
     */
    public QuickItem material(CompMaterial material) {
        this.material = material;

        return this;
    }

    /**
     * Set the amount of ItemStack to create.
     *
     * @param amount
     *
     * @return
     */
    public QuickItem amount(int amount) {
        this.amount = amount;

        return this;
    }

    /**
     * Set a custom name for the item (& color codes are replaced automatically).
     */
    public QuickItem name(String name) {
        this.name = name;

        return this;
    }

    /**
     * Set a fallback texture for the item if using custom heads.
     */
    public QuickItem fallbackTexture(String textureUrl) {
        this.fallbackTexture = textureUrl;
        return this;
    }

    /**
     * Remove any previous lore from the item. Useful if you initiated this
     * class with an ItemStack or set the itemstack already, to clear old lore off of it.
     */
    public QuickItem clearLore() {
        this.lores.clear();
        return this;
    }

    /**
     * Append the given lore to the end of existing item lore.
     */
    public QuickItem lore(String... lore) {
        return this.lore(Arrays.asList(lore));
    }

    /**
     * Append the given lore to the end of existing item lore.
     */
    public QuickItem lore(List<String> lore) {
        this.lores.addAll(lore);
        return this;
    }

    public QuickItem lore(Player player, String... lore) {
        List<String> newLore =Arrays.asList(lore);
        if (player != null) {
            newLore = PlaceholderAPIHook.tryReplace(player, newLore);
        }

        return this.lore(newLore);
    }

    /**
     * Append the given lore to the end of existing item lore.
     */
    public QuickItem lore(Player player, List<String> lore) {
        if (player != null) {
            this.lores.addAll(PlaceholderAPIHook.tryReplace(player, lore));
        } else {
            this.lores.addAll(lore);
        }
        return this;
    }

    /**
     * Add the given enchant to the item.
     */
    public QuickItem enchant(Enchantment enchantment) {
        return this.enchant(enchantment, 1);
    }

    /**
     * Add the given enchant to the item.
     */
    public QuickItem enchant(Enchantment enchantment, int level) {
        this.enchants.put(enchantment, level);
        return this;
    }

    /**
     * Add the given flags to the item.
     */
    public QuickItem flags(ItemFlag... flags) {
        this.flags.addAll(Arrays.asList(flags));
        return this;
    }

    /**
     * Set the item to be unbreakable.
     */
    public QuickItem unbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
        return this;
    }

    /**
     * Set the stained or dye color in case your item is either of {@link LeatherArmorMeta},
     * or from a selected list of compatible items such as stained glass, wool, etc.
     */
    public QuickItem color(Color color) {
        this.color = color;
        return this;
    }

    /**
     * Removes all enchantment, attribute and other tags appended
     * at the end of item lore, typically with blue color.
     */
    public QuickItem hideTags(boolean hideTags) {
        this.hideTags = hideTags;
        return this;
    }

    /**
     * Set the Custom Model Data of this item, compatible with MC 1.14+
     */
    public QuickItem modelData(int modelData) {
        this.modelData = modelData;
        return this;
    }

    /**
     * Makes this item glow. Ignored if enchantments exists. Call {@link #hideTags(boolean)}
     * to hide enchantment lores instead.
     */
    public QuickItem glow(boolean glow) {
        this.glow = glow;
        return this;
    }

    /**
     * Places an invisible custom tag to the item, for most server instances it
     * will persist across saves/restarts (you should check just to be safe).
     */
    public QuickItem tag(String key, String value) {
        this.tags.put(key, value);
        return this;
    }

    /**
     * Construct a valid {@link ItemStack} from all parameters of this class.
     *
     * @return the finished item
     */
    public ItemStack make() {
        ItemStack compiledItem = this.item != null ? this.item.clone() : this.material.parseItem();
        ItemMeta compiledMeta = compiledItem != null && compiledItem.hasItemMeta() ? compiledItem.getItemMeta() : Bukkit.getItemFactory().getItemMeta(compiledItem.getType());

        // Override with given material
        if (this.material != null) {
            compiledItem.setType(this.material.get());

            if (ServerVersion.isServerVersionBelow(ServerVersion.V1_13))
                compiledItem.setData(new MaterialData(this.material.parseMaterial(), this.material.getData()));
        }

        // Skip if air
        if (CompMaterial.isAir(compiledItem.getType()))
            return compiledItem;


        if (this.glow && this.enchants.isEmpty()) {
            compiledMeta.addEnchant(XEnchantment.UNBREAKING.get(), 1, true);
            this.flags.add(XItemFlag.HIDE_ENCHANTS.get());
        }

        for (final Map.Entry<Enchantment, Integer> entry : this.enchants.entrySet()) {
            final Enchantment enchant = entry.getKey();
            final int level = entry.getValue();

            if (compiledMeta instanceof EnchantmentStorageMeta)
                ((EnchantmentStorageMeta) compiledMeta).addStoredEnchant(enchant, level, true);

            else
                compiledMeta.addEnchant(enchant, level, true);
        }

        if (this.name != null && !"".equals(this.name))
            compiledMeta.setDisplayName(Common.colorize(name));

        List<String> lore = new ArrayList<>();

        if (meta != null && meta.getLore() != null)
            lore = meta.getLore();

        if (!this.lores.isEmpty()) {
            final List<String> coloredLores = new ArrayList<>();

            for (final String line : this.lores) {
                if (line != null) {
                    for (final String subLore : line.split("\n"))
                        coloredLores.add(Common.colorize("&7" + subLore));
                }
            }

            lore.addAll(coloredLores);
        }

        compiledMeta.setLore(lore);

        if (this.unbreakable) {
            this.flags.add(XItemFlag.HIDE_ATTRIBUTES.get());
            this.flags.add(XItemFlag.HIDE_UNBREAKABLE.get());
        }

        if (this.hideTags)
            for (final ItemFlag f : ItemFlag.values())
                if (!this.flags.contains(f))
                    this.flags.add(f);

        for (final ItemFlag flag : this.flags)
            try {
                Multimap<Attribute, AttributeModifier> modifiers = compiledMeta.getAttributeModifiers();
                if(modifiers == null) {
                    modifiers = HashMultimap.create();
                    compiledMeta.setAttributeModifiers(modifiers);
                }

                compiledMeta.addItemFlags(XItemFlag.of(flag).get());
            } catch (final Throwable t) {
            }

        // Override with custom amount if set
        if (this.amount != -1)
            compiledItem.setAmount(this.amount);

        if (this.modelData != null && ServerVersion.isServerVersionAtLeast(ServerVersion.V1_14))
            try {
                compiledMeta.setCustomModelData(this.modelData);
            } catch (final Throwable ignored) {
            }

        // Apply Bukkit metadata
        compiledItem.setItemMeta(compiledMeta);

        if (!this.tags.isEmpty()) {
            for (final Map.Entry<String, String> entry : this.tags.entrySet()) {
                NBT.modify(compiledItem, nbt -> {
                    nbt.setString(entry.getKey(), entry.getValue());
                });
            }
        }

        if (this.unbreakable) {
            NBT.modify(this.item, nbt -> {
                nbt.setByte("Unbreakable", (byte) 1);
            });
        }

        return compiledItem;
    }

    // ----------------------------------------------------------------------------------------
    // Static access
    // ----------------------------------------------------------------------------------------

    public static ItemStack bg(final ItemStack item) {
        return of(item).name(" ").clearLore().make();
    }

    public static ItemStack bg(final String item) {
        return of(item).name(" ").clearLore().make();
    }

    /**
     * Convenience method to get a new item creator with material, name and lore set
     *
     * @param material
     * @param name
     * @param lore
     *
     * @return new item creator
     */
    public static QuickItem of(final CompMaterial material, final String name, @NonNull final String... lore) {
        return new QuickItem().material(material).name(name).lore(lore).hideTags(true);
    }

    public static QuickItem of(final String material) {
        if (material.contains("textures.minecraft.net"))
            return of(createTexturedHead(material));

        final String[] split = material.split(":");

        if (split.length == 2 && MathUtil.isInt(split[1])) {
            return new QuickItem().material(CompMaterial.matchCompMaterial(split[0].toUpperCase()).orElse(CompMaterial.STONE)).modelData(Integer.parseInt(split[1])).hideTags(true);
        }

        return new QuickItem().material(CompMaterial.matchCompMaterial(split[0].toUpperCase()).orElse(CompMaterial.STONE)).hideTags(true);
    }

    /**
     * Convenience method to get a wool
     *
     * @param color the wool color
     *
     * @return the new item creator
     */
    public static QuickItem ofWool(final Color color) {
        return of(CompMaterial.makeWool(color, 1)).color(color);
    }

    /**
     * Convenience method to get the creator of an existing itemstack
     *
     * @param item existing itemstack
     *
     * @return the new item creator
     */
    public static QuickItem of(final ItemStack item) {
        final QuickItem builder = new QuickItem();
        builder.item(item);

        final ItemMeta meta = item.hasItemMeta() ? item.getItemMeta() : Bukkit.getItemFactory().getItemMeta(item.getType());

//        if (meta != null && meta.getLore() != null)
//            builder.lore(meta.getLore());

        return builder.meta(meta);
    }

    /**
     * Get a new item creator from material
     *
     * @param mat existing material
     *
     * @return the new item creator
     */
    public static QuickItem of(final CompMaterial mat) {
        return new QuickItem().material(mat);
    }

    public static QuickItem of(final OfflinePlayer player) {
        final ItemStack itemStack = CompMaterial.PLAYER_HEAD.parseItem();
        final SkullMeta meta = (SkullMeta) itemStack.getItemMeta();

        assert meta != null;

        try {
            XSkull.of(meta).profile(Profileable.of(player)).applyAsync().join();
        } catch (Exception ignored) {
            Bukkit.getLogger().log(Level.WARNING, ignored.getMessage());
            meta.setOwningPlayer(player);
        }

        itemStack.setItemMeta(meta);
        return of(itemStack);
    }

    public static CompletableFuture<ItemStack> asyncTexturedHead(String url) {
        return XSkull
                .of(CompMaterial.PLAYER_HEAD.parseItem())
                .profile(Profileable.detect(url))
                .fallback(Profileable.of(ProfileInputType.TEXTURE_URL, fallbackTexture))
                .lenient()
                .applyAsync();
    }

    public static ItemStack createTexturedHead(String url) {
        ItemStack item = CompMaterial.PLAYER_HEAD.parseItem();
        if (item == null)
            return CompMaterial.STONE.parseItem();

        SkullMeta sm = (SkullMeta) item.getItemMeta();
        assert sm != null;

        XSkull
                .of(sm)
                .profile(Profileable.detect(url))
                .fallback(Profileable.of(ProfileInputType.TEXTURE_URL, fallbackTexture))
                .lenient()
                .apply();
        item.setItemMeta(sm);

        return item;
    }

    public static String toString(ItemStack item) {
        return SerializeUtil.itemToString(item);
    }

    public static String toString(ItemStack... items) {
        return SerializeUtil.itemsToString(items);
    }

    public static ItemStack getItem(String string) {
        return SerializeUtil.stringToItem(string);
    }

    public static ItemStack[] getItems(String string) {
        return SerializeUtil.stringToItems(string);
    }

    private static String encodeURL(final String url) {
        byte[] encodedData = Base64.getEncoder().encode(String.format("{textures:{SKIN:{url:\"%s\"}}}", url).getBytes());
        return new String(encodedData);
    }
}
