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

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.github.gestalt.config.decoder.Decoder;
import org.github.gestalt.config.decoder.DecoderContext;
import org.github.gestalt.config.decoder.Priority;
import org.github.gestalt.config.entity.ValidationError;
import org.github.gestalt.config.entity.ValidationLevel;
import org.github.gestalt.config.node.ConfigNode;
import org.github.gestalt.config.node.MapNode;
import org.github.gestalt.config.reflect.TypeCapture;
import org.github.gestalt.config.tag.Tags;
import org.github.gestalt.config.utils.GResultOf;

// Modified version of https://github.com/gestalt-config/gestalt/issues/235#issuecomment-2737554670
final class SealedConfigDecoder implements Decoder<Object> {

    @Override
    public Priority priority() {
        return Priority.MEDIUM;
    }

    @Override
    public String name() {
        return "SealedConfig";
    }

    @Override
    public boolean canDecode(final String path, final Tags tags, final ConfigNode node, final TypeCapture<?> type) {
        final var clazz = type.getRawType();

        final var annotation = clazz.getAnnotation(SealedConfig.class);
        if (!(clazz.isSealed() && node instanceof MapNode && annotation != null)) {
            return false;
        }

        final var names = this.getSealedConfigSubclasses(clazz);
        if (annotation.def().isBlank()) {
            throw new RuntimeException("Missing default subclass of @SealedConfig for " + clazz.getName());
        }
        if (!names.containsKey(annotation.def())) {
            throw new RuntimeException(
                    "Unknown subclass of @SealedConfig for " + clazz.getName() + ": " + annotation.def());
        }

        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public GResultOf<Object> decode(
            final String path,
            final Tags tags,
            final ConfigNode node,
            final TypeCapture<?> type,
            final DecoderContext context) {
        final var clazz = type.getRawType();
        final var subclasses = this.getSealedConfigSubclasses(clazz);
        final var property = node.getKey("type");

        final Class<?> subclass;
        if (property.isEmpty()) {
            subclass = Objects.requireNonNull(
                    subclasses.get(Objects.requireNonNull(clazz.getAnnotation(SealedConfig.class))
                            .def()));
        } else {
            final var value = property.get().getValue();
            if (value.isEmpty()) {
                return GResultOf.errors(new InvalidSealedConfigTypeError(path, clazz, ""));
            }
            subclass = subclasses.get(value.get());
            if (subclass == null) {
                return GResultOf.errors(new InvalidSealedConfigTypeError(path, clazz, value.get()));
            }
        }

        return (GResultOf<Object>)
                context.getDecoderService().decodeNode(path, tags, node, TypeCapture.of(subclass), context);
    }

    @SuppressWarnings({"unchecked", "EmptyCatch"})
    private <T> Map<String, Class<? extends T>> getSealedConfigSubclasses(final Class<T> clazz) {
        final Map<String, Class<? extends T>> names = new HashMap<>();
        for (final var permitted : clazz.getPermittedSubclasses()) {
            final var annotation = permitted.getAnnotation(SealedConfig.class);
            if (annotation == null) {
                throw new RuntimeException("Missing @SealedConfig annotation for class " + permitted.getName());
            }
            if (annotation.name().isBlank()) {
                throw new RuntimeException("Subtype name required for @SealedConfig for " + permitted.getName());
            }
            if (names.put(annotation.name(), (Class<? extends T>) permitted) != null) {
                throw new RuntimeException("Duplicate name for @SealedConfig for " + permitted.getName());
            }
            try {
                final var ignored = permitted.getField("type");
                throw new RuntimeException("Conflicting 'type' field in @SealedConfig " + permitted.getName());
            } catch (final NoSuchFieldException ignored) {
            }
        }
        return names;
    }

    static final class InvalidSealedConfigTypeError extends ValidationError {

        private final String path;
        private final Class<?> clazz;
        private final String value;

        private InvalidSealedConfigTypeError(final String path, final Class<?> clazz, final String value) {
            super(ValidationLevel.ERROR);
            this.path = path;
            this.clazz = clazz;
            this.value = value;
        }

        @Override
        public String description() {
            return "Invalid @SealedConfig type for class " + this.clazz.getName() + " (value=" + this.value
                    + ") for path: " + this.path;
        }
    }
}
