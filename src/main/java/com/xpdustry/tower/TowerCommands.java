/*
 * This file is part of TowerDefense. An implementation of the tower defense gamemode by Xpdustry.
 *
 * MIT License
 *
 * Copyright (c) 2024 Xpdustry
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
package com.xpdustry.tower;

import arc.util.CommandHandler;
import com.xpdustry.distributor.api.Distributor;
import com.xpdustry.distributor.api.component.style.ComponentColor;
import com.xpdustry.distributor.api.plugin.MindustryPlugin;
import com.xpdustry.distributor.api.plugin.PluginListener;
import java.awt.Color;
import java.io.IOException;
import mindustry.Vars;
import mindustry.gen.Player;
import org.jspecify.annotations.Nullable;

import static com.xpdustry.distributor.api.component.ListComponent.components;
import static com.xpdustry.distributor.api.component.TextComponent.newline;
import static com.xpdustry.distributor.api.component.TextComponent.text;

final class TowerCommands implements PluginListener {

    private final MindustryPlugin plugin;
    private final TowerConfigProvider config;

    TowerCommands(final MindustryPlugin plugin, final TowerConfigProvider config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public void onPluginClientCommandsRegistration(final CommandHandler handler) {
        this.onPluginSharedCommandsRegistration(handler);
    }

    @Override
    public void onPluginServerCommandsRegistration(final CommandHandler handler) {
        this.onPluginSharedCommandsRegistration(handler);

        handler.register("td-reload", "Reload TD configuration.", args -> {
            try {
                this.config.reload();
                this.plugin.getLogger().info("TD configuration reloaded!");
            } catch (final IOException e) {
                this.plugin.getLogger().error("Failed to reload TD configuration!", e);
            }
        });
    }

    private void onPluginSharedCommandsRegistration(final CommandHandler handler) {
        handler.<@Nullable Player>register("td", "Information about the TD gamemode", (args, player) -> {
            final var audience = player == null
                    ? Distributor.get().getAudienceProvider().getServer()
                    : Distributor.get().getAudienceProvider().getPlayer(player);
            audience.sendMessage(components(
                    ComponentColor.WHITE,
                    text(">>>", ComponentColor.CYAN),
                    text(" Welcome to TD "),
                    components(
                            ComponentColor.from(Color.gray),
                            text("(v"),
                            text(this.plugin.getMetadata().getVersion()),
                            text(')')),
                    text(':'),
                    newline(),
                    text('>', ComponentColor.ACCENT),
                    text(" The enemy is currently at %.2fx strength."
                            .formatted(Vars.state.rules.waveTeam.rules().unitHealthMultiplier))));
        });
    }
}
