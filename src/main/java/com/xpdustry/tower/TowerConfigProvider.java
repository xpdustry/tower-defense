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

import arc.util.CommandHandler;
import arc.util.Strings;
import com.xpdustry.distributor.api.Distributor;
import com.xpdustry.distributor.api.key.CTypeKey;
import com.xpdustry.distributor.api.plugin.MindustryPlugin;
import com.xpdustry.distributor.api.plugin.PluginListener;
import com.xpdustry.tower.config.PatchedRecordDecoder;
import com.xpdustry.tower.config.SealedConfig;
import com.xpdustry.tower.config.SnakeYamlLoader;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;
import mindustry.Vars;
import mindustry.ctype.MappableContent;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.decoder.*;
import org.github.gestalt.config.entity.ValidationError;
import org.github.gestalt.config.entity.ValidationLevel;
import org.github.gestalt.config.loader.EnvironmentVarsLoader;
import org.github.gestalt.config.node.ConfigNode;
import org.github.gestalt.config.node.MapNode;
import org.github.gestalt.config.path.mapper.KebabCasePathMapper;
import org.github.gestalt.config.reflect.TypeCapture;
import org.github.gestalt.config.source.EnvironmentConfigSourceBuilder;
import org.github.gestalt.config.source.FileConfigSourceBuilder;
import org.github.gestalt.config.tag.Tags;
import org.github.gestalt.config.utils.GResultOf;
import org.jspecify.annotations.Nullable;

final class TowerConfigProvider implements PluginListener, Supplier<TowerConfig> {

    private final MindustryPlugin plugin;
    private @Nullable TowerConfig config = null;

    public TowerConfigProvider(final MindustryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onPluginInit() {
        try {
            this.reload();
        } catch (final Exception e) {
            throw new RuntimeException("Failed to load TD config", e);
        }
    }

    @Override
    public void onPluginServerCommandsRegistration(CommandHandler handler) {
        handler.register("td-reload", "Reload TD configuration.", args -> {
            try {
                this.reload();
                this.plugin.getLogger().info("TD configuration reloaded!");
            } catch (final Exception e) {
                this.plugin.getLogger().error("Failed to reload TD configuration!", e);
            }
        });
    }

    @Override
    public TowerConfig get() {
        return Objects.requireNonNull(this.config);
    }

    void reload() throws Exception {
        final var file = this.plugin.getDirectory().resolve("config.yaml");
        if (Files.notExists(file)) {
            try (final var stream = Objects.requireNonNull(
                    this.getClass().getClassLoader().getResourceAsStream("com/xpdustry/tower/config.yaml"))) {
                Files.copy(stream, file);
            }
        }

        final var builder = new GestaltBuilder()
                .addSource(FileConfigSourceBuilder.builder().setPath(file).build())
                .addSource(EnvironmentConfigSourceBuilder.builder()
                        .setPrefix("XP_TOWER_DEFENSE")
                        .build())
                .setTreatMissingDiscretionaryValuesAsErrors(false)
                .addDecoder(new PatchedRecordDecoder())
                .addPathMapper(new KebabCasePathMapper())
                .addConfigLoader(new SnakeYamlLoader())
                .addConfigLoader(new EnvironmentVarsLoader())
                .addDecoder(new FloatDecoder())
                .addDecoder(new StringDecoder())
                .addDecoder(new MapDecoder())
                .addDecoder(new BooleanDecoder())
                .addDecoder(new ListDecoder())
                .addDecoder(new SetDecoder())
                .addDecoder(new IntegerDecoder())
                .addDecoder(new OptionalDecoder())
                .addDecoder(new SealedConfigDecoder());
        for (final var key : CTypeKey.ALL) {
            builder.addDecoder(new MindustryContentDecoder<>(key));
        }

        final var gestalt = builder.build();
        gestalt.loadConfigs();
        this.config = gestalt.getConfig("", TowerConfig.class);
        Distributor.get().getEventBus().post(TowerConfigReloadEvent.INSTANCE);
    }

    static final class MindustryContentDecoder<T extends MappableContent> extends LeafDecoder<T> {

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

        private static final class UnknownMindustryContentError<T extends MappableContent> extends ValidationError {

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

    // Modified version of https://github.com/gestalt-config/gestalt/issues/235#issuecomment-2737554670
    private static final class SealedConfigDecoder implements Decoder<Object> {

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

        private static final class InvalidSealedConfigTypeError extends ValidationError {

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
}
