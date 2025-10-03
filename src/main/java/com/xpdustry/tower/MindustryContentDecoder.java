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

import arc.util.Strings;
import com.xpdustry.distributor.api.key.CTypeKey;
import mindustry.Vars;
import mindustry.ctype.MappableContent;
import org.github.gestalt.config.decoder.DecoderContext;
import org.github.gestalt.config.decoder.LeafDecoder;
import org.github.gestalt.config.decoder.Priority;
import org.github.gestalt.config.entity.ValidationError;
import org.github.gestalt.config.entity.ValidationLevel;
import org.github.gestalt.config.node.ConfigNode;
import org.github.gestalt.config.reflect.TypeCapture;
import org.github.gestalt.config.tag.Tags;
import org.github.gestalt.config.utils.GResultOf;

final class MindustryContentDecoder<T extends MappableContent> extends LeafDecoder<T> {

    private final CTypeKey<T> key;

    MindustryContentDecoder(final CTypeKey<T> key) {
        this.key = key;
    }

    @Override
    public Priority priority() {
        return Priority.MEDIUM;
    }

    @Override
    public String name() {
        return "Mindustry" + Strings.capitalize(this.key.getContentType().name());
    }

    @Override
    public boolean canDecode(final String path, final Tags tags, final ConfigNode node, final TypeCapture<?> type) {
        return this.key.getKey().getToken().getRawType().isAssignableFrom(type.getRawType());
    }

    @SuppressWarnings("unchecked")
    @Override
    protected GResultOf<T> leafDecode(final String path, final ConfigNode node, final DecoderContext context) {
        final var value = node.getValue().orElse("");
        final var content = Vars.content.getByName(this.key.getContentType(), value);
        if (content == null) {
            return GResultOf.errors(new UnknownMindustryContentError<>(path, this.key, value));
        }
        return GResultOf.result((T) content);
    }

    static final class UnknownMindustryContentError<T extends MappableContent> extends ValidationError {

        private final String path;
        private final CTypeKey<T> key;
        private final String value;

        private UnknownMindustryContentError(final String path, final CTypeKey<T> key, String value) {
            super(ValidationLevel.ERROR);
            this.path = path;
            this.key = key;
            this.value = value;
        }

        @Override
        public String description() {
            return "Unable to find mindustry content named " + this.value + " of type "
                    + this.key.getContentType().name() + " on path: " + this.path;
        }
    }
}
