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

import arc.struct.IntSet;
import com.xpdustry.distributor.api.annotation.EventHandler;
import com.xpdustry.distributor.api.annotation.PlayerActionHandler;
import com.xpdustry.distributor.api.collection.MindustryCollections;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.distributor.api.util.Priority;
import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.gen.Call;
import mindustry.gen.Iconc;
import mindustry.net.Administration;
import mindustry.world.Block;
import mindustry.world.Tile;

final class TowerPathfinder extends Pathfinder implements PluginListener {

    private static final int BIT_MASK_TOWER_PASSABLE = (1 << 30);

    public final IntSet towerPassableFloors = new IntSet();
    private final IntSet towerBlockWhitelist = new IntSet();

    @EventHandler
    void onServerLoadEvent(final EventType.ServerLoadEvent event) {
        // TODO This trick may not be able to support modded units with custom path types
        MindustryCollections.mutableList(Pathfinder.costTypes).replaceAll(TowerPathCostWrapper::new);
    }

    @EventHandler(priority = Priority.HIGH)
    void onWorldLoadEvent(final EventType.WorldLoadEvent event) {
        onGenericLoadEvent();
    }

    @EventHandler(priority = Priority.HIGH)
    void onSaveLoadEvent(final EventType.SaveLoadEvent event) {
        onGenericLoadEvent();
    }

    private void onGenericLoadEvent() {
        this.towerPassableFloors.clear();
        for (final var tile : Vars.world.tiles) {
            if (tile.overlay().equals(Blocks.spawn)) {
                this.towerPassableFloors.add(tile.floor().id);
            }
        }
    }

    public void updateConfiguration(final TowerConfig config) {
        this.towerBlockWhitelist.clear();
        final var newWhitelist = config.blockWhitelist();

        for (final Block block : newWhitelist) {
            this.towerBlockWhitelist.add(block.id);
        }
    }

    @PlayerActionHandler
    boolean onInteractWithTowerPassableFloor(final Administration.PlayerAction action) {
        if (action.type == Administration.ActionType.placeBlock
                || action.type == Administration.ActionType.dropPayload) {
            Block block =
                    action.block != null ? action.block : action.payload.content() instanceof Block b ? b : Blocks.air;
            if (towerBlockWhitelist.contains(block.id) || block == Blocks.air) return true;
            final var covered = new IntSet();
            action.tile.getLinkedTilesAs(block, tile -> covered.add(tile.floor().id));
            final var iterator = covered.iterator();
            while (iterator.hasNext) {
                if (towerPassableFloors.contains(iterator.next())) {
                    Call.label(
                            action.player.con, "[scarlet]" + Iconc.cancel, 1F, action.tile.x * 8f, action.tile.y * 8f);
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public int packTile(final Tile tile) {
        int pack = super.packTile(tile);
        if (this.towerPassableFloors.contains(tile.floor().id) && !tile.block().isStatic()) {
            pack |= BIT_MASK_TOWER_PASSABLE;
        }
        return pack;
    }

    private record TowerPathCostWrapper(PathCost inner) implements PathCost {

        @Override
        public int getCost(final int team, final int tile) {
            int cost = this.inner.getCost(team, tile);
            if ((tile & BIT_MASK_TOWER_PASSABLE) == 0 && team == Vars.state.rules.waveTeam.id && cost >= 0) {
                cost += 6000;
            }
            return cost;
        }
    }
}
