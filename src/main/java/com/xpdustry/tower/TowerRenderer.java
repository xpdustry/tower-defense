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

import arc.struct.IntMap;
import arc.util.Strings;
import com.xpdustry.distributor.api.annotation.EventHandler;
import com.xpdustry.distributor.api.annotation.TaskHandler;
import com.xpdustry.distributor.api.component.render.ComponentStringBuilder;
import com.xpdustry.distributor.api.component.style.ComponentColor;
import com.xpdustry.distributor.api.key.KeyContainer;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.distributor.api.scheduler.MindustryTimeUnit;
import java.text.DecimalFormat;
import java.util.LinkedList;
import java.util.List;
import mindustry.Vars;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.gen.WorldLabel;
import mindustry.type.ItemSeq;

import static com.xpdustry.distributor.api.component.ListComponent.components;
import static com.xpdustry.distributor.api.component.NumberComponent.number;
import static com.xpdustry.distributor.api.component.TextComponent.space;
import static com.xpdustry.distributor.api.component.TextComponent.text;

public final class TowerRenderer implements PluginListener {

    private static final DecimalFormat MULTIPLIER_FORMAT = new DecimalFormat("#.##");
    private static final IntMap<String> ITEM_ICONS = new IntMap<>();

    static {
        for (final var item : Vars.content.items()) {
            try {
                final var field = Iconc.class.getDeclaredField(Strings.kebabToCamel("item-" + item.name));
                ITEM_ICONS.put(item.id, Character.toString((char) field.get(null)));
            } catch (final ReflectiveOperationException ignored) {
            }
        }
    }

    private final List<LabelWrapper> wrappers = new LinkedList<>();

    @EventHandler
    void onEnemyPowerIncreaseEvent(final TowerEnemyPowerUpEvent.Health event) {
        if ((int) event.after() > (int) event.before()) {
            Call.sendMessage("Health multiplier has increased to " + MULTIPLIER_FORMAT.format(event.after()));
        }
    }

    @EventHandler
    void onReset(final EventType.ResetEvent event) {
        wrappers.clear();
    }

    @EventHandler
    void onTowerDrop(final TowerDropEvent event) {
        final LinkedList<LabelWrapper> closest = new LinkedList<>();
        for (final var wrapper : wrappers) {
            if (wrapper.label.dst(event.x(), event.y()) <= 3 * Vars.tilesize && wrapper.items.total <= 3000) {
                closest.add(wrapper);
            }
        }
        if (closest.isEmpty()) {
            final var wrapper = new LabelWrapper();
            wrapper.label.flags((byte) (WorldLabel.flagBackground | WorldLabel.flagOutline));
            wrapper.label.fontSize = 2F;
            wrapper.update(event);
            wrappers.add(wrapper);
        } else {
            final var first = closest.removeFirst();
            wrappers.removeAll(closest);
            for (final var wrapper : closest) {
                first.items.add(wrapper.items);
                wrapper.remove();
            }
            first.update(event);
        }
    }

    @TaskHandler(interval = 1L, unit = MindustryTimeUnit.SECONDS)
    void onStaleLabelRemoval() {
        final var iterator = wrappers.iterator();
        while (iterator.hasNext()) {
            final var wrapper = iterator.next();
            if (wrapper.age() >= 2) {
                wrapper.remove();
                iterator.remove();
            }
        }
    }

    private static final class LabelWrapper {
        private final WorldLabel label = WorldLabel.create();
        private final ItemSeq items = new ItemSeq();
        private long lastUpdate = System.currentTimeMillis();

        long age() {
            return (System.currentTimeMillis() - lastUpdate) / 1000L;
        }

        void update(final TowerDropEvent event) {
            label.set(event.x(), event.y());
            items.add(event.items());
            label.text(ComponentStringBuilder.mindustry(KeyContainer.empty())
                    .append(components()
                            .modify(builder -> {
                                final var color = ComponentColor.from(Vars.state.rules.defaultTeam.color);
                                items.each((item, amount) -> {
                                    builder.append(text('+', color));
                                    builder.append(number(amount, color));
                                    builder.append(space());
                                    builder.append(text(ITEM_ICONS.get(item.id, item.name)));
                                    builder.append(text(" "));
                                });
                            })
                            .build())
                    .toString());
            label.add();
            lastUpdate = System.currentTimeMillis();
        }

        void remove() {
            label.remove();
            Call.removeWorldLabel(label.id);
        }
    }
}
