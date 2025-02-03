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

import mindustry.Vars;
import mindustry.ai.Pathfinder;
import mindustry.ai.types.GroundAI;
import mindustry.entities.Units;
import mindustry.gen.Teamc;
import mindustry.world.blocks.storage.CoreBlock;
import org.jspecify.annotations.Nullable;

final class TowerAI extends GroundAI {

    @Override
    public @Nullable Teamc target(
            final float x, final float y, final float range, final boolean air, final boolean ground) {
        return Units.closestTarget(
                this.unit().team(), x, y, range, $ -> false, build -> build.block() instanceof CoreBlock && ground);
    }

    @Override
    public void pathfind(final int pathTarget) {
        int costType = unit().isFlying() ? TowerPathfinder.COST_AIR : unit().pathType();
        final var tile = unit().tileOn();
        if (tile == null) return;
        final var targetTile =
                Vars.pathfinder.getTargetTile(tile, Vars.pathfinder.getField(unit().team, costType, pathTarget));
        if (tile == targetTile || (costType == Pathfinder.costNaval && !targetTile.floor().isLiquid)) return;
        unit().movePref(vec.trns(unit().angleTo(targetTile.worldx(), targetTile.worldy()), unit().speed()));
    }
}
