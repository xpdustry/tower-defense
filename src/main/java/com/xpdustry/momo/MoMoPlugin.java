/*
 * This file is part of MOMO. A plugin providing more gamemodes for Mindustry servers.
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
package com.xpdustry.momo;

import com.xpdustry.distributor.api.annotation.PluginAnnotationProcessor;
import com.xpdustry.distributor.api.plugin.AbstractMindustryPlugin;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.momo.tower.TowerConfig;
import com.xpdustry.momo.tower.TowerDrop;
import com.xpdustry.momo.tower.TowerLogic;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import mindustry.Vars;
import mindustry.ctype.ContentType;
import mindustry.type.Item;
import mindustry.type.UnitType;
import org.jspecify.annotations.Nullable;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

@SuppressWarnings("unused")
public final class MoMoPlugin extends AbstractMindustryPlugin implements MoMoAPI {

    private final PluginAnnotationProcessor<List<Object>> processor = PluginAnnotationProcessor.compose(
            PluginAnnotationProcessor.events(this),
            PluginAnnotationProcessor.tasks(this),
            PluginAnnotationProcessor.triggers(this),
            PluginAnnotationProcessor.playerActions(this));

    private @Nullable MoMoConfig config;

    @Override
    public void onInit() {
        try {
            this.config = load();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load MoMo", e);
        }
        this.addListener(new TowerLogic(this));
    }

    @Override
    public void addListener(final PluginListener listener) {
        super.addListener(listener);
        processor.process(listener);
    }

    @Override
    public boolean isActive(final MoGameMode mode) {
        return getMoConfig().mode() == mode;
    }

    public MoMoConfig getMoConfig() {
        return Objects.requireNonNull(config);
    }

    private MoMoConfig load() throws IOException {
        final var file = this.getDirectory().resolve("config.yaml");
        if (Files.notExists(file)) {
            try (final var stream = Objects.requireNonNull(
                    this.getClass().getClassLoader().getResourceAsStream("com/xpdustry/momo/config.yaml"))) {
                Files.copy(stream, file);
            }
        }
        final var node = YamlConfigurationLoader.builder().path(file).build().load();

        final TowerConfig tower;
        {
            final var root = node.node("tower-defense");
            final var drops = root.node("drops").childrenMap().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            entry -> entry.getKey().toString(), entry -> entry.getValue().childrenList().stream()
                                    .map(this::parseDrop)
                                    .toList()));
            final var units = root.node("units").childrenMap().entrySet().stream()
                    .collect(Collectors.toUnmodifiableMap(
                            entry -> {
                                final var name = entry.getKey().toString();
                                return Objects.requireNonNull(
                                        Vars.content.<UnitType>getByName(ContentType.unit, name),
                                        "Unknown unit " + name);
                            },
                            entry -> parseUnit(drops, entry.getValue())));
            tower = new TowerConfig(drops, units);
        }

        MoGameMode mode = null;
        {
            final var root = node.node("game-mode");
            if (!root.virtual()) {
                final var name = Objects.requireNonNull(root.getString());
                mode = switch (name) {
                    case "tower-defense" -> MoGameMode.TOWER_DEFENSE;
                    default -> throw new IllegalArgumentException("Unknown gamemode " + name);};
            }
        }

        return new MoMoConfig(mode, tower);
    }

    private TowerDrop parseDrop(final ConfigurationNode node) {
        final var type = node.node("type").getString("simple");
        switch (type) {
            case "simple" -> {
                final var itemName =
                        Objects.requireNonNull(node.node("item").getString(), "item field missing for " + node.path());
                final var item = Objects.requireNonNull(
                        Vars.content.<Item>getByName(ContentType.item, itemName), "Unknown item " + itemName);
                final var amountNode = node.node("amount");
                if (amountNode.virtual()) throw new RuntimeException("The amount of item is missing");
                return new TowerDrop.Simple(item, amountNode.getInt());
            }
            case "random" -> {
                return new TowerDrop.Random(node.node("items").childrenList().stream()
                        .map(this::parseDrop)
                        .toList());
            }
            default -> throw new RuntimeException("Unknown bounty type: " + type);
        }
    }

    private TowerConfig.UnitData parseUnit(final Map<String, List<TowerDrop>> drops, final ConfigurationNode node) {
        final var dropName =
                Objects.requireNonNull(node.node("drop").getString(), "drop field missing for " + node.path());
        final var drop = Objects.requireNonNull(drops.get(dropName), "Unknown drop bundle " + dropName);
        return new TowerConfig.UnitData(drop);
    }
}
