# SBPCLifesteal

SBPCLifesteal is an SBPC-dependent Lifesteal add-on that ties player hearts, section progression, and combat logging together. It drops "Broken Hearts" on environmental deaths, rewards PvP killers with the health they steal, and blends health with SBPC's time-skip system to keep low-health players moving faster.

## Requirements
- **Server:** Paper/Spigot API 1.21+
- **Dependency:** [SBPC](https://github.com/your-org/SBPC) must be installed and enabled before this plugin loads.

## Key Features
- **Configurable Lifesteal rules:** PvP deaths transfer a full heart from victim to killer, while environmental deaths cost half a heart and drop a Broken Heart item that restores ½ heart on use. Minimum max health, PvP vs. environmental loss amounts, and default health all live in `config.yml`.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L53-L137】【F:src/config.yml†L7-L38】
- **Destroyed-heart safety net:** Broken Hearts that burn, fall into the void, or despawn are tracked. Stocked halves automatically unban players in FIFO order and give them pending ½-heart revives the next time they join.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L63-L176】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L800-L836】
- **SBPC section trading:** When a lower-section player kills someone from a higher section, they trade places once per section per victim; deaths to stronger players also reset SBPC entry progress. First-time PvP between two players triggers a warning about the mechanic.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L74-L118】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L872-L918】
- **Health-based SBPC speedup:** A 1-second task computes a multiplier from current/max hearts and feeds extra `SbpcAPI.applyExternalTimeSkip` seconds, letting weakened players progress SBPC faster without touching base SBPC tick speed.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L87-L93】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L688-L741】
- **Combat logging enforcement:** Players tagged for PvP spawn gear-holding, glowing zombies if they log out; killing the zombie within its TTL counts as a PvP kill, while letting it expire safely returns gear. Entries persist in `plugins/SBPCLifesteal/players/*.yml` for restarts.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L24-L188】

## Usage Examples
- **PvP heart steal:** Alice kills Bob in PvP. Bob loses the configured hearts (default 1), Alice gains that heart amount (capped if Bob was already low). If Bob drops to or below minimum health without a destroyed-heart lifeline, he is lifesteal-banned.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L53-L137】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L688-L836】
- **Environmental death:** Bob falls into the void. He loses half a heart of max health, drops a Broken Heart beetroot, and his SBPC entry progress is reset (section unchanged). Anyone can right-click the beetroot to regain ½ heart; if it burns, it contributes to the destroyed-heart stockpile instead.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L60-L86】【F:src/config.yml†L14-L35】
- **Section trade:** Carol (section B) kills Dave (section D). If Dave has not already been demoted from D by Carol before, Dave moves down to C and Carol climbs to C. Both receive configurable messages and newly disallowed gear drops if keep-inventory is on.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L872-L903】
- **Combat logging:** Eve hits Frank and Frank logs out inside the combat tag window. A no-AI zombie spawns with Frank's items and health. If Grace kills that zombie within the TTL, Frank is treated as if killed in PvP when he next logs in; otherwise he returns safely with his gear after TTL expiry or login.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L24-L188】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L1053-L1113】

## Configuration Highlights
The default `config.yml` ships with sensible values you can tweak:

```yaml
lifesteal:
  base-max-health: 20.0      # default hearts (health points)
  min-max-health: 2.0        # ban threshold; hearts below this trigger bans
  pvp-loss-hearts: 1.0       # hearts lost to players
  env-loss-hearts: 0.5       # hearts lost to environment and dropped as Broken Hearts

broken-heart-item:
  name: "&dBroken Heart"
  lore:
    - "&7A fragile fragment of a lost soul."
    - "&7Right-click to restore &c½ &7heart."

combat-log:
  tag-duration-seconds: 300  # PvP tag window
  zombie-ttl-seconds: 120    # time a combat logger zombie persists
```

## Integration Hooks
If you are writing a companion plugin or hook, these public surfaces and persistent markers are important:

### Classes
- `me.BaddCamden.SBPCLifesteal.SBPCLifestealPlugin` — main plugin class and event listener tying together lifesteal, SBPC section trades, and combat log bridges.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L94-L200】
- `me.BaddCamden.SBPCLifesteal.combat.CombatLogManager` — manages PvP tagging, zombie proxies, persistence, and exposes tagging helpers for other listeners to reuse.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L24-L189】
- `me.BaddCamden.SBPCLifesteal.combat.CombatLogEntry` — data carrier storing player/zombie state, inventory, XP, and location for combat-log persistence.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogEntry.java†L8-L285】

### Public methods for hooks
- `CombatLogManager.tagCombat(UUID victim, long nowMillis)` / `clearTag(UUID uuid)` / `isCombatTagged(UUID uuid, long nowMillis)` — tag bookkeeping helpers you can call when integrating additional damage sources or tag displays.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L83-L109】
- `CombatLogManager.loadAllEntries()` / `saveAllEntries()` — lifecycle persistence hooks already used by the plugin; call if you manage your own startup/shutdown flow.【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L115-L188】
- `SBPCLifestealPlugin.handleOfflineCombatLogKill(UUID victimId, Player killer)` — utility to apply heart/banning consequences for an offline combat-log kill when your systems kill the zombie yourself.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L919-L991】
- `SBPCLifestealPlugin.onCombatLoggerZombieDeath(EntityDeathEvent)` / `onPlayerJoinAfterCombatLog(PlayerJoinEvent)` — event bridges that translate combat-logger zombie deaths into pending heart loss and apply it on login. Listen for these if you emit your own proxy entities.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L1053-L1113】

### Persistent data keys and files
- Broken Heart items carry a `NamespacedKey` of `sbpclifesteal:broken_heart` to mark the beetroot as a heart fragment, and combat logger zombies store `sbpclifesteal:combat_logger_owner` with the owner's UUID. Use these keys to detect or interoperate with items/entities this plugin created.【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L96-L155】【F:src/me/BaddCamden/SBPCLifesteal/SBPCLifestealPlugin.java†L186-L200】
- Player-specific lifesteal and combat-log data persist under `plugins/SBPCLifesteal/players/<uuid>.yml`. The combat-log section uses the `combat-log.*` fields defined in `CombatLogEntry` (location, hearts, inventory, XP, TTL).【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogManager.java†L24-L188】【F:src/me/BaddCamden/SBPCLifesteal/combat/CombatLogEntry.java†L8-L285】

These surfaces should help downstream plugins tag players, reuse the combat logger, or consume lifesteal state without reimplementing the logic.
