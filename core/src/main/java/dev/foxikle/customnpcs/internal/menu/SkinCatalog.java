/*
 * Copyright (c) 2024. Foxikle
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package dev.foxikle.customnpcs.internal.menu;

import dev.foxikle.customnpcs.internal.utils.Msg;
import io.github.mqzen.menus.base.Content;
import io.github.mqzen.menus.base.MenuView;
import io.github.mqzen.menus.base.pagination.FillRange;
import io.github.mqzen.menus.base.pagination.Page;
import io.github.mqzen.menus.base.style.TextLayout;
import io.github.mqzen.menus.base.style.TextLayoutPane;
import io.github.mqzen.menus.misc.Capacity;
import io.github.mqzen.menus.misc.DataRegistry;
import io.github.mqzen.menus.misc.Slots;
import io.github.mqzen.menus.misc.itembuilder.ItemBuilder;
import io.github.mqzen.menus.titles.MenuTitle;
import io.github.mqzen.menus.titles.MenuTitles;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

public class SkinCatalog extends Page {

    @Override
    public FillRange getFillRange(Capacity capacity, Player player) {
        return FillRange.start(capacity).except(Slots.of(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 17, 18, 26, 27, 35, 36, 45, 46, 47, 48, 49, 50, 51, 52, 53).getSlots());
    }

    @Override
    public ItemStack nextPageItem(Player player) {
        return ItemBuilder.modern(Material.ARROW)
                .setDisplay(Msg.translate(player.locale(), "customnpcs.items.next_page"))
                .build();
    }

    @Override
    public ItemStack previousPageItem(Player player) {
        return ItemBuilder.modern(Material.ARROW)
                .setDisplay(Msg.translate(player.locale(), "customnpcs.items.prev_page"))
                .build();
    }

    /**
     * Handles the click sounds on page change
     *
     * @param playerMenuView the menu view that the player has clicked on.
     * @param event          the click event
     */
    @Override
    public void onPostClick(MenuView<?> playerMenuView, InventoryClickEvent event) {
        if ((event.getSlot() == 45 || event.getSlot() == 53) && event.getCurrentItem().getType() == Material.ARROW) {
            event.getWhoClicked().playSound(Sound.sound(builder -> builder.type(Key.key("minecraft", "ui.button.click")).pitch(1).volume(1).source(Sound.Source.MASTER)));
        }
    }

    @Override
    public String getName() {
        return MenuUtils.NPC_SKIN_CATALOG;
    }

    @Override
    public @NotNull MenuTitle getTitle(DataRegistry dataRegistry, Player player) {
        return MenuTitles.createModern(Msg.translate(player.locale(), "customnpcs.menus.skin_catalog.title"));
    }

    @Override
    public @NotNull Capacity getCapacity(DataRegistry dataRegistry, Player player) {
        return Capacity.ofRows(6);
    }

    @Override
    public @NotNull Content getContent(DataRegistry dataRegistry, Player player, Capacity capacity) {
        Content.Builder builder = Content.builder(capacity);
        TextLayoutPane pane = new TextLayoutPane(
                capacity, TextLayout.builder().set('X', MenuItems.MENU_GLASS)
                .set('O', MenuItems.toMain(player)).build(),
                "XXXXXXXXX",
                "X       X",
                "X       X",
                "X       X",
                "X       X",
                "XXXXOXXXX"
        );
        builder.applyPane(pane);
        return builder.build();
    }
}
