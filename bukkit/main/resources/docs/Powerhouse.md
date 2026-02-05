# Powerhouse — API Usage Guide

Version: Last updated February 4, 2026

This document is a developer-focused guide to Powerhouse's public API. It shows the supported, stable ways other plugins should read metrics and state, and how to interact safely with Powerhouse subsystems (Folia-aware where applicable). The emphasis is on using the public `PowerhouseAPI` and `AllOptimizations` entry points rather than accessing internals.

Contents
- Introduction
- Quick Start: Accessing the API
- Core API Reference
  - `PowerhouseAPI`
  - `AllOptimizations`
  - `Calculations` (redstone culling)
  - `EntityLookup`, `ApiCompat`, `DebugLog`
- Common Examples
  - Get MSPT and statistics
  - Check whether a location is culled
  - Query whether an entity's AI is disabled
  - Mark an entity dead / request safe removal
  - Schedule a task at a specific world location (Folia-aware)
- Threading and Folia safety
- World Unload / memory-safety notes
- Best Practices and Troubleshooting
- FAQ

Introduction
------------
Powerhouse provides runtime optimizations for Bukkit/Paper/Folia servers: redstone culling, item/XP merging, entity AI management, hopper transfer limiting, and other maintenance systems. This guide shows how to safely read Powerhouse state and interact with it from another plugin.

**Quick Start: Accessing the API**

Powerhouse exposes a lightweight public API intended for other plugins. Prefer `kaiakk.powerhouse.external.PowerhouseAPI` for cross-plugin usage; it forwards to the runtime orchestrator `kaiakk.powerhouse.world.AllOptimizations` when available. `AllOptimizations.getInstance()` may return `null` if Powerhouse is not loaded or not yet initialized.

Example — safely querying MSPT (recommended):

```java
// runtime lookup via the public API (no internals required)
double mspt = kaiakk.powerhouse.external.PowerhouseAPI.getAverageMspt();
if (mspt < 0) {
	// Powerhouse unavailable or not ready
}
// use mspt
```

Example — directly using `AllOptimizations` when you have a direct dependency:

```java
kaiakk.powerhouse.world.AllOptimizations ops = kaiakk.powerhouse.world.AllOptimizations.getInstance();
if (ops == null) return; // not loaded
double mspt = ops.getAverageMspt();
```

Core API Reference
-------------------

`PowerhouseAPI`
- Lightweight wrapper API intended for other plugins (read-only). Full class name: `kaiakk.powerhouse.external.PowerhouseAPI`. Use this from other plugins to avoid a compile-time dependency on Powerhouse internals; the API forwards to `AllOptimizations` where appropriate.

PowerhouseAPI Methods (common)

- `double getAverageMspt()` — current average MSPT, or `-1` if unavailable.
- `double getAverageMsptRounded(int decimals)` — MSPT rounded to `decimals` places; throws `IllegalArgumentException` if `decimals < 0`.
- `long getCrammingRemovals()` — total entity removals performed due to mob-cramming mitigation, or `0` if unavailable.
- `long getItemRemovals()` — total item removals performed by cleanup systems, or `0` if unavailable.
- `Map<String,Object> getStatisticsSnapshot()` — immutable snapshot of global optimization statistics, or empty map.
- `Set<String> getDebugUsersSnapshot()` — snapshot of debug users enabled for per-user debug.
- `boolean isDebugEnabled()` — whether global debug mode is enabled.
- `String getDebugOwner()` — name of the current debug owner, or `null`.
- `boolean isDebugEnabledForUser(String name)` — whether debug is enabled for `name`.
- `boolean isLocationCulled(Location location)` — whether redstone updates are currently culled at `location`.
- `boolean isLocationCulled(String worldName, int x, int y, int z)` — convenience overload using coordinates.
- `boolean isLocationThrottled(Location location)` — best-effort check for deferred/throttled locations (calls async helpers if available).
- `boolean isEntityAiDisabled(UUID entityId)` — whether AI is currently disabled for the entity.
- `boolean isProxyPresent()` — whether a proxy has been detected or configured.
- `String getProxyType()` — human-friendly proxy type (e.g. "NONE", "BUNGEE", "VELOCITY", "MIXED", "CONFIGURED_ONLY").
- `String getVersion()` — plugin version from the loaded plugin description, or `null`.
- `boolean isPowerhouseActive()` — true if Powerhouse is loaded and running.
- `boolean enqueueEntityTask(Entity ent, Runnable task)` — enqueue a synchronous task to run relative to an entity's scheduler; returns true if queued.
- `void markEntityDead(Entity ent)` — best-effort helper to mark an entity dead (use with care; prefer `enqueueEntityTask`).

Notes:
- `PowerhouseAPI` is stable for read-only operations; prefer it for cross-plugin integrations to avoid depending on internal package names.
- Methods that return collections (`getStatisticsSnapshot`, `getDebugUsersSnapshot`) return safe, immutable views.

`AllOptimizations` (runtime orchestrator — full class name: `kaiakk.powerhouse.world.AllOptimizations`)
- `getInstance()` — returns the singleton `AllOptimizations` instance or `null`.
- `double getAverageMspt()` — returns server average milliseconds-per-tick (MSPT). Returns a non-negative value when available; `PowerhouseAPI` will return `-1` when unavailable.
- `boolean isLocationCulled(Location loc)` — returns `true` if Powerhouse currently suppresses redstone updates at `loc`.
- `boolean isEntityAiDisabled(UUID id)` — returns `true` if AI is currently disabled for that entity.
- `void enqueueEntityTask(Entity ent, Runnable task)` — schedule a task on the entity/region owning thread when possible; safe cross-platform helper for Folia and Paper.
- Instrumentation helpers such as `addCrammingRemovals` / `addItemRemovals` exist but are rarely needed.

`Calculations` (async helpers — under `kaiakk.powerhouse.calculations`)
- Useful async helpers and scanners for redstone culling. The exact package may change between Powerhouse releases; prefer `PowerhouseAPI`/`AllOptimizations` first. Available helpers include scanning candidate locations and clearing per-world data.

Other Utility Classes
- `EntityLookup` — safe UUID→Entity lookup with fallbacks for older servers (scans loaded chunks when modern method missing). Use `EntityLookup.getEntity(UUID)` when you cannot rely on `Bukkit.getEntity(UUID)` to exist.
- `ApiCompat` — compatibility wrappers for operations like `setAI(LivingEntity, boolean)`, `getMaxHealth`, and `playSoundSafe` across server versions.
- `DebugLog` — central debug logger. Use only for development/debugging to avoid spamming server console. `DebugLog.debug(String)` will check the Powerhouse debug flag before printing.

**Common Examples**

1) Get MSPT and statistics (recommended via `PowerhouseAPI`):

```java
double mspt = kaiakk.powerhouse.external.PowerhouseAPI.getAverageMspt();
// mspt may be -1 if Powerhouse is not loaded
```

Or, if you have a compile-time dependency and need other internals:

```java
kaiakk.powerhouse.world.AllOptimizations ops = kaiakk.powerhouse.world.AllOptimizations.getInstance();
if (ops != null) {
	double mspt = ops.getAverageMspt();
	long crammingRemovals = ops.getCrammingRemovals();
	long itemRemovals = ops.getItemRemovals();
}
```

2) Check whether a location is culled (use `PowerhouseAPI` or `AllOptimizations`):

```java
Location loc = someBlock.getLocation();
if (kaiakk.powerhouse.external.PowerhouseAPI.isLocationCulled(loc)) {
	// suppressed
}
// or via AllOptimizations when available
```

3) Query whether an entity currently has AI disabled

```java
UUID id = someEntity.getUniqueId();
if (kaiakk.powerhouse.external.PowerhouseAPI.isEntityAiDisabled(id)) {
	// AI disabled
}
```

4) Request safe removal (enqueue a task)

Prefer using `enqueueEntityTask` rather than calling removal internals directly:

```java
kaiakk.powerhouse.world.AllOptimizations ops = kaiakk.powerhouse.world.AllOptimizations.getInstance();
if (ops != null) {
	ops.enqueueEntityTask(someEntity, () -> {
		try {
			kaiakk.powerhouse.world.AllOptimizations.getInstance().markEntityDead(someEntity);
		} catch (Throwable ignored) {}
	});
}
```

Note: `markEntityDead` is a version-sensitive helper. If you must remove entities yourself, follow server threading rules and prefer `Entity.remove()` on the main thread.

5) Schedule a task at a `Location` that is Folia/region-aware

Powerhouse exposes `runAtLocation(Location, Runnable)` internally and attempts to detect a platform region scheduler. If you're writing an integration that benefits from this, implement your own region-aware scheduling or use Powerhouse's `enqueueEntityTask` for entity-owned scheduling. Example pattern:

```java
// Example helper (conceptual) - calling Powerhouse's runAtLocation is internal
Location loc = someBlock.getLocation();
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops != null) {
	// Best practice: wrap work in a Bukkit scheduler run() or use the server API
	org.bukkit.Bukkit.getScheduler().runTask(myPlugin, () -> {
		// safe main-thread work here
	});
}
```

**Threading and Folia safety**

Powerhouse is compatible with Paper and Folia. Key points:

- Heavy computations are often done async and results applied on the owning thread/region.
- When calling into `AllOptimizations` or Powerhouse helpers, assume entity/world access must follow the server's threading model. Use `enqueueEntityTask` or schedule a main/region task for entity/world operations.
- Avoid long-lived strong references to `World`/`Location`; prefer world names and clear caches on unload.

World Unload / Memory-safety notes
----------------------------------
Holding onto `World` or `Location` objects after a world unload is a common source of memory leaks. Powerhouse already takes these precautions:

- `AllOptimizations` maintains `lastItemScan` and `lastRedstoneScan` (maps keyed by `World`) and clears them on plugin stop.
- There is a `WorldUnloadEvent` handler in `AllOptimizations` that removes entries from these maps and calls `Calculations.clearWorldData(worldName)` to purge any tracked redstone/cull state for that world.

If you integrate Powerhouse state into your own plugin, follow these rules:

- Never store strong references to `World` or `Location` in long-lived static maps. If you must cache a location, store the world by name (String) and remove entries on `WorldUnloadEvent`.
- If you read Powerhouse's internal maps for diagnostics, do not keep references to returned `Location` objects across world unloads.

Best Practices and Troubleshooting
---------------------------------
- Prefer read-only calls such as `getAverageMspt()`, `isLocationCulled()`, and `getStatistics()` when integrating Powerhouse into dashboards or admin tools.
- Avoid calling Powerhouse internals that mutate world state unless you strictly follow the server's threading model and Folia region ownership rules.
- Use `EntityLookup.getEntity(UUID)` for compatibility with legacy servers where `Bukkit.getEntity(UUID)` may not exist.
- Use `ApiCompat` for compatibility wrappers when you need to call `setAI`, `getMaxHealth`, or sound methods across versions.
- If you see Folia thread-check exceptions in logs, that indicates code accessed entities from a non-owning thread. Move the access to a scheduled main/region task or use Powerhouse's enqueue helpers.

FAQ
---
Q: Can I rely on Powerhouse to remove entities for me?
A: Powerhouse performs certain entity removals (culling, cramming cleanup, item purges). If you need a guaranteed removal, request it on the main thread and use Powerhouse APIs where possible, but avoid removing entities from other plugins without careful synchronization.

Q: How do I detect whether a location is safe to place redstone machinery (i.e., not culled)?
A: Use `AllOptimizations.isLocationCulled(Location)`. If it returns `true`, Powerhouse may be suppressing updates there. Use that information to warn admins or auto-disable automation.

Q: How do I respond when Powerhouse unculls locations?
A: Powerhouse will periodically expire culled locations and has a `uncullAll()` safety valve. There is no direct event emitted by Powerhouse for uncull actions; if you need to watch it, poll `isLocationCulled()` or request a feature to emit events.