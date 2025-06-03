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
import mindustry.content.UnitTypes;
import mindustry.ctype.ContentType;
import mindustry.type.Item;
import mindustry.type.UnitType;
import mindustry.world.Block;
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
        final var pathfinder = new TowerPathfinder(this);
        Vars.pathfinder = pathfinder;
        this.addListener(pathfinder);
        this.addListener(new TowerRenderer());
        this.addListener(new TowerCommands(this));

        // Make sure all enemy units are targetable and hittable and count to waves
        UnitTypes.emanate.targetable = UnitTypes.emanate.hittable = UnitTypes.emanate.isEnemy = true;
        UnitTypes.evoke.targetable = UnitTypes.evoke.hittable = UnitTypes.evoke.isEnemy = true;
        UnitTypes.incite.targetable = UnitTypes.incite.hittable = UnitTypes.incite.isEnemy = true;
        UnitTypes.mono.isEnemy = true;

        // Weapon qol so units dont damage things they shouldnt
        UnitTypes.crawler.weapons.first().shootOnDeath = false;
        UnitTypes.navanax.weapons.each(w -> { 
            if(w.name.equalsIgnoreCase("plasma-laser-mount")) { 
                w.autoTarget = false; 
                w.controllable = true; 
            }
        });

        // Dont let legs damage buildings
        UnitTypes.arkyid.legSplashDamage = UnitTypes.arkyid.legSplashRange = 0;
        UnitTypes.toxopid.legSplashDamage = UnitTypes.toxopid.legSplashRange = 0;
        UnitTypes.tecta.legSplashDamage = UnitTypes.tecta.legSplashRange = 0;
        UnitTypes.collaris.legSplashDamage = UnitTypes.collaris.legSplashRange = 0;
    }

    @Override
    public void onExit() {
        // Revert all modifications on exit
        UnitTypes.emanate.targetable = UnitTypes.emanate.hittable = UnitTypes.emanate.isEnemy = false;
        UnitTypes.evoke.targetable = UnitTypes.evoke.hittable = UnitTypes.evoke.isEnemy = false;
        UnitTypes.incite.targetable = UnitTypes.incite.hittable = UnitTypes.incite.isEnemy = false;
        UnitTypes.mono.isEnemy = false;

        UnitTypes.crawler.weapons.first().shootOnDeath = true;
        UnitTypes.navanax.weapons.each(w -> { 
            if(w.name.equalsIgnoreCase("plasma-laser-mount")) { 
                w.autoTarget = true; 
                w.controllable = false; 
            }
        });

        UnitTypes.arkyid.legSplashDamage = 32;
        UnitTypes.arkyid.legSplashRange = 30;
        UnitTypes.toxopid.legSplashDamage = 80;
        UnitTypes.toxopid.legSplashRange = 60;
        UnitTypes.tecta.legSplashDamage = 32;
        UnitTypes.tecta.legSplashRange = 30;
        UnitTypes.collaris.legSplashDamage = 32;
        UnitTypes.collaris.legSplashRange = 32;
    }

    @Override
    protected void addListener(final PluginListener listener) {
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

        final var blocks = root.node("buildable-on-path").isNull() 
            ? null : root.node("buildable-on-path").childrenList().stream()
                .map(node -> Objects.requireNonNull(
                        Vars.content.<Block>getByName(ContentType.block, node.getString()),
                        "Unknown block " + node.getString()))
                .toList();

        final var drops = root.node("drops").childrenMap().entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(
                entry -> entry.getKey().toString(), // Convert category keys to String
                    categoryEntry -> categoryEntry.getValue().childrenMap().entrySet().stream()
                        .collect(Collectors.toUnmodifiableMap(
                            dropEntry -> dropEntry.getKey().toString(), // Convert tier keys to String
                            dropEntry -> dropEntry.getValue().childrenList().stream()
                                .map(this::parseDrop)
                                .toList()
                        ))
            ));

        final var units = root.node("units").childrenMap().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> {
                            final var name = entry.getKey().toString();
                            return Objects.requireNonNull(
                                    Vars.content.<UnitType>getByName(ContentType.unit, name), "Unknown unit " + name);
                        },
                        entry -> parseUnit(drops, entry.getValue())));

        return new TowerConfig(
                root.node("health-multiplier").getFloat(1.03F),
                root.node("mitosis").getBoolean(true),
                root.node("downgrade").getBoolean(true),
                root.node("unit-bind").getBoolean(false),
                blocks,
                drops,
                units);
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

    private TowerConfig.UnitData parseUnit(final Map<String, Map<String, List<TowerDrop>>> drops, final ConfigurationNode node) {
        final var dropName = Objects.requireNonNull(node.node("drop").getString(), "drop field missing for " + node.path());
        
        // Split the dropName into category and tier
        final var dropParts = dropName.split("_", 2);
        if (dropParts.length != 2) {
            throw new RuntimeException("Invalid drop name format: " + dropName);
        }
        final var category = dropParts[0];
        final var tier = dropParts[1];

        // Retrieve the drop list
        if (!drops.containsKey(category)) {
            throw new RuntimeException("Unknown drop category '" + category + "' in node path: " + node.path() + " (from drop name: " + dropName + ")");
        }
        final var categoryDrops = drops.get(category);
        if (!categoryDrops.containsKey(tier)) {
            throw new RuntimeException("Unknown drop tier '" + tier + "' in category '" + category + "' from drop name: " + dropName);
        }
        final var drop = Objects.requireNonNull(categoryDrops.get(tier), "Unknown drop tier " + tier);

        // Handle downgrade
        final var downgradeName = node.node("downgrade").getString();
        final var downgrade = downgradeName == null
                ? null
                : Objects.requireNonNull(
                        Vars.content.<UnitType>getByName(ContentType.unit, downgradeName),
                        "Unknown unit " + downgradeName);

        return new TowerConfig.UnitData(drop, downgrade);
    }
}
