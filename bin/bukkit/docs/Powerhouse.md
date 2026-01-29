# Powerhouse — API Usage Guide

Version: Last updated December 21, 2025

This document is a developer-focused, comprehensive guide to using Powerhouse's public API to inspect runtime status, query optimization state, and interact safely with Powerhouse subsystems from other plugins. It covers typical read-only operations (getting metrics and state), safe interactions that must respect server threading / Folia, and recommended best practices.

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

Quick Start: Accessing the API
--------------------------------
Powerhouse exposes programmatic read-only access via `PowerhouseAPI` and a runtime orchestrator `AllOptimizations`. Most commonly you will simply call `AllOptimizations.getInstance()` to access runtime methods — note: `getInstance()` returns `null` if Powerhouse hasn't been started yet.

Example — safe guard when Powerhouse might not be loaded:

```java
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops == null) {
	// Powerhouse not present or not yet initialized
	return;
}
// proceed to query ops
```

Core API Reference
-------------------

`PowerhouseAPI`
- Lightweight wrapper API intended for other plugins (read-only). Use this when you want to avoid a direct dependency on internal classes; the API forwards to `AllOptimizations` where appropriate.

`AllOptimizations`
- `getInstance()` — returns the singleton `AllOptimizations` instance or `null`.
- `double getAverageMspt()` — returns server average milliseconds-per-tick (MSPT). Returns `0.0` if not available.
- `boolean isLocationCulled(Location loc)` — returns `true` if Powerhouse currently has redstone updates disabled (culled) at `loc`. Safe to call from main thread — ensure your caller has a valid `Location` with a valid `World`.
- `boolean isEntityAiDisabled(UUID id)` — returns `true` if the `EntityPusher`/AI-management subsystem currently has disabled AI for the entity with `id`.
- `void enqueueEntityTask(Entity ent, Runnable task)` — helper that attempts to schedule `task` against the entity's region scheduler (if present), otherwise runs the task at `ent`'s `Location` (region-aware), otherwise falls back to an internal pending-sync queue. Use this to schedule small operations that must run on the entity's owning thread/region.
- `void addCrammingRemovals(long n)`, `void addItemRemovals(long n)` — instrumentation counters; rarely needed by external code.

`Calculations` (async redstone & culling helpers)
- `static List<Location> scanRedstoneCullingCandidates()` — returns candidate Locations that appear to have excessive block updates.
- `static Map<Location, Integer> scanRedstoneCullingCandidatesWithCounts(int minUpdates)` — returns candidate locations and their update counts.
- `static void markLocationCulled(Location loc)` — mark a block location culled (internal; you generally won't call this).
- `static boolean isLocationCulled(Location loc)` — same as `AllOptimizations.isLocationCulled` but static.
- `static void uncullAll()` — safety valve that clears all culled locations.
- `static void clearWorldData(String worldName)` — remove tracked redstone/cull data related to a world (called on `WorldUnload`).

Other Utility Classes
- `EntityLookup` — safe UUID→Entity lookup with fallbacks for older servers (scans loaded chunks when modern method missing). Use `EntityLookup.getEntity(UUID)` when you cannot rely on `Bukkit.getEntity(UUID)` to exist.
- `ApiCompat` — compatibility wrappers for operations like `setAI(LivingEntity, boolean)`, `getMaxHealth`, and `playSoundSafe` across server versions.
- `DebugLog` — central debug logger. Use only for development/debugging to avoid spamming server console. `DebugLog.debug(String)` will check the Powerhouse debug flag before printing.

Common Examples
---------------

1) Get MSPT and the plugin statistics

```java
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops != null) {
	double mspt = ops.getAverageMspt();
	long crammingRemovals = ops.getCrammingRemovals();
	long itemRemovals = ops.getItemRemovals();
	// Use or present values
}
```

2) Check whether a location is currently culled (redstone suspended)

```java
Location loc = someBlock.getLocation();
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops != null && ops.isLocationCulled(loc)) {
	// location currently suppressed by Powerhouse
}
```

3) Query whether an entity currently has AI disabled

```java
UUID id = someEntity.getUniqueId();
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops != null && ops.isEntityAiDisabled(id)) {
	// AI currently disabled
}
```

4) Request safe removal (mark an entity dead) from another plugin

Powerhouse provides `markEntityDead(Entity)` internally to safely remove an entity in a version-compatible way. Because this method is internal, prefer requesting a removal via Powerhouse's task enqueueing or inter-plugin contract, but if you must call the internal helper, make sure you have compile-time access and call it on the server/main thread:

```java
// best: schedule a run on the main thread that asks Powerhouse to remove
kaiakk.powerhouse.sync.AllOptimizations ops = kaiakk.powerhouse.sync.AllOptimizations.getInstance();
if (ops != null) {
	// use the enqueue helper to ask Powerhouse to run a task at the entity's owning location
	ops.enqueueEntityTask(someEntity, () -> {
		try {
			kaiakk.powerhouse.sync.AllOptimizations.getInstance().markEntityDead(someEntity);
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

Threading and Folia safety
---------------------------
Powerhouse is built to be compatible with Paper and Folia. That means:

- Many subsystems take a snapshot on the main thread (or region thread) and run CPU-heavy computations async, then apply results back on the owning thread using region-aware scheduling.
- When calling into Powerhouse from your plugin, assume that most `AllOptimizations` operations expect a valid `Location`/Entity and that entity access must be done on the owning thread. If you need to access an `Entity` safely by UUID from an async context, use `EntityLookup.getEntity(UUID)` on the main thread or schedule a task using the server scheduler.
- Do not access or store `World` or `Location` objects for long-term caching in static maps — prefer storing weak references or world names, and clear cached references on `WorldUnloadEvent` (Powerhouse already clears its internal caches on unload).

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