package com.fsodomka.idepilot;

import com.intellij.openapi.client.ClientAppSession;
import com.intellij.openapi.client.ClientKind;
import com.intellij.openapi.client.ClientProjectSession;
import com.intellij.openapi.client.ClientSessionsManager;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.configurationStore.ComponentSerializationUtil;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.options.advanced.AdvancedSettingBean;
import com.intellij.openapi.options.advanced.AdvancedSettings;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.util.JDOMExternalizable;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.util.registry.RegistryValue;
import com.intellij.serviceContainer.ComponentManagerImpl;
import com.intellij.util.xmlb.Accessor;
import com.intellij.util.xmlb.XmlSerializerUtil;
import org.jdom.Element;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * The effective values of IDE and project settings, defaults included: registry keys, Advanced Settings, and the
 * persisted state of the application's and open projects' services.
 */
final class IdeSettings {
	private static final int MAX_VALUE = 160;
	/** The shortest find that creates the services it names. */
	private static final int MIN_CREATE = 4;

	private IdeSettings() {
	}

	/**
	 * The settings whose line contains {@code find} (case-insensitive), at most {@code limit}:
	 * {@code registry KEY = VALUE}, {@code advanced ID "title" = VALUE}, and
	 * {@code app|project NAME FILE Component.property = VALUE}, marked (default) or (changed) where known.
	 */
	static String find(String find, int limit) {
		String needle = find.toLowerCase(Locale.ROOT);
		List<String> lines = new ArrayList<>();
		for (RegistryValue value : Registry.getAll()) {
			add(lines, needle, "registry " + value.getKey() + " = " + value.asString() + (value.isChangedFromDefault() ? " (changed)" : ""));
		}
		for (AdvancedSettingBean bean : AdvancedSettingBean.EP_NAME.getExtensionList()) {
			add(lines, needle, advanced(bean));
		}
		states(ApplicationManager.getApplication(), "app", needle, lines);
		// per-client services (client="all"), such as the editor's settings, live in the local session's container
		for (ClientAppSession session : ClientSessionsManager.getAppSessions(ClientKind.LOCAL)) {
			states(session, "app", needle, lines);
		}
		for (Project project : ProjectManager.getInstance().getOpenProjects()) {
			states(project, "project " + project.getName(), needle, lines);
			for (ClientProjectSession session : ClientSessionsManager.getProjectSessions(project, ClientKind.LOCAL)) {
				states(session, "project " + project.getName(), needle, lines);
			}
		}
		if (lines.isEmpty()) {
			return "(no setting contains \"" + find + "\"; only services already created are searched, and, for a find of "
					+ MIN_CREATE + " or more characters, those whose class or state name contains it)";
		}
		String more = lines.size() > limit ? "\n... " + (lines.size() - limit) + " more; narrow find" : "";
		return String.join("\n", lines.subList(0, Math.min(lines.size(), limit))) + more;
	}

	private static void add(List<String> lines, String needle, String line) {
		if (line != null && line.toLowerCase(Locale.ROOT).contains(needle)) {
			lines.add(line);
		}
	}

	private static String advanced(AdvancedSettingBean bean) {
		try {
			// getSetting is protected; the public getters need the type, and an enum's class is internal
			Method getSetting = AdvancedSettings.class.getDeclaredMethod("getSetting", String.class);
			getSetting.setAccessible(true);
			Object value = getSetting.invoke(AdvancedSettings.getInstance(), bean.id);
			return "advanced " + bean.id + " \"" + bean.title() + "\" = " + render(value)
					+ (Objects.equals(value, bean.getDefaultValueObject()) ? " (default)" : " (changed)");
		} catch (ReflectiveOperationException | RuntimeException e) {
			return null;
		}
	}

	/**
	 * The properties of {@code manager}'s persistent services: those created, and those whose class or state name
	 * contains {@code needle}, created now. A state kept as XML is read from the service's own public properties.
	 */
	private static void states(Object manager, String scope, String needle, List<String> lines) {
		if (!(manager instanceof ComponentManagerImpl components)) {
			return;
		}
		// creates the matching services that are not created yet; a service may fail to create, as in its own use.
		// Not for a short find, which would create most of them, each with its own startup work
		if (needle.strip().length() >= MIN_CREATE) {
			try {
				Iterator<Object> created = components.instances(true, c -> isState(c) && named(c).toLowerCase(Locale.ROOT).contains(needle)).iterator();
				while (created.hasNext()) {
					created.next();
				}
			} catch (RuntimeException e) {
				// list what can be listed
			}
		}
		for (Iterator<Object> it = components.instances(false, IdeSettings::isState).iterator(); it.hasNext(); ) {
			Object component = it.next();
			try {
				lines.addAll(properties(scope, component, needle));
			} catch (RuntimeException | LinkageError e) {
				// a state that cannot be read now is skipped
			}
		}
	}

	private static boolean isState(Class<?> c) {
		return PersistentStateComponent.class.isAssignableFrom(c);
	}

	/** The class's simple name and its @State name. */
	private static String named(Class<?> c) {
		State state = c.getAnnotation(State.class);
		return c.getSimpleName() + (state == null ? "" : " " + state.name());
	}

	private static List<String> properties(String scope, Object component, String needle) {
		State spec = component.getClass().getAnnotation(State.class);
		String name = spec == null ? component.getClass().getSimpleName() : spec.name();
		String file = spec == null || spec.storages().length == 0 ? "" : spec.storages()[0].value() + " ";
		Object bean = stateBean(component);
		List<String> result = new ArrayList<>();
		if (bean instanceof JDOMExternalizable) { // writes itself; the serializer refuses its accessors
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
