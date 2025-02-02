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

import com.xpdustry.distributor.api.annotation.PluginAnnotationProcessor;
import com.xpdustry.distributor.api.plugin.AbstractMindustryPlugin;
import com.xpdustry.distributor.api.plugin.PluginListener;
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
public final class TowerPlugin extends AbstractMindustryPlugin {

    private final PluginAnnotationProcessor<List<Object>> processor = PluginAnnotationProcessor.compose(
            PluginAnnotationProcessor.events(this),
            PluginAnnotationProcessor.tasks(this),
            PluginAnnotationProcessor.triggers(this),
            PluginAnnotationProcessor.playerActions(this));

    private @Nullable TowerConfig config;

    @Override
    public void onInit() {
        try {
            this.config = load();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load TD", e);
        }
        this.addListener(new TowerLogic(this));
    }

    @Override
    public void addListener(final PluginListener listener) {
        super.addListener(listener);
        processor.process(listener);
    }

    TowerConfig config() {
        return Objects.requireNonNull(config);
    }

    private TowerConfig load() throws IOException {
        final var file = this.getDirectory().resolve("config.yaml");
        if (Files.notExists(file)) {
            try (final var stream = Objects.requireNonNull(
                    this.getClass().getClassLoader().getResourceAsStream("com/xpdustry/tower/config.yaml"))) {
                Files.copy(stream, file);
            }
        }
        final var root = YamlConfigurationLoader.builder().path(file).build().load();
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
                                    Vars.content.<UnitType>getByName(ContentType.unit, name), "Unknown unit " + name);
                        },
                        entry -> parseUnit(drops, entry.getValue())));

        final var multiplierNode = root.node("health-multiplier");
        if (multiplierNode.virtual()) {
            throw new RuntimeException("health-multiplier field missing");
        }

        return new TowerConfig(multiplierNode.getFloat(), drops, units);
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
