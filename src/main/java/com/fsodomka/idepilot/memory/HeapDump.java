package com.fsodomka.idepilot.memory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.Predicate;

/**
 * A heap dump in HPROF format (as {@code jcmd PID GC.heap_dump} writes it), indexed to find the shortest chain of
 * references from a GC root to an instance of a class: what keeps it alive. Reads the file memory-mapped; names are
 * decoded only for the result.
 */
final class HeapDump {
	// top-level records
	private static final int STRING = 0x01, LOAD_CLASS = 0x02, STACK_FRAME = 0x04, STACK_TRACE = 0x05, HEAP_DUMP = 0x0C, HEAP_DUMP_SEGMENT = 0x1C;
	// heap dump sub-records
	private static final int ROOT_UNKNOWN = 0xFF, ROOT_JNI_GLOBAL = 0x01, ROOT_JNI_LOCAL = 0x02, ROOT_JAVA_FRAME = 0x03, ROOT_NATIVE_STACK = 0x04,
			ROOT_STICKY_CLASS = 0x05, ROOT_THREAD_BLOCK = 0x06, ROOT_MONITOR_USED = 0x07, ROOT_THREAD_OBJECT = 0x08,
			CLASS_DUMP = 0x20, INSTANCE_DUMP = 0x21, OBJ_ARRAY_DUMP = 0x22, PRIM_ARRAY_DUMP = 0x23;
	private static final int OBJECT = 2;

	private final Mapped in;
	private final int idSize;
	private final LongLongMap strings = new LongLongMap(); // string ID -> its record's position
	private final LongLongMap classNames = new LongLongMap(); // class object ID -> name string ID
	private final Map<Integer, Long> classBySerial = new HashMap<>(); // class serial -> name string ID
	private final Map<Long, ClassInfo> classes = new HashMap<>();
	private final LongLongMap objects = new LongLongMap(); // instance or array ID -> position of its sub-record body
	private final List<Root> roots = new ArrayList<>();
	private final Map<Integer, Long> threadObjects = new HashMap<>(); // thread serial -> thread object ID
	private final Map<Integer, Integer> threadTraces = new HashMap<>(); // thread serial -> stack trace serial
	private final Map<Integer, long[]> traces = new HashMap<>(); // stack trace serial -> frame IDs
	private final LongLongMap frames = new LongLongMap(); // frame ID -> its record's position
	private long referenceClass; // java.lang.ref.Reference, whose referent the path does not follow: weak, soft and phantom references

	private record Root(long id, int kind, int thread, int frame) {
	}

	/** A class: its superclass and loader, its own instance fields, and its static object fields (name string IDs and values). */
	private record ClassInfo(long superId, long loaderId, long[] fieldNames, byte[] fieldTypes, long[] staticNames, long[] staticValues) {
	}

	private HeapDump(Mapped in) {
		this.in = in;
		long pos = 0;
		while (in.u1(pos) != 0) { // "JAVA PROFILE 1.0.2\0"
			pos++;
		}
		idSize = in.i4(pos + 1);
		parse(pos + 1 + 4 + 8);
		classNames.forEach((id, name) -> {
			if (string(name).equals("java/lang/ref/Reference")) {
				referenceClass = id;
			}
		});
	}

	/**
	 * The shortest chain of references from a GC root to an instance of {@code className} (binary name, e.g.
	 * {@code a.Outer$Inner}) in the dump at {@code file}, the instance first, ending at the first static field or at
	 * the root (a thread's local variable with its frame, a JNI reference, a system class). For an array class, such as
	 * {@code byte[]}, also the {@code limit} fields holding most of its arrays, of those whose class {@code holderShown} accepts.
	 */
	static String path(Path file, String className, int limit, Predicate<String> holderShown) throws IOException {
		// the index takes about as many bytes of the IDE's heap as the dump has (two maps of 16 bytes per object, ~60 bytes per object in a dump)
		Runtime runtime = Runtime.getRuntime();
		long free = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory());
		if (Files.size(file) > free) {
			throw new IllegalStateException(String.format("The heap dump of %,d MB needs about as much of the IDE's heap, which has %,d MB free; "
					+ "raise the IDE's -Xmx (Help | Change Memory Settings)", Files.size(file) >> 20, free >> 20));
		}
		try (Mapped mapped = new Mapped(file)) {
			return new HeapDump(mapped).path(className, limit, holderShown);
		}
	}

	private void parse(long pos) {
		while (pos < in.size) {
			int tag = in.u1(pos);
			long length = in.i4(pos + 5) & 0xFFFFFFFFL;
			long body = pos + 9;
			switch (tag) {
				case STRING -> strings.put(id(body), pos);
				case LOAD_CLASS -> {
					long name = id(body + 4 + idSize + 4);
					classNames.put(id(body + 4), name);
					classBySerial.put(in.i4(body), name);
				}
				case STACK_FRAME -> frames.put(id(body), pos);
				case STACK_TRACE -> {
					int count = in.i4(body + 8);
					long[] ids = new long[count];
					for (int i = 0; i < count; i++) {
						ids[i] = id(body + 12 + (long) i * idSize);
					}
					traces.put(in.i4(body), ids);
				}
				case HEAP_DUMP, HEAP_DUMP_SEGMENT -> heap(body, body + length);
				default -> {
				}
			}
			pos = body + length;
		}
	}

	private void heap(long pos, long end) {
		while (pos < end) {
			int tag = in.u1(pos++);
			long id = id(pos);
			switch (tag) {
				case ROOT_UNKNOWN, ROOT_STICKY_CLASS, ROOT_MONITOR_USED -> {
					roots.add(new Root(id, tag, -1, -1));
					pos += idSize;
				}
				case ROOT_JNI_GLOBAL -> {
					roots.add(new Root(id, tag, -1, -1));
					pos += 2L * idSize;
				}
				case ROOT_JNI_LOCAL, ROOT_JAVA_FRAME -> {
					roots.add(new Root(id, tag, in.i4(pos + idSize), in.i4(pos + idSize + 4)));
					pos += idSize + 8;
				}
				case ROOT_NATIVE_STACK, ROOT_THREAD_BLOCK -> {
					roots.add(new Root(id, tag, in.i4(pos + idSize), -1));
					pos += idSize + 4;
				}
				case ROOT_THREAD_OBJECT -> {
					int serial = in.i4(pos + idSize);
					threadObjects.put(serial, id);
					threadTraces.put(serial, in.i4(pos + idSize + 4));
					roots.add(new Root(id, tag, serial, -1));
					pos += idSize + 8;
				}
				case CLASS_DUMP -> pos = classDump(pos);
				case INSTANCE_DUMP -> {
					objects.put(id, pos);
					pos += idSize + 4 + idSize + 4 + (in.i4(pos + idSize + 4 + idSize) & 0xFFFFFFFFL);
				}
				case OBJ_ARRAY_DUMP -> {
					objects.put(id, pos);
					pos += idSize + 4 + 4 + idSize + (long) in.i4(pos + idSize + 4) * idSize;
				}
				case PRIM_ARRAY_DUMP -> {
					objects.put(id, pos);
					pos += idSize + 4 + 4 + 1 + (long) in.i4(pos + idSize + 4) * size(in.u1(pos + idSize + 8));
				}
				default -> throw new IllegalStateException("Unknown heap dump record 0x" + Integer.toHexString(tag) + " at byte " + (pos - 1));
			}
		}
	}

	private long classDump(long pos) {
		long id = id(pos);
		long superId = id(pos + idSize + 4);
		long loaderId = id(pos + 2L * idSize + 4);
		pos += 7L * idSize + 4 + 4;
		int constants = in.u2(pos);
		pos += 2;
		for (int i = 0; i < constants; i++) {
			pos += 2 + 1 + size(in.u1(pos + 2));
		}
		int statics = in.u2(pos);
		pos += 2;
		long[] staticNames = new long[statics];
		long[] staticValues = new long[statics];
		int objectStatics = 0;
		for (int i = 0; i < statics; i++) {
			int type = in.u1(pos + idSize);
			if (type == OBJECT) {
				staticNames[objectStatics] = id(pos);
				staticValues[objectStatics++] = id(pos + idSize + 1);
			}
			pos += idSize + 1 + size(type);
		}
		int fields = in.u2(pos);
		pos += 2;
		long[] names = new long[fields];
		byte[] types = new byte[fields];
		for (int i = 0; i < fields; i++) {
			names[i] = id(pos);
			types[i] = (byte) in.u1(pos + idSize);
			pos += idSize + 1;
		}
		classes.put(id, new ClassInfo(superId, loaderId, names, types, Arrays.copyOf(staticNames, objectStatics),
				Arrays.copyOf(staticValues, objectStatics)));
		return pos;
	}

	/** Whether field {@code name} of java.lang.ref.Reference does not keep its object alive: referent, and discovered (the GC's list). */
	private boolean weak(long name) {
		String field = string(name);
		return field.equals("referent") || field.equals("discovered");
	}

	private boolean exists(long id) {
		return id != 0 && (objects.get(id) >= 0 || classes.containsKey(id));
	}

	/** Passes each object {@code id} refers to: a class's statics, superclass and loader; an instance's class and fields; an array's elements. */
	private void references(long id, LongConsumer out) {
		ClassInfo info = classes.get(id);
		if (info != null) {
			for (long value : info.staticValues) {
				out.accept(value);
			}
			out.accept(info.superId);
			out.accept(info.loaderId);
			return;
		}
		long pos = objects.get(id);
		int tag = in.u1(pos - 1);
		if (tag == INSTANCE_DUMP) {
			long classId = id(pos + idSize + 4);
			out.accept(classId);
			long field = pos + idSize + 4 + idSize + 4;
			for (long c = classId; c != 0; c = classes.get(c).superId) {
				ClassInfo ci = classes.get(c);
				for (int i = 0; i < ci.fieldTypes.length; i++) {
					if (ci.fieldTypes[i] == OBJECT && !(c == referenceClass && weak(ci.fieldNames[i]))) {
						out.accept(id(field));
					}
					field += size(ci.fieldTypes[i]);
				}
			}
		} else if (tag == OBJ_ARRAY_DUMP) {
			int count = in.i4(pos + idSize + 4);
			long element = pos + idSize + 4 + 4 + idSize;
			for (int i = 0; i < count; i++) {
				out.accept(id(element + (long) i * idSize));
			}
		}
	}

	/** How {@code from} refers to {@code to}, as one step of a chain: "static A.f", "A.f", "A[3]", "class A <class loader>". */
	private String step(long from, long to) {
		ClassInfo info = classes.get(from);
		if (info != null) {
			for (int i = 0; i < info.staticValues.length; i++) {
				if (info.staticValues[i] == to) {
					return "static " + className(from) + "." + string(info.staticNames[i]);
				}
			}
			return "class " + className(from) + (info.loaderId == to ? " <class loader>" : " <superclass>");
		}
		long pos = objects.get(from);
		if (in.u1(pos - 1) == OBJ_ARRAY_DUMP) {
			String type = typeOf(from);
			int count = in.i4(pos + idSize + 4);
			for (int i = 0; i < count; i++) {
				if (id(pos + idSize + 4 + 4 + idSize + (long) i * idSize) == to) {
					return type.substring(0, type.length() - 2) + "[" + i + "]";
				}
			}
			return type;
		}
		long classId = id(pos + idSize + 4);
		long field = pos + idSize + 4 + idSize + 4;
		for (long c = classId; c != 0; c = classes.get(c).superId) {
			ClassInfo ci = classes.get(c);
			for (int i = 0; i < ci.fieldTypes.length; i++) {
				if (ci.fieldTypes[i] == OBJECT && id(field) == to) {
					return className(classId) + "." + string(ci.fieldNames[i]);
				}
				field += size(ci.fieldTypes[i]);
			}
		}
		return className(classId) + " <class>";
	}

	private String path(String className, int limit, Predicate<String> holderShown) {
		long start = System.nanoTime();
		LongLongMap targets = new LongLongMap();
		classNames.forEach((id, name) -> {
			if (javaName(string(name)).equals(className)) {
				targets.put(id, 1);
			}
		});
		int primitive = primitiveArray(className);
		if (targets.size() == 0 && primitive < 0) {
			throw new IllegalArgumentException("No class " + className + " in the heap dump; give its binary name, e.g. a.Outer$Inner");
		}
		// parent: the object that first reached each one; a root's is -2 - its index in roots
		LongLongMap parent = new LongLongMap();
		LongQueue queue = new LongQueue();
		for (int i = 0; i < roots.size(); i++) {
			long id = roots.get(i).id;
			if (exists(id) && parent.get(id) == -1) {
				parent.put(id, -2 - i);
				queue.add(id);
			}
		}
		while (!queue.isEmpty()) {
			long id = queue.poll();
			if (isTarget(id, targets, primitive)) {
				return chain(id, parent) + String.format("\n(%,d of %,d objects searched in %d ms)", parent.size(), objects.size() + classes.size(),
						(System.nanoTime() - start) / 1_000_000) + (className.endsWith("[]") ? holders(className, targets, primitive, limit, holderShown) : "");
			}
			references(id, target -> {
				if (exists(target) && parent.get(target) == -1) {
					parent.put(target, id);
					queue.add(target);
				}
			});
		}
		boolean[] any = {false};
		objects.forEach((id, pos) -> any[0] |= isTarget(id, targets, primitive));
		return any[0] ? "No instance of " + className + " is strongly reachable from a GC root: only weak, soft or phantom references hold them"
				: "No instance of " + className + " in the heap dump (of live objects)";
	}

	/** Whether {@code id} is an instance or object array of a class in {@code targets}, or a primitive array of element type {@code primitive}. */
	private boolean isTarget(long id, LongLongMap targets, int primitive) {
		long pos = objects.get(id);
		if (pos < 0) {
			return false;
		}
		return switch (in.u1(pos - 1)) {
			case INSTANCE_DUMP -> targets.get(id(pos + idSize + 4)) == 1;
			case OBJ_ARRAY_DUMP -> targets.get(id(pos + idSize + 4 + 4)) == 1;
			default -> in.u1(pos + idSize + 8) == primitive;
		};
	}

	/** The HPROF element type of a one-dimensional primitive array class such as {@code byte[]}, else -1. */
	private static int primitiveArray(String className) {
		for (int type = 4; type <= 11; type++) {
			if ((primitive(type) + "[]").equals(className)) {
				return type;
			}
		}
		return -1;
	}

	/**
	 * The {@code limit} fields, static fields and array types referring to most objects of the target class, as
	 * COUNT HOLDER lines after a heading: for an array class, which owner type it grows with.
	 */
	private String holders(String className, LongLongMap targets, int primitive, int limit, Predicate<String> holderShown) {
		Map<String, Long> counts = new HashMap<>();
		classes.forEach((classId, info) -> {
			for (int i = 0; i < info.staticValues.length; i++) {
				if (isTarget(info.staticValues[i], targets, primitive)) {
					counts.merge("static " + className(classId) + "." + string(info.staticNames[i]), 1L, Long::sum);
				}
			}
		});
		Map<Long, String[]> labels = new HashMap<>(); // class ID -> "Class.field" by field index over its hierarchy, filled when met
		objects.forEach((id, pos) -> {
			int tag = in.u1(pos - 1);
			if (tag == INSTANCE_DUMP) {
				long classId = id(pos + idSize + 4);
				long field = pos + idSize + 4 + idSize + 4;
				int index = 0;
				for (long c = classId; c != 0; c = classes.get(c).superId) {
					ClassInfo ci = classes.get(c);
					for (int i = 0; i < ci.fieldTypes.length; i++, index++) {
						if (ci.fieldTypes[i] == OBJECT && isTarget(id(field), targets, primitive)) {
							String[] names = labels.computeIfAbsent(classId, k -> new String[fieldCount(k)]);
							if (names[index] == null) {
								names[index] = className(classId) + "." + string(ci.fieldNames[i]);
							}
							counts.merge(names[index], 1L, Long::sum);
						}
						field += size(ci.fieldTypes[i]);
					}
				}
			} else if (tag == OBJ_ARRAY_DUMP) {
				int count = in.i4(pos + idSize + 4);
				long element = pos + idSize + 4 + 4 + idSize;
				String label = null;
				for (int i = 0; i < count; i++) {
					if (isTarget(id(element + (long) i * idSize), targets, primitive)) {
						label = label == null ? typeOf(id) + " element" : label;
						counts.merge(label, 1L, Long::sum);
					}
				}
			}
		});
		return "\nWhat holds " + className + " objects, as COUNT HOLDER, most first:\n" + String.join("\n", counts.entrySet().stream()
				.filter(e -> holderShown.test(e.getKey().startsWith("static ") ? e.getKey().substring(7) : e.getKey()))
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(limit)
				.map(e -> e.getValue() + "\t" + e.getKey())
				.toList());
	}

	/** The instance fields of class {@code classId} and its superclasses. */
	private int fieldCount(long classId) {
		int count = 0;
		for (long c = classId; c != 0; c = classes.get(c).superId) {
			count += classes.get(c).fieldTypes.length;
		}
		return count;
	}

	private String chain(long id, LongLongMap parent) {
		List<String> steps = new ArrayList<>(List.of(typeOf(id)));
		long child = id;
		for (long from = parent.get(child); from >= 0; child = from, from = parent.get(child)) {
			String step = step(from, child);
			steps.add(step);
			if (step.startsWith("static ")) {
				return String.join("\n  <- ", steps);
			}
		}
		steps.add(describe(roots.get((int) (-2 - parent.get(child)))));
		return String.join("\n  <- ", steps);
	}

	private String typeOf(long id) {
		if (classes.containsKey(id)) {
			return "class " + className(id);
		}
		long pos = objects.get(id);
		return switch (in.u1(pos - 1)) {
			case INSTANCE_DUMP -> className(id(pos + idSize + 4));
			case OBJ_ARRAY_DUMP -> className(id(pos + idSize + 4 + 4));
			default -> primitive(in.u1(pos + idSize + 8)) + "[]";
		};
	}

	private String describe(Root root) {
		String thread = root.thread < 0 ? "" : " in thread " + threadName(root.thread);
		return switch (root.kind) {
			case ROOT_JAVA_FRAME -> "local variable" + thread + frame(root.thread, root.frame);
			case ROOT_JNI_LOCAL -> "JNI local reference" + thread + frame(root.thread, root.frame);
			case ROOT_JNI_GLOBAL -> "JNI global reference";
			case ROOT_STICKY_CLASS -> "system class";
			case ROOT_THREAD_OBJECT -> "the thread object of " + threadName(root.thread);
			case ROOT_NATIVE_STACK -> "native stack" + thread;
			case ROOT_THREAD_BLOCK -> "thread block" + thread;
			case ROOT_MONITOR_USED -> "monitor in use";
			default -> "unknown GC root";
		};
	}

	/** " at Class.method:line" of {@code thread}'s stack frame {@code frame}, or "" when the dump lacks it. */
	private String frame(int thread, int frame) {
		long[] ids = traces.get(threadTraces.getOrDefault(thread, -1));
		long pos = ids == null || frame < 0 || frame >= ids.length ? -1 : frames.get(ids[frame]);
		if (pos < 0) {
			return "";
		}
		long body = pos + 9;
		Long classSerialName = classBySerial.get(in.i4(body + 4L * idSize));
		int line = in.i4(body + 4L * idSize + 4);
		return " at " + (classSerialName == null ? "?" : javaName(string(classSerialName))) + "." + string(id(body + idSize))
				+ (line > 0 ? ":" + line : "");
	}

	/** The quoted name of the thread with {@code serial}, read from its Thread object, else #serial. */
	private String threadName(int serial) {
		Long thread = threadObjects.get(serial);
		long name = thread == null ? 0 : field(thread, "name");
		long value = name == 0 ? 0 : field(name, "value");
		long pos = value == 0 ? -1 : objects.get(value);
		if (pos < 0 || in.u1(pos - 1) != PRIM_ARRAY_DUMP) {
			return "#" + serial;
		}
		int type = in.u1(pos + idSize + 8);
		byte[] bytes = in.bytes(pos + idSize + 9, in.i4(pos + idSize + 4) * size(type));
		// a byte[] value is LATIN1 (coder 0) or UTF-16 in the JVM's byte order; a char[] value (Java 8) is UTF-16, big-endian in the dump
		return "\"" + new String(bytes, type == 8 && field(name, "coder") == 0 ? StandardCharsets.ISO_8859_1
				: type == 8 ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE) + "\"";
	}

	/** Field {@code name} of instance {@code id}: an object ID, or a byte or int value; 0 when it has none. */
	private long field(long id, String name) {
		long pos = objects.get(id);
		if (pos < 0 || in.u1(pos - 1) != INSTANCE_DUMP) {
			return 0;
		}
		long field = pos + idSize + 4 + idSize + 4;
		for (long c = id(pos + idSize + 4); c != 0; c = classes.get(c).superId) {
			ClassInfo ci = classes.get(c);
			for (int i = 0; i < ci.fieldTypes.length; i++) {
				if (name.equals(string(ci.fieldNames[i]))) {
					return switch (ci.fieldTypes[i]) {
						case OBJECT -> id(field);
						case 4, 8 -> in.u1(field);
						case 10 -> in.i4(field);
						default -> 0;
					};
				}
				field += size(ci.fieldTypes[i]);
			}
		}
		return 0;
	}

	private String className(long classId) {
		long name = classNames.get(classId);
		return name < 0 ? "?" : javaName(string(name));
	}

	private String string(long id) {
		long pos = strings.get(id);
		return pos < 0 ? "?" : in.utf8(pos + 9 + idSize, (int) ((in.i4(pos + 5) & 0xFFFFFFFFL) - idSize));
	}

	private long id(long pos) {
		return idSize == 8 ? in.i8(pos) : in.i4(pos) & 0xFFFFFFFFL;
	}

	private int size(int type) {
		return switch (type) {
			case OBJECT -> idSize;
			case 4, 8 -> 1;
			case 5, 9 -> 2;
			case 6, 10 -> 4;
			case 7, 11 -> 8;
			default -> throw new IllegalStateException("Unknown field type " + type + " in the heap dump");
		};
	}

	private static String primitive(int type) {
		return switch (type) {
			case 4 -> "boolean";
			case 5 -> "char";
			case 6 -> "float";
			case 7 -> "double";
			case 8 -> "byte";
			case 9 -> "short";
			case 10 -> "int";
			default -> "long";
		};
	}

	/** A dump's class name as Java writes it: java/lang/String to java.lang.String, [Ljava/lang/Object; to java.lang.Object[], [[B to byte[][]. */
	static String javaName(String name) {
		int dims = 0;
		while (dims < name.length() && name.charAt(dims) == '[') {
			dims++;
		}
		String base = name.substring(dims);
		if (dims > 0) {
			base = base.startsWith("L") ? base.substring(1, base.length() - 1) : switch (base) {
				case "Z" -> "boolean";
				case "C" -> "char";
				case "F" -> "float";
				case "D" -> "double";
				case "B" -> "byte";
				case "S" -> "short";
				case "I" -> "int";
				default -> "long";
			};
		}
		return base.replace('/', '.') + "[]".repeat(dims);
	}

	/** The file, memory-mapped in chunks of 1 GB, and unmapped on close so it can be deleted (Windows keeps mapped files). */
	private static final class Mapped implements AutoCloseable {
		private static final int SHIFT = 30;
		private static final int MASK = (1 << SHIFT) - 1;
		private final MappedByteBuffer[] chunks;
		final long size;

		Mapped(Path path) throws IOException {
			try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r"); FileChannel channel = file.getChannel()) {
				size = channel.size();
				chunks = new MappedByteBuffer[(int) ((size >> SHIFT) + 1)];
				for (int i = 0; i < chunks.length; i++) {
					long start = (long) i << SHIFT;
					chunks[i] = channel.map(FileChannel.MapMode.READ_ONLY, start, Math.min(size - start, 1L << SHIFT));
				}
			}
		}

		int u1(long pos) {
			return chunks[(int) (pos >>> SHIFT)].get((int) (pos & MASK)) & 0xFF;
		}

		int u2(long pos) {
			return u1(pos) << 8 | u1(pos + 1);
		}

		int i4(long pos) {
			return u1(pos) << 24 | u1(pos + 1) << 16 | u1(pos + 2) << 8 | u1(pos + 3);
		}

		long i8(long pos) {
			return (long) i4(pos) << 32 | i4(pos + 4) & 0xFFFFFFFFL;
		}

		byte[] bytes(long pos, int count) {
			byte[] out = new byte[count];
			for (int i = 0; i < count; i++) {
				out[i] = (byte) u1(pos + i);
			}
			return out;
		}

		String utf8(long pos, int count) {
			return new String(bytes(pos, count), StandardCharsets.UTF_8);
		}

		/** Unmaps the chunks through sun.misc.Unsafe.invokeCleaner, the only way before the GC frees them; else they stay mapped. */
		@Override
		public void close() {
			try {
				Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
				Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
				theUnsafe.setAccessible(true);
				Method invokeCleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
				for (MappedByteBuffer chunk : chunks) {
					invokeCleaner.invoke(theUnsafe.get(null), chunk);
				}
			} catch (ReflectiveOperationException | RuntimeException e) {
				// left to the GC: deleting the file may fail until then
			}
		}
	}

	/** Open-addressing map from long keys (not 0) to long values, -1 for a missing key. */
	private static final class LongLongMap {
		private long[] keys = new long[1 << 10];
		private long[] values = new long[1 << 10];
		private int size;

		int size() {
			return size;
		}

		long get(long key) {
			int mask = keys.length - 1;
			for (int i = hash(key) & mask; keys[i] != 0; i = (i + 1) & mask) {
				if (keys[i] == key) {
					return values[i];
				}
			}
			return -1;
		}

		void put(long key, long value) {
			if (size * 10L >= keys.length * 7L) {
				long[] oldKeys = keys;
				long[] oldValues = values;
				keys = new long[oldKeys.length * 2];
				values = new long[oldKeys.length * 2];
				size = 0;
				for (int i = 0; i < oldKeys.length; i++) {
					if (oldKeys[i] != 0) {
						put(oldKeys[i], oldValues[i]);
					}
				}
			}
			int mask = keys.length - 1;
			int i = hash(key) & mask;
			while (keys[i] != 0 && keys[i] != key) {
				i = (i + 1) & mask;
			}
			if (keys[i] == 0) {
				size++;
			}
			keys[i] = key;
			values[i] = value;
		}

		interface Entry {
			void accept(long key, long value);
		}

		void forEach(Entry entry) {
			for (int i = 0; i < keys.length; i++) {
				if (keys[i] != 0) {
					entry.accept(keys[i], values[i]);
				}
			}
		}

		private static int hash(long key) {
			return Long.hashCode(key * 0x9E3779B97F4A7C15L);
		}
	}

	/** A growing FIFO queue of longs. */
	private static final class LongQueue {
		private long[] items = new long[1 << 12];
		private int head;
		private int tail;

		boolean isEmpty() {
			return head == tail;
		}

		void add(long item) {
			if (tail == items.length) {
				int count = tail - head;
				long[] grown = count * 2 > items.length ? new long[items.length * 2] : items;
				System.arraycopy(items, head, grown, 0, count);
				items = grown;
				head = 0;
				tail = count;
			}
			items[tail++] = item;
		}

		long poll() {
			return items[head++];
		}
	}
}
