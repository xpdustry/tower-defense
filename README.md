# tower-defense

[![Build status](https://github.com/xpdustry/tower-defense/actions/workflows/build.yml/badge.svg?branch=master&event=push)](https://github.com/xpdustry/tower-defense/actions/workflows/build.yml)
[![Mindustry 8.0](https://img.shields.io/badge/Mindustry-8.0-ffd37f)](https://github.com/Anuken/Mindustry/releases)

## Installation

This plugin requires :

- Java 21 or above.

- Mindustry v149 or above.

## Config

```yml
mitosis: The number of offspring an unit produces on death (0 for none, 1+ to enable).
unit-bind: If logic unit-bind is allowed.
health-multiplier: The amount enemy health is increased per minute.
block-whitelist: The blocks allowed to be build on the tower defense path.
drops: The unit drops. See config.yaml for details.
```

## Building

- `./gradlew shadowJar` to compile the plugin into a usable jar (will be located
  at `builds/libs/(plugin-name).jar`).

- `./gradlew runMindustryServer` to run the plugin in a local Mindustry server.

- `./gradlew runMindustryDesktop` to start a local Mindustry client that will let you test the plugin.
