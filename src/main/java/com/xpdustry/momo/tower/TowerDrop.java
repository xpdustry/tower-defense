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

import java.util.List;
import mindustry.type.Item;
import mindustry.type.ItemSeq;

public sealed interface TowerDrop {

    void apply(final ItemSeq items);

    record Simple(Item item, int amount) implements TowerDrop {

        public Simple {
            if (amount < 1) throw new IllegalArgumentException("amount is lower than one: " + amount);
        }

        @Override
        public void apply(final ItemSeq items) {
            items.add(item, amount);
        }
    }

    record Random(List<TowerDrop> drops) implements TowerDrop {

        private static final java.util.Random RANDOM = new java.util.Random();

        public Random(final List<TowerDrop> drops) {
            if (drops.isEmpty()) {
                throw new IllegalArgumentException("The drop list is empty");
            }
            this.drops = List.copyOf(drops);
        }

        @Override
        public void apply(final ItemSeq items) {
            this.drops.get(RANDOM.nextInt(this.drops.size())).apply(items);
        }
    }
}
