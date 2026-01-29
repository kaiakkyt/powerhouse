package kaiakk.powerhouse.async;

import org.bukkit.event.HandlerList;

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
			kaiakk.powerhouse.sync.AllOptimizations ao = null;
			try { ao = kaiakk.powerhouse.sync.AllOptimizations.getInstance(); } catch (Throwable t) {
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

		try { kaiakk.powerhouse.sync.RecentActionTracker.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		try { kaiakk.powerhouse.sync.BookLagger.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		try { kaiakk.powerhouse.ConfigHelp.shutdown(); } catch (Exception e) { if (VERBOSE) e.printStackTrace(); }

		String[] otherSingletons = new String[]{
			kaiakk.powerhouse.sync.ProjectileManager.class.getName(),
			kaiakk.powerhouse.sync.PassivePhysicsManager.class.getName(),
			kaiakk.powerhouse.sync.ParticleCulling.class.getName()
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
