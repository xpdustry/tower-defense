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

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.github.gestalt.config.entity.ConfigNodeContainer;
import org.github.gestalt.config.entity.ValidationError;
import org.github.gestalt.config.exceptions.GestaltException;
import org.github.gestalt.config.lexer.PathLexer;
import org.github.gestalt.config.lexer.SentenceLexer;
import org.github.gestalt.config.loader.ConfigLoader;
import org.github.gestalt.config.node.ArrayNode;
import org.github.gestalt.config.node.ConfigNode;
import org.github.gestalt.config.node.LeafNode;
import org.github.gestalt.config.node.MapNode;
import org.github.gestalt.config.source.ConfigSourcePackage;
import org.github.gestalt.config.utils.GResultOf;
import org.github.gestalt.config.utils.PathUtil;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.*;

// https://github.com/gestalt-config/gestalt/blob/8b5f7dd9f74f1899e44719df29c17543e5a68597/gestalt-yaml/src/main/java/org/github/gestalt/config/yaml/YamlLoader.java
final class SnakeYamlLoader implements ConfigLoader {

    private final Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
    private final SentenceLexer lexer = new PathLexer();

    @Override
    public String name() {
        return "SnakeYamlLoader";
    }

    @Override
    public boolean accepts(final String format) {
        return "yml".equals(format) || "yaml".equals(format);
    }

    @Override
    public GResultOf<List<ConfigNodeContainer>> loadSource(final ConfigSourcePackage sourcePackage)
            throws GestaltException {
        var source = sourcePackage.getConfigSource();
        if (source.hasStream()) {
            try (final var stream = source.loadStream()) {
                final var reader = new InputStreamReader(stream, StandardCharsets.UTF_8);
                final var node = yaml.compose(reader);
                if (node == null) {
                    throw new GestaltException("Exception loading source: " + source.name() + " no yaml found");
                }
                return construct(node, "")
                        .mapWithError(
                                result -> List.of(new ConfigNodeContainer(result, source, sourcePackage.getTags())));
            } catch (final IOException | NullPointerException e) {
                throw new GestaltException("Exception loading source: " + source.name(), e);
            }
        } else {
            throw new GestaltException("Config source: " + source.name() + " does not have a stream to load.");
        }
    }

    private GResultOf<ConfigNode> construct(final Node _node, final String path) {
        return switch (_node) {
            case SequenceNode node -> {
                final List<ValidationError> errors = new ArrayList<>();
                final List<ConfigNode> array = new ArrayList<>();
                final var values = node.getValue();
                for (int i = 0; i < values.size(); i++) {
                    final var currentPath = PathUtil.pathForIndex(this.lexer, path, i);
                    final var result = construct(values.get(i), currentPath);
                    errors.addAll(result.getErrors());
                    if (!result.hasResults()) {
                        errors.add(new ValidationError.NoResultsFoundForPath(currentPath));
                    } else {
                        array.add(result.results());
                    }
                }
                yield GResultOf.resultOf(new ArrayNode(array), errors);
            }
            case AnchorNode node -> {
                yield construct(node.getRealNode(), path);
            }
            case MappingNode node -> {
                final List<ValidationError> errors = new ArrayList<>();
                final Map<String, ConfigNode> mapNode = new HashMap<>();

                for (final var pair : node.getValue()) {
                    if (!(pair.getKeyNode() instanceof ScalarNode key)) {
                        errors.add(new ValidationError.UnknownNodeTypeDuringLoad(
                                path, _node.getNodeId().name()));
                        continue;
                    }

                    var tokenList = tokenizer(key.getValue());
                    tokenList = tokenList.stream().map(this::normalizeSentence).collect(Collectors.toList());
                    String currentPath = PathUtil.pathForKey(lexer, path, tokenList);

                    final var result = construct(pair.getValueNode(), PathUtil.pathForKey(lexer, path, tokenList));
                    errors.addAll(result.getErrors());
                    if (!result.hasResults()) {
                        errors.add(new ValidationError.NoResultsFoundForPath(currentPath));
                    } else {
                        ConfigNode currentNode = result.results();
                        for (int i = tokenList.size() - 1; i > 0; i--) {
                            Map<String, ConfigNode> nextMapNode = new HashMap<>();
                            nextMapNode.put(tokenList.get(i), currentNode);
                            currentNode = new MapNode(nextMapNode);
                        }

                        mapNode.put(tokenList.getFirst(), currentNode);
                    }
                }

                ConfigNode mapConfigNode = new MapNode(mapNode);
                yield GResultOf.resultOf(mapConfigNode, errors);
            }
            case ScalarNode node -> {
                yield GResultOf.result(new LeafNode(node.getValue()));
            }
            default -> {
                yield GResultOf.errors(new ValidationError.UnknownNodeTypeDuringLoad(
                        path, _node.getNodeId().name()));
            }
        };
    }

    private String normalizeSentence(String sentence) {
        return lexer.normalizeSentence(sentence);
    }

    private List<String> tokenizer(String sentence) {
        return lexer.tokenizer(sentence);
    }
}
