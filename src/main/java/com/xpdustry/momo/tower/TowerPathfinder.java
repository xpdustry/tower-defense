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
package com.xpdustry.momo.tower;

import arc.struct.IntSet;
import com.xpdustry.distributor.api.annotation.EventHandler;
import com.xpdustry.distributor.api.annotation.PlayerActionHandler;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.distributor.api.util.Priority;
import com.xpdustry.momo.MoGameMode;
import com.xpdustry.momo.MoMoAPI;
import java.util.Objects;
import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.content.Blocks;
import mindustry.game.EventType;
import mindustry.net.Administration;
import mindustry.world.Tile;

final class TowerPathfinder extends Pathfinder implements PluginListener {

    private static final int BIT_MASK_TOWER_PASSABLE = (1 << 30);

    static {
        wrapPathCost(Pathfinder.costGround);
        wrapPathCost(Pathfinder.costLegs);
    }

    private static void wrapPathCost(final int pathCostType) {
        final var costType = Objects.requireNonNull(Pathfinder.costTypes.get(pathCostType));
        Pathfinder.costTypes.set(pathCostType, (team, tile) -> {
            int cost = costType.getCost(team, tile);
            if (MoMoAPI.get().isActive(MoGameMode.TOWER_DEFENSE)
                    && (tile & BIT_MASK_TOWER_PASSABLE) == 0
                    && team == Vars.state.rules.waveTeam.id
                    && cost >= 0) {
                cost += 6000;
            }
            return cost;
        });
    }

    private final IntSet towerPassableFloors = new IntSet();

    @EventHandler(priority = Priority.HIGH)
    void onWorldLoadEvent(final EventType.WorldLoadEvent event) {
        onGenericLoadEvent();
    }

    @EventHandler(priority = Priority.HIGH)
    void onSaveLoadEvent(final EventType.SaveLoadEvent event) {
        onGenericLoadEvent();
    }

    private void onGenericLoadEvent() {
        if (MoMoAPI.get().isActive(MoGameMode.TOWER_DEFENSE)) {
            for (final var tile : Vars.world.tiles) {
                if (tile.overlay().equals(Blocks.spawn)) {
                    towerPassableFloors.add(tile.floor().id);
                }
            }
        } else {
            towerPassableFloors.clear();
        }
    }

    @PlayerActionHandler
    boolean onInteractWithTowerPassableFloor(final Administration.PlayerAction action) {
        if (!MoMoAPI.get().isActive(MoGameMode.TOWER_DEFENSE)) return true;
        return !(action.type == Administration.ActionType.placeBlock
                && this.towerPassableFloors.contains(action.tile.floor().id));
    }

    @Override
    public int packTile(final Tile tile) {
        var packed = super.packTile(tile);
        if (MoMoAPI.get().isActive(MoGameMode.TOWER_DEFENSE)) {
            packed |= (towerPassableFloors.contains(tile.floor().id) ? BIT_MASK_TOWER_PASSABLE : 0);
        }
        return packed;
    }
}
