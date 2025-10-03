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
import com.xpdustry.distributor.api.Distributor;
import com.xpdustry.distributor.api.key.CTypeKey;
import com.xpdustry.distributor.api.plugin.MindustryPlugin;
import com.xpdustry.distributor.api.plugin.PluginListener;
import java.nio.file.Files;
import java.util.*;
import java.util.function.Supplier;
import org.github.gestalt.config.builder.GestaltBuilder;
import org.github.gestalt.config.decoder.*;
import org.github.gestalt.config.loader.EnvironmentVarsLoader;
import org.github.gestalt.config.path.mapper.KebabCasePathMapper;
import org.github.gestalt.config.source.EnvironmentConfigSourceBuilder;
import org.github.gestalt.config.source.FileConfigSourceBuilder;
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
                .addDecoder(new RecordDecoder())
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
                .addDecoder(new SealedConfigDecoder());
        for (final var key : CTypeKey.ALL) {
            builder.addDecoder(new MindustryContentDecoder<>(key));
        }

        final var gestalt = builder.build();
        gestalt.loadConfigs();
        this.config = gestalt.getConfig("", TowerConfig.class);
        Distributor.get().getEventBus().post(TowerConfigReloadEvent.INSTANCE);
    }
}
