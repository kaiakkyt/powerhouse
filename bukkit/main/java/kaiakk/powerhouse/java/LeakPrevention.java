package kaiakk.powerhouse.java;

import org.bukkit.event.HandlerList;

import kaiakk.powerhouse.helpers.internal.ConfigHelp;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Collection;

public final class LeakPrevention {

	private LeakPrevention() {}
	private static final boolean VERBOSE = Boolean.getBoolean("powerhouse.leak.verbose");

	private static volatile boolean SHUTDOWN = false;

	public static void shutdownAll() {
		if (SHUTDOWN) return;
		try {
			org.bukkit.plugin.Plugin p = kaiakk.powerhouse.Powerhouse.getInstance();
			if (p != null) {
				try { org.bukkit.Bukkit.getScheduler().cancelTasks(p); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }
				try { org.bukkit.Bukkit.getServicesManager().unregisterAll(p); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }
			}
		} catch (Throwable t) {
			if (VERBOSE) { System.err.println("LeakPrevention: failed to cancel tasks/unregister services: " + t); t.printStackTrace(System.err); }
		}

		try {
			kaiakk.powerhouse.world.AllOptimizations ao = null;
			try { ao = kaiakk.powerhouse.world.AllOptimizations.getInstance(); } catch (Throwable t) {
				if (VERBOSE) {
					System.err.println("LeakPrevention: failed to query AllOptimizations.getInstance(): " + t);
					t.printStackTrace(System.err);
				}
			}
			if (ao != null) {
				try { ao.stop(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }
				try { HandlerList.unregisterAll(ao); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }
			}
		} catch (Exception e) {
			if (VERBOSE) { System.err.println("LeakPrevention: unexpected error stopping AllOptimizations: " + e); e.printStackTrace(System.err); }
		}
		try { setStaticFieldNull("kaiakk.powerhouse.sync.AllOptimizations", "INSTANCE"); } catch (Throwable t) { if (VERBOSE) { System.err.println("LeakPrevention: unable to clear AllOptimizations.INSTANCE: " + t); t.printStackTrace(System.err);} }

		try { kaiakk.powerhouse.data.RecentActionTracker.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		try { kaiakk.powerhouse.world.limiters.BookLimiter.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		try { kaiakk.powerhouse.helpers.internal.ConfigHelp.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		String[] otherSingletons = new String[]{
			kaiakk.powerhouse.world.physics.ProjectileManager.class.getName(),
			kaiakk.powerhouse.world.physics.PassivePhysicsManager.class.getName(),
			kaiakk.powerhouse.world.physics.ParticleCulling.class.getName()
		};
		for (String cls : otherSingletons) {
			String[] fields = new String[]{"INSTANCE", "plugin", "PLUGIN"};
			for (String f : fields) {
				try { setStaticFieldNull(cls, f); } catch (Throwable ignored) {}
			}
		}

		try {
			if (VERBOSE) System.out.println("LeakPrevention: Shutdown complete. Requesting GC...");
			System.gc();
		} catch (Throwable ignored) {}

		SHUTDOWN = true;
	}

	public static void purgeCollections() {
		String[] managers = new String[]{
			"kaiakk.powerhouse.sync.ProjectileManager",
			"kaiakk.powerhouse.sync.PassivePhysicsManager",
			"kaiakk.powerhouse.sync.RecentActionTracker"
		};
		for (String className : managers) {
			try {
				Class<?> clazz = Class.forName(className, false, LeakPrevention.class.getClassLoader());
				Object targetInstance = null;
				try {
					java.lang.reflect.Field instF = clazz.getDeclaredField("INSTANCE");
					instF.setAccessible(true);
					targetInstance = instF.get(null);
				} catch (Throwable ignored) {}
				if (targetInstance == null) {
					try {
						java.lang.reflect.Method m = clazz.getMethod("getInstance");
						Object res = m.invoke(null);
						if (res != null) targetInstance = res;
					} catch (Throwable ignored) {}
				}
				for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
					Class<?> t = field.getType();
					if (java.util.Map.class.isAssignableFrom(t) || java.util.Collection.class.isAssignableFrom(t)) {
						field.setAccessible(true);
						Object collection = null;
						try {
							if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) collection = field.get(null);
							else if (targetInstance != null) collection = field.get(targetInstance);
						} catch (Throwable ignored) {}
						if (collection != null) {
							try {
								if (collection instanceof java.util.Map) ((java.util.Map) collection).clear();
								else if (collection instanceof java.util.Collection) ((java.util.Collection) collection).clear();
							} catch (Throwable ex) { if (VERBOSE) ex.printStackTrace(); }
							try {
								java.lang.reflect.Field modifiersField = Field.class.getDeclaredField("modifiers");
								modifiersField.setAccessible(true);
								modifiersField.setInt(field, field.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
							} catch (Throwable ex) { if (VERBOSE) ex.printStackTrace(); }
							try {
								if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) field.set(null, null);
								else if (targetInstance != null) field.set(targetInstance, null);
							} catch (Throwable ex) { if (VERBOSE) ex.printStackTrace(); }
							if (VERBOSE) System.out.println("LeakPrevention: purged collection field " + clazz.getName() + "." + field.getName());
						}
					}
				}
			} catch (Throwable t) {
				if (VERBOSE) { System.err.println("LeakPrevention: purgeCollections error for " + className + " -> " + t); t.printStackTrace(System.err); }
			}
		}

		// Attempt a broader purge across plugin classes under our package to clear
		// static maps/collections which commonly retain memory over time.
		try {
			org.bukkit.plugin.Plugin p = kaiakk.powerhouse.Powerhouse.getInstance();
			purgePluginStatics(p, "kaiakk.powerhouse");
		} catch (Throwable ignored) {}
	}

	private static void purgePluginStatics(org.bukkit.plugin.Plugin plugin, String packagePrefix) {
		if (plugin == null) return;
		try {
			java.net.URL url = plugin.getClass().getProtectionDomain().getCodeSource().getLocation();
			if (url == null) return;
			java.io.File file = new java.io.File(url.toURI());
			if (file.exists()) {
				if (file.isFile()) {
					try (java.util.jar.JarFile jf = new java.util.jar.JarFile(file)) {
						java.util.Enumeration<java.util.jar.JarEntry> entries = jf.entries();
						while (entries.hasMoreElements()) {
							java.util.jar.JarEntry je = entries.nextElement();
							String name = je.getName();
							if (!name.endsWith(".class")) continue;
							if (!name.startsWith(packagePrefix.replace('.', '/') + "/")) continue;
							String className = name.substring(0, name.length() - 6).replace('/', '.');
							try {
								Class<?> clazz = Class.forName(className, false, LeakPrevention.class.getClassLoader());
								for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
									int mods = field.getModifiers();
									if (!java.lang.reflect.Modifier.isStatic(mods)) continue;
									Class<?> t = field.getType();
									if (java.util.Map.class.isAssignableFrom(t) || java.util.Collection.class.isAssignableFrom(t)) {
										field.setAccessible(true);
										Object collection = null;
										try { collection = field.get(null); } catch (Throwable ignored) {}
										if (collection != null) {
											try { if (collection instanceof java.util.Map) ((java.util.Map) collection).clear(); else if (collection instanceof java.util.Collection) ((java.util.Collection) collection).clear(); } catch (Throwable ignored) {}
										}
										try { setStaticField(clazz, field.getName(), null); } catch (Throwable ignored) {}
									}
								}
							} catch (Throwable ignored) {}
						}
					}
				} else if (file.isDirectory()) {
					java.nio.file.Path root = file.toPath();
					java.nio.file.Path pkgPath = root.resolve(packagePrefix.replace('.', java.io.File.separatorChar));
					if (java.nio.file.Files.exists(pkgPath)) {
						java.nio.file.Files.walk(pkgPath).forEach(p -> {
							if (!p.toString().endsWith(".class")) return;
							try {
								String rel = root.relativize(p).toString().replace(java.io.File.separatorChar, '/');
								if (!rel.startsWith(packagePrefix.replace('.', '/') + "/")) return;
								String className = rel.substring(0, rel.length() - 6).replace('/', '.');
								Class<?> clazz = Class.forName(className, false, LeakPrevention.class.getClassLoader());
								for (java.lang.reflect.Field field : clazz.getDeclaredFields()) {
									int mods = field.getModifiers();
									if (!java.lang.reflect.Modifier.isStatic(mods)) continue;
									Class<?> t = field.getType();
									if (java.util.Map.class.isAssignableFrom(t) || java.util.Collection.class.isAssignableFrom(t)) {
										field.setAccessible(true);
										Object collection = null;
										try { collection = field.get(null); } catch (Throwable ignored) {}
										if (collection != null) {
											try { if (collection instanceof java.util.Map) ((java.util.Map) collection).clear(); else if (collection instanceof java.util.Collection) ((java.util.Collection) collection).clear(); } catch (Throwable ignored) {}
										}
										try { setStaticField(clazz, field.getName(), null); } catch (Throwable ignored) {}
									}
								}
							} catch (Throwable ignored) {}
						});
					}
				}
			}
		} catch (Throwable t) {
			if (VERBOSE) { System.err.println("LeakPrevention: error scanning plugin classes: " + t); t.printStackTrace(System.err); }
		}
	}


	private static void setStaticFieldNull(String className, String fieldName) {
		try {
			Class<?> c = Class.forName(className, false, LeakPrevention.class.getClassLoader());
			setStaticField(c, fieldName, null);
		} catch (ClassNotFoundException cnf) {
			if (VERBOSE) System.err.println("LeakPrevention: class not found: " + className);
		} catch (Throwable t) {
			if (VERBOSE) { System.err.println("LeakPrevention: unexpected error loading class " + className + " -> " + t); t.printStackTrace(System.err); }
		}
	}

	private static void setStaticField(Class<?> c, String fieldName, Object value) {
		try {
			Field f = c.getDeclaredField(fieldName);
			f.setAccessible(true);
			try {
				Field modifiersField = Field.class.getDeclaredField("modifiers");
				modifiersField.setAccessible(true);
				modifiersField.setInt(f, f.getModifiers() & ~java.lang.reflect.Modifier.FINAL);
			} catch (Throwable ex) {
				if (VERBOSE) {
					System.err.println("LeakPrevention: couldn't strip final modifier for " + c.getName() + "." + fieldName + " -> " + ex);
					ex.printStackTrace(System.err);
				}
			}

			f.set(null, value);
			if (VERBOSE) System.out.println("LeakPrevention: cleared static " + c.getName() + "." + fieldName);
		} catch (Throwable t) {
			if (VERBOSE) {
				System.err.println("LeakPrevention: failed to clear static field " + c.getName() + "." + fieldName + " -> " + t);
				t.printStackTrace(System.err);
			}
		}
	}
}
