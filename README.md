# SprintStrike Minecraft Plugin

## Overview
SprintStrike is an exciting Minecraft plugin that adds a dynamic combat mechanic allowing players to teleport near mobs with a powerful strike ability. With multiple tiers of progression, players can unlock increasingly powerful effects and abilities.

## Features
- **Teleport Strike Mechanic**: Quickly teleport near mobs when sneaking with a weapon
- **Tier-Based Progression**: A custom amount of unique tiers with increasingly powerful effects
- **Combo System**: Advanced players can chain strikes within a time window
- **Customizable Effects**: Each tier grants different potion effects
- **Configurable Messages**: Fully customizable language and message settings

## Requirements
- Spigot or Paper Minecraft Server
- Java 21
- Minecraft version 1.13 or newer

## Installation
1. Download the latest release from the GitHub releases page
2. Place the `.jar` file in your server's `plugins` directory
3. Restart or reload your server

## Configuration
The plugin uses several configuration files for customization:

### `config.yml`
- Configure global plugin settings
- Set message display type (chat, hotbar, or title)

### `lang.yml`
- Customize all plugin messages
- Add translations or modify existing messages

### `tiers.yml`
- Define tier-specific settings
- Customize effects, cooldowns, and abilities for each tier

### `playerdata.yml`
- Stores player tier information
- Automatically managed by the plugin

## Commands
- `/sprintstrike settier <level> [player]`: Set a player's SprintStrike tier
  - Requires `canSetSprintStrikeLevel` permission

## Permissions
- `canSetSprintStrikeLevel`: Allows setting player sprint strike tiers

## How to Use
1. Hold a sword, axe, or stick
2. Sneak near a mob to activate the sprint strike
3. Teleport and receive tier-specific effects

## Customization Example
```yaml
# Example of a custom tier in tiers.yml
tier 3:
  effects:
    - strength,6,4
    - speed,2,4
  combo_duration: 4
  max_teleport_distance: 15
  cooldown: 30
  damage_breaks_combo: true
```

## Contributing
Contributions are welcome! Please:
- Fork the repository
- Create a feature branch
- Submit a pull request

## Support
- Open an issue on the GitHub repository for bugs or feature requests

## Credits
Developed by [taran2k](https://github.com/taran2k/).

## Changelog
### v1.0.0
- Initial release
