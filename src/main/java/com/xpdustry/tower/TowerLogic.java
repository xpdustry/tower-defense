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

import arc.graphics.Color;
import arc.struct.IntMap;
import arc.util.Interval;
import arc.util.Time;
import com.xpdustry.distributor.api.Distributor;
import com.xpdustry.distributor.api.annotation.EventHandler;
import com.xpdustry.distributor.api.annotation.PlayerActionHandler;
import com.xpdustry.distributor.api.annotation.TaskHandler;
import com.xpdustry.distributor.api.component.style.ComponentColor;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.distributor.api.scheduler.MindustryTimeUnit;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.gen.Player;
import mindustry.logic.LAssembler;
import mindustry.logic.LExecutor;
import mindustry.net.Administration;
import mindustry.type.ItemSeq;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.logic.LogicBlock;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.world.blocks.storage.StorageBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.xpdustry.distributor.api.component.TextComponent.text;

final class TowerLogic implements PluginListener {

    private static final Logger logger = LoggerFactory.getLogger(TowerLogic.class);
    private final TowerConfigProvider config;
    private final IntMap<Interval> messageRateLimits = new IntMap<>();

    public TowerLogic(final TowerConfigProvider config) {
        this.config = config;
    }

    @EventHandler
    void onPlayerQuit(final EventType.PlayerLeave event) {
        messageRateLimits.remove(event.player.id());
    }

    @PlayerActionHandler
    boolean onCoreBuildInteract(final Administration.PlayerAction action) {
        return switch (action.type) {
            case depositItem, withdrawItem -> !hasCoreBlock(action.tile);
            case placeBlock -> hasNoNearbyCore(action.block, action.tile, action.player);
            case dropPayload -> !(action.payload.content() instanceof Block block)
                    || hasNoNearbyCore(block, action.tile, action.player);
            default -> true;
        };
    }

    @PlayerActionHandler
    boolean onLogicUBindAttempt(final Administration.PlayerAction action) {
        final Block block;
        switch (action.type) {
            case placeBlock -> block = action.block;
            case configure -> block = action.tile.block();
            default -> {
                return true;
            }
        }

        if (!(block instanceof LogicBlock && action.config instanceof byte[] data)
                || this.config.get().ubind()
                || block.privileged) {
            return true;
        }

        try (final var stream = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(data)))) {
            final var version = stream.read();
            if (version == 0) {
                throw new IOException("Unsupported version");
            }

            final var length = stream.readInt();
            if (length > 1024 * 100) {
                throw new IOException("Data length too big");
            }

            final var bytes = new byte[length];
            stream.readFully(bytes);

            final var total = stream.readInt();
            for (var i = 0; i < total; i++) {
                stream.readUTF(); // link name
                stream.readShort(); // x
                stream.readShort(); // y
            }

            final var code = new String(bytes, StandardCharsets.UTF_8);
            final var assembler = LAssembler.assemble(code, block.privileged);

            for (final var instruction : assembler.instructions) {
                if (instruction instanceof LExecutor.UnitBindI) {
                    if (messageRateLimits.get(action.player.id(), Interval::new).get(Time.toSeconds)) {
                        Distributor.get()
                                .getAudienceProvider()
                                .getPlayer(action.player)
                                .sendWarning(text(
                                        "You can't use ubind in this gamemode.", ComponentColor.from(Color.scarlet)));
                    }
                    return false;
                }
            }

            return true;
        } catch (final IOException e) {
            logger.debug("Failed to parse logic code", e);
            return true;
        }
    }

    @EventHandler
    void onUnitSpawn(final EventType.UnitSpawnEvent event) {
        if (event.unit.team() == Vars.state.rules.waveTeam) {
            event.unit.controller(new TowerAI());
        }
    }

    @EventHandler
    void onUnitDeath(final EventType.UnitDestroyEvent event) {
        if (event.unit.team() != Vars.state.rules.waveTeam) return;

        final var items = new ItemSeq();
        final var data = this.config.get().units().get(event.unit.type());
        if (data == null) return;

        for (final var drop : data.drops()) drop.apply(items);

        final var core = Vars.state.rules.defaultTeam.core();
        if (core != null) core.items.add(items);

        Distributor.get().getEventBus().post(new EnemyDropEvent(event.unit.x(), event.unit.y(), items));

        if (this.plugin.config().downgrade() && data.downgrade() != null) {
            final var mitosis = this.plugin.config().mitosis();
            final var repeat = mitosis ? 2 : 1;
            for (int i = 0; i < repeat; i++) {
                final var unit = data.downgrade().create(Vars.state.rules.waveTeam);
                unit.set(event.unit.x(), event.unit.y());
                unit.rotation(event.unit.rotation());
                unit.apply(StatusEffects.slow, (float) MindustryTimeUnit.TICKS.convert(5L, MindustryTimeUnit.SECONDS));
                unit.controller(new TowerAI());
                unit.add();
                Call.effect(Fx.spawn, event.unit.x(), event.unit.y(), 0F, Vars.state.rules.waveTeam.color);
            }
        }
    }

    @TaskHandler(delay = 1L, interval = 1L, unit = MindustryTimeUnit.MINUTES)
    void onEnemyHealthMultiply() {
        if (!Vars.state.isPlaying()) return;
        final var prev = Vars.state.rules.waveTeam.rules().unitHealthMultiplier;
        final var next = prev * this.config.get().healthMultiplier();
        Vars.state.rules.waveTeam.rules().unitHealthMultiplier = next;
        Call.setRules(Vars.state.rules);
        Distributor.get().getEventBus().post(new PowerIncreaseEvent.Health(Vars.state.rules.waveTeam, prev, next));
    }

    private boolean hasNoNearbyCore(final Block block, final Tile tile, final Player player) {
        final int rx = tile.x + block.sizeOffset;
        final int ry = tile.y + block.sizeOffset;
        for (int i = rx - 1; i <= rx + block.size; i++) {
            for (int j = ry - 1; j <= ry + block.size; j++) {
                final var at = Vars.world.tile(i, j);
                if (at != null && hasCoreBlock(at)) {
                    Call.label(player.con, "[scarlet]" + Iconc.cancel, 1F, i, j);
                    return false;
                }
            }
        }
        return true;
    }

    private boolean hasCoreBlock(final Tile tile) {
        return (tile.block() instanceof CoreBlock
                || (tile.build instanceof StorageBlock.StorageBuild build && build.linkedCore != null));
    }
}
