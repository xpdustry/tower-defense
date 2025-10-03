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
import arc.math.Mathf;
import arc.math.geom.Vec2;
import arc.struct.IntMap;
import arc.struct.IntSet;
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
import java.util.Objects;
import java.util.zip.InflaterInputStream;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.content.Fx;
import mindustry.content.StatusEffects;
import mindustry.content.UnitTypes;
import mindustry.core.GameState;
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
    private static final int MAX_SPAWN_SEARCH_RADIUS = 4;

    private final TowerConfigProvider config;
    private final TowerPathfinder pathfinder;
    private final IntMap<Interval> messageRateLimits = new IntMap<>();
    private final IntSet towerBlockWhitelist = new IntSet();

    public TowerLogic(final TowerConfigProvider config, final TowerPathfinder pathfinder) {
        this.config = config;
        this.pathfinder = pathfinder;
    }

    @Override
    public void onPluginInit() {
        // TODO Store modifications somewhere to not make these changes permanent

        // Make sure all enemy units are targetable and hittable and count to waves
        UnitTypes.emanate.targetable = UnitTypes.emanate.hittable = UnitTypes.emanate.isEnemy = true;
        UnitTypes.evoke.targetable = UnitTypes.evoke.hittable = UnitTypes.evoke.isEnemy = true;
        UnitTypes.incite.targetable = UnitTypes.incite.hittable = UnitTypes.incite.isEnemy = true;
        UnitTypes.mono.isEnemy = true;

        // Weapon qol so units don't damage things they shouldn't
        UnitTypes.crawler.weapons.first().shootOnDeath = false;
        UnitTypes.navanax.weapons.each(w -> {
            if (w.name.equalsIgnoreCase("plasma-laser-mount")) {
                w.autoTarget = false;
                w.controllable = true;
            }
        });

        // Dont let legs damage buildings
        UnitTypes.arkyid.legSplashDamage = UnitTypes.arkyid.legSplashRange = 0;
        UnitTypes.toxopid.legSplashDamage = UnitTypes.toxopid.legSplashRange = 0;
        UnitTypes.tecta.legSplashDamage = UnitTypes.tecta.legSplashRange = 0;
        UnitTypes.collaris.legSplashDamage = UnitTypes.collaris.legSplashRange = 0;

        // No crush damage
        UnitTypes.vanquish.crushDamage = 0;
        UnitTypes.conquer.crushDamage = 0;
        // Latum and Renale also have it, but it is their only form of damage and won't be removed
    }

    @EventHandler
    void onConfigUpdate(final TowerConfigReloadEvent event) {
        this.towerBlockWhitelist.clear();
        for (final var block : this.config.get().blockWhitelist()) {
            this.towerBlockWhitelist.add(block.id);
        }
    }

    @EventHandler
    void onGameStart(final EventType.StateChangeEvent event) {
        if (!(event.from == GameState.State.menu && event.to == GameState.State.playing)) {
            return;
        }
        Vars.state.rules.waveTeam.data().units.forEach(u -> u.controller(new TowerAI()));
    }

    @EventHandler
    void onPlayerQuit(final EventType.PlayerLeave event) {
        this.messageRateLimits.remove(event.player.id());
    }

    @PlayerActionHandler
    boolean onCoreBuildInteract(final Administration.PlayerAction action) {
        return switch (action.type) {
            case depositItem, withdrawItem -> !this.hasCoreBlock(action.tile);
            case placeBlock -> this.hasNoNearbyCore(action.block, action.tile, action.player);
            case dropPayload ->
                !(action.payload.content() instanceof Block block)
                        || this.hasNoNearbyCore(block, action.tile, action.player);
            default -> true;
        };
    }

    @PlayerActionHandler
    boolean onInteractWithTowerPassableFloor(final Administration.PlayerAction action) {
        final Block block;
        switch (action.type) {
            case placeBlock -> block = action.block;
            case dropPayload -> block = action.payload.content() instanceof Block b ? b : Blocks.air;
            default -> block = null;
        }
        if (block == null || this.towerBlockWhitelist.contains(block.id) || block.id == Blocks.air.id) {
            return true;
        }
        final var covered = new IntSet();
        action.tile.getLinkedTilesAs(block, tile -> covered.add(tile.floor().id));
        final var iterator = covered.iterator();
        while (iterator.hasNext) {
            if (this.pathfinder.towerPassableFloors.contains(iterator.next())) {
                Call.label(
                        action.player.con, "[scarlet]" + Iconc.cancel, 1F, action.tile.worldx(), action.tile.worldy());
                return false;
            }
        }
        return true;
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
                    if (this.messageRateLimits
                            .get(action.player.id(), Interval::new)
                            .get(Time.toSeconds)) {
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

        final var config = this.config.get();
        final var items = new ItemSeq();
        final var data = config.units().get(event.unit.type());
        if (data == null) return;

        final var drops = Objects.requireNonNull(config.drops().get(data.drop()));
        for (final var drop : drops) drop.apply(items);

        final var core = Vars.state.rules.defaultTeam.core();
        if (core != null) core.items.add(items);

        Distributor.get().getEventBus().post(new EnemyDropEvent(event.unit.x(), event.unit.y(), items));

        if (data.downgrade().isPresent()) {
            final var spawn = new Vec2();
            for (int i = 0; i < config.mitosis(); i++) {

                // Find a valid location first
                spawn.rnd(2 * Vars.tilesize);
                spawn.add(event.unit);
                if (!this.isValidSpawn(Mathf.floor(spawn.x), Mathf.floor(spawn.y))) {
                    this.findNearestValidSpawn(event.unit.tileX(), event.unit.tileY(), spawn);
                    spawn.scl(Vars.tilesize);
                }

                final var unit = data.downgrade().get().create(Vars.state.rules.waveTeam);
                unit.set(spawn);
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
        if (!Vars.state.isPlaying() || Vars.state.rules.waveTeam.data().unitCount == 0) return;
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
                    Call.label(player.con, "[scarlet]" + Iconc.cancel, 1F, tile.worldx(), tile.worldy());
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

    /** @author JasonP01 */
    private void findNearestValidSpawn(final int x, final int y, final Vec2 spawn) {
        if (this.isValidSpawn(x, y)) {
            spawn.set(x, y);
            return;
        }
        for (int radius = 1; radius <= MAX_SPAWN_SEARCH_RADIUS; radius++) {
            for (int i = -radius; i <= radius; i++) {
                if (this.isValidSpawn(x + i, y + radius)) {
                    spawn.set(x + i, y + radius);
                    return;
                }
                if (this.isValidSpawn(x + i, y - radius)) {
                    spawn.set(x + i, y - radius);
                    return;
                }
                if (i > -radius && i < radius) {
                    if (this.isValidSpawn(x + radius, y + i)) {
                        spawn.set(x + radius, y + i);
                        return;
                    }
                    if (this.isValidSpawn(x - radius, y + i)) {
                        spawn.set(x - radius, y + i);
                        return;
                    }
                }
            }
        }
        spawn.set(x, y);
    }

    private boolean isValidSpawn(final int x, final int y) {
        final var tile = Vars.world.tile(x, y);
        return tile != null
                && this.pathfinder.towerPassableFloors.contains(tile.floorID())
                && tile.block().id == Blocks.air.id;
    }
}
