package com.fsodomka.idepilot;

import com.intellij.configurationStore.ComponentSerializationUtil;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ComponentManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.Service;
import com.intellij.openapi.components.State;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryKeyBean;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.util.xmlb.Accessor;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jdom.Element;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The effective values of IDE and project settings, defaults included: registry keys, Advanced Settings, and the
 * persisted state of the application's and open projects' services. Through public API only, which lists the registry
 * keys but neither the Advanced Settings nor the services: those are found by their ID and class name.
 */
final class IdeSettings {
	private static final int MAX_VALUE = 160;
	private static final ExtensionPointName<RegistryKeyBean> REGISTRY_KEYS = ExtensionPointName.create("com.intellij.registryKey");
	/** A class name, as its lowercase packages and a capitalized name, then maybe a dot and a part of a property name. */
	private static final Pattern SERVICE = Pattern.compile("((?:[a-z_]\\w*\\.)+[A-Z][\\w$]*)(?:\\.(.*))?");
	/** An Advanced Setting's value and default, read as each of its possible types; the wrong ones fail to cast. */
	private static final List<Function<String, Object[]>> ADVANCED = List.of(
			id -> new Object[]{AdvancedSettings.getBoolean(id), AdvancedSettings.getDefaultBoolean(id)},
			id -> new Object[]{AdvancedSettings.getInt(id), AdvancedSettings.getDefaultInt(id)},
			id -> new Object[]{AdvancedSettings.getString(id), AdvancedSettings.getDefaultString(id)},
			IdeSettings::advancedEnum);

	private IdeSettings() {
	}

	/**
	 * At most {@code limit} settings: the registry keys whose line {@code registry KEY = VALUE} contains {@code find}
	 * (case-insensitive); the Advanced Setting with the ID {@code find}, as {@code advanced ID = VALUE}; and, for the
	 * class name of a service (then maybe a dot and a part of a property name), its persisted state, as
	 * {@code app|project NAME FILE Component.property = VALUE}; marked (default) or (changed) where known.
	 */
	static String find(String find, int limit) {
		String needle = find.toLowerCase(Locale.ROOT);
		List<String> lines = new ArrayList<>();
		for (String key : registryKeys()) {
			try {
				RegistryValue value = Registry.get(key);
				add(lines, needle, "registry " + key + " = " + value.asString() + (value.isChangedFromDefault() ? " (changed)" : ""));
			} catch (MissingResourceException e) {
				// a key that is declared but has no value is skipped
			}
		}
		String advanced = advanced(find.strip());
		if (advanced != null) {
			lines.add(advanced);
		}
		Matcher service = SERVICE.matcher(find.strip());
		if (service.matches()) {
			states(service.group(1), service.group(2) == null ? "" : service.group(2).toLowerCase(Locale.ROOT), lines);
		}
		if (lines.isEmpty()) {
			return "(no setting found for \"" + find + "\": give a part of a registry key, the exact ID of an Advanced Setting, "
					+ "or the class name of a service with persisted state, e.g. com.intellij.ide.GeneralSettings, "
					+ "or with a part of a property name, com.intellij.ide.GeneralSettings.confirm; services of the platform only)";
		}
		String more = lines.size() > limit ? "\n... " + (lines.size() - limit) + " more; narrow find or raise limit" : "";
		return String.join("\n", lines.subList(0, Math.min(lines.size(), limit))) + more;
	}

	/** The registry's keys: the platform's, from its bundle, and the plugins', from their extensions. */
	private static Set<String> registryKeys() {
		Set<String> keys = new TreeSet<>();
		for (String key : ResourceBundle.getBundle(Registry.REGISTRY_BUNDLE, Locale.ROOT, Registry.class.getClassLoader()).keySet()) {
			if (!key.endsWith(".description") && !key.endsWith(".restartRequired")) {
				keys.add(key);
			}
		}
		for (RegistryKeyBean bean : REGISTRY_KEYS.getExtensionList()) {
			keys.add(bean.key);
		}
		return keys;
	}

	private static void add(List<String> lines, String needle, String line) {
		if (line != null && line.toLowerCase(Locale.ROOT).contains(needle)) {
			lines.add(line);
		}
	}

	/** The Advanced Setting with this ID as a line, or null when there is none. */
	private static String advanced(String id) {
		for (Function<String, Object[]> typed : ADVANCED) {
			try {
				Object[] value = typed.apply(id);
				return "advanced " + id + " = " + render(value[0]) + (Objects.equals(value[0], value[1]) ? " (default)" : " (changed)");
			} catch (ClassCastException e) {
				// of another type
			} catch (IllegalArgumentException e) {
				return null; // no such setting
			}
		}
		return null;
	}

	/** As any enum: the public getters need the setting's own enum class, which only its internal declaration names. */
	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object[] advancedEnum(String id) {
		return new Object[]{AdvancedSettings.getEnum(id, (Class) Enum.class), AdvancedSettings.getDefaultEnum(id, (Class) Enum.class)};
	}

	/**
	 * The properties containing {@code needle} of the service {@code className}, of the application and of each open
	 * project, created if it is not yet. A state kept as XML is read from the service's own public properties.
	 */
	private static void states(String className, String needle, List<String> lines) {
		Class<?> type = load(className);
		if (type == null) {
			return;
		}
		// a light service names its level, and asking the other one for it is an error; a registered one is just not there
		Service light = type.getAnnotation(Service.class);
		List<Service.Level> levels = light == null || light.value().length == 0 ? List.of(Service.Level.values()) : List.of(light.value());
		// the application first: a project asked for an application service logs an error, while the application asked
		// for a project service just has none
		if (levels.contains(Service.Level.APP) && state(ApplicationManager.getApplication(), type, "app", needle, lines)) {
			return;
		}
		if (levels.contains(Service.Level.PROJECT)) {
			for (Project project : ProjectManager.getInstance().getOpenProjects()) {
				state(project, type, "project " + project.getName(), needle, lines);
			}
		}
	}

	/** Adds the properties of {@code manager}'s service {@code type}; whether it has that service. */
	private static boolean state(ComponentManager manager, Class<?> type, String scope, String needle, List<String> lines) {
		Object service;
		try {
			service = manager.getService(type);
		} catch (RuntimeException | LinkageError e) {
			return false; // a service that cannot be created now
		}
		if (service instanceof PersistentStateComponent<?> component) {
			try {
				lines.addAll(properties(scope, component, needle));
			} catch (RuntimeException | LinkageError e) {
				// a state that cannot be read now is skipped
			}
		}
		return service != null;
	}

	/**
	 * The platform's class of this name, or null. Not a plugin's: from 2026.2 every way to find a plugin, and so its
	 * class loader, is internal.
	 */
	private static Class<?> load(String name) {
		try {
			return Class.forName(name, false, Registry.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError e) {
			return null;
		}
	}

	private static List<String> properties(String scope, Object component, String needle) {
		State spec = component.getClass().getAnnotation(State.class);
		String name = spec == null ? component.getClass().getSimpleName() : spec.name();
		String file = spec == null || spec.storages().length == 0 ? "" : spec.storages()[0].value() + " ";
		Object bean = stateBean(component);
		List<String> result = new ArrayList<>();
		// writes itself, and the serializer refuses its accessors; by name, as JDOMExternalizable is deprecated
		Class<?> externalizable = load("com.intellij.openapi.util.JDOMExternalizable");
		if (externalizable != null && externalizable.isInstance(bean)) {
			return result;
		}
		Object defaults = bean == component ? null : fresh(bean.getClass());
		// as Accessor: the list's own element type, MutableAccessor, is internal
		for (Accessor accessor : XmlSerializerUtil.getAccessors(bean.getClass())) {
			Object value = accessor.read(bean);
			String line = scope + " " + file + name + "." + accessor.getName() + " = " + render(value);
			if (defaults != null) {
				line += Objects.equals(value, accessor.read(defaults)) ? " (default)" : " (changed)";
			}
			add(result, needle, line);
		}
		return result;
	}

	/**
	 * The object holding {@code component}'s state: the first field holding an instance of its state class, else the
	 * component itself (its state is itself, or XML built from its own fields). Not getState(), which may save
	 * schemes to disk or need the EDT, as the IDE calls it only to save.
	 */
	@SuppressWarnings("unchecked")
	private static Object stateBean(Object component) {
		Class<?> stateClass = ComponentSerializationUtil.getStateClass((Class<? extends PersistentStateComponent<?>>) component.getClass());
		if (stateClass == Element.class || stateClass.isInstance(component)) {
			return component;
		}
		for (Class<?> c = component.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) {
					continue;
				}
				try {
					field.setAccessible(true);
					Object value = field.get(component);
					if (stateClass.isInstance(value)) {
						return value;
					}
				} catch (ReflectiveOperationException | RuntimeException e) {
					// a field that cannot be read is not the state
				}
			}
		}
		return component;
	}

	/** A new instance of a state class, holding its defaults; null when it has no no-argument constructor. */
	private static Object fresh(Class<?> type) {
		try {
			Constructor<?> constructor = type.getDeclaredConstructor();
			constructor.setAccessible(true);
			return constructor.newInstance();
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	private static String render(Object value) {
		String text = value == null ? "null" : value.getClass().isArray() ? arrayText(value) : String.valueOf(value);
		text = text.replaceAll("\\s+", " ");
		return text.length() > MAX_VALUE ? text.substring(0, MAX_VALUE) + "..." : text;
	}

	private static String arrayText(Object array) {
		Object[] items = new Object[Array.getLength(array)];
		Arrays.setAll(items, i -> Array.get(array, i));
		return Arrays.toString(items);
	}
}
