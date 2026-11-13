package com.fsodomka.idepilot.memory;

import com.fsodomka.idepilot.Edt;
import com.fsodomka.idepilot.Evaluator;
import com.fsodomka.idepilot.RunConsoles;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.intellij.xdebugger.XDebugSession;
import com.intellij.xdebugger.XDebugSessionListener;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * pilot_memory: the live instances per class of a running Java debuggee and their growth since a baseline, without
 * pausing the program; and, from a heap dump of any running Java program, the chain of references keeping an
 * instance alive.
 */
public final class DebuggeeMemory {
	private static final Set<String> OPS = Set.of("counts", "baseline", "diff", "path");
	/** The JDK's packages, whose classes and arrays of them hideJdk leaves out. */
	private static final List<String> JDK_PACKAGES = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.");
	/** A class line of jcmd GC.class_histogram: "   1:   1234   56789  java.lang.String (java.base@25)". */
	private static final Pattern HISTOGRAM_LINE = Pattern.compile("(?m)^\\s*\\d+:\\s+(\\d+)\\s+\\d+\\s+(\\S+)");
	/** The instance counts per class name at each baseline, per debug session or Run process; dropped when it ends. */
	private static final Map<Object, Baseline> baselines = new ConcurrentHashMap<>();

	private record Baseline(Map<String, Long> counts, long at) {
	}

	private DebuggeeMemory() {
	}

	public static String memory(String op, String session, String className, String filter, boolean hideJdk, int limit, double seconds)
			throws InterruptedException {
		String operation = op == null ? "counts" : op;
		if (!OPS.contains(operation)) {
			throw new IllegalArgumentException("Unknown op " + operation + "; use counts, baseline, diff or path");
		}
		if (limit < 1) {
			throw new IllegalArgumentException("limit must be at least 1");
		}
		if (operation.equals("path") && className == null) {
			throw new IllegalArgumentException("op path needs class");
		}
		if (!operation.equals("path") && className != null) {
			throw new IllegalArgumentException("class is only for op path; filter narrows the others");
		}
		if (operation.equals("path")) {
			return path(session, className, limit, hideJdk, seconds);
		}
		if (Edt.compute(() -> Evaluator.debugging(session))) {
			try {
				return Evaluator.onDebuggerThread(session, seconds,
						debuggee -> report(operation, debuggee.session(), counts(debuggee.vm()), filter, hideJdk, limit));
			} catch (IllegalStateException e) {
				// report's and counts' own messages, without the exception class onDebuggerThread names
				throw e.getCause() instanceof IllegalArgumentException || e.getCause() instanceof IllegalStateException ? (RuntimeException) e.getCause() : e;
			}
		}
		// a Run tab: jcmd's class histogram, after a full GC that pauses the program
		Process process = Edt.compute(() -> RunConsoles.process(session));
		try {
			return report(operation, process, histogram(jcmd(process, seconds, "GC.class_histogram")), filter, hideJdk, limit);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot run jcmd: " + e.getMessage(), e);
		}
	}

	/** Op counts, baseline or diff over {@code counts}, the live instances per class of {@code owner}: an XDebugSession or a Run tab's Process. */
	private static String report(String operation, Object owner, Map<String, Long> counts, String filter, boolean hideJdk, int limit) {
		if (operation.equals("baseline")) {
			if (baselines.put(owner, new Baseline(counts, System.currentTimeMillis())) == null) {
				if (owner instanceof XDebugSession session) {
					session.addSessionListener(new XDebugSessionListener() {
						@Override
						public void sessionStopped() {
							baselines.remove(session);
						}
					});
				} else {
					((Process) owner).onExit().thenRun(() -> baselines.remove(owner));
				}
			}
			long total = counts.values().stream().mapToLong(Long::longValue).sum();
			return "Baseline taken: " + total + " live instances of " + counts.size() + " classes. Repeat the suspected action, "
					+ "then op diff. Most instances:\n" + String.join("\n", top(counts, filter, hideJdk, limit));
		}
		if (operation.equals("diff")) {
			Baseline baseline = baselines.get(owner);
			if (baseline == null) {
				throw new IllegalStateException("No baseline for this session yet; take one with op baseline");
			}
			List<String> grown = grown(baseline.counts(), counts, filter, hideJdk, limit);
			long ago = (System.currentTimeMillis() - baseline.at()) / 1000;
			return grown.isEmpty() ? "No class grew since the baseline " + ago + " s ago"
					: "Grown since the baseline " + ago + " s ago, as +GROWTH NOW CLASS; a class grown by about the number of "
							+ "repeats points at a leak, and op path shows what holds it (for an array, which fields hold most):\n"
							+ String.join("\n", grown);
		}
		return "Live instances per class, as COUNT CLASS, most first:\n" + String.join("\n", top(counts, filter, hideJdk, limit));
	}

	/** The live instances per class name in jcmd's GC.class_histogram {@code output}, summed over class loaders. */
	static Map<String, Long> histogram(String output) {
		Map<String, Long> counts = new HashMap<>();
		Matcher line = HISTOGRAM_LINE.matcher(output);
		while (line.find()) {
			String name = line.group(2);
			counts.merge(name.startsWith("[") ? HeapDump.javaName(name) : name, Long.parseLong(line.group(1)), Long::sum);
		}
		if (counts.isEmpty()) {
			throw new IllegalStateException("No class histogram from jcmd: " + output);
		}
		return counts;
	}

	/** The live instances per class name, summed over class loaders. Call on the debugger thread. */
	private static Map<String, Long> counts(VirtualMachine vm) {
		if (!vm.canGetInstanceInfo()) {
			throw new IllegalStateException("The debuggee's JVM cannot count instances");
		}
		List<ReferenceType> types = vm.allClasses().stream().filter(ReferenceType::isPrepared).toList();
		long[] counts = vm.instanceCounts(types);
		Map<String, Long> byName = new HashMap<>();
		for (int i = 0; i < counts.length; i++) {
			byName.merge(types.get(i).name(), counts[i], Long::sum);
		}
		return byName;
	}

	/** The classes matching {@code filter} with the most instances, as COUNT CLASS lines. */
	static List<String> top(Map<String, Long> counts, String filter, boolean hideJdk, int limit) {
		return counts.entrySet().stream()
				.filter(e -> e.getValue() > 0 && matches(e.getKey(), filter, hideJdk))
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(limit)
				.map(e -> e.getValue() + "\t" + e.getKey())
				.toList();
	}

	/** The classes matching {@code filter} with more instances {@code after} than {@code before}, most grown first, as +GROWTH NOW CLASS lines. */
	static List<String> grown(Map<String, Long> before, Map<String, Long> after, String filter, boolean hideJdk, int limit) {
		return after.entrySet().stream()
				.filter(e -> matches(e.getKey(), filter, hideJdk))
				.map(e -> Map.entry(e.getKey(), e.getValue() - before.getOrDefault(e.getKey(), 0L)))
				.filter(e -> e.getValue() > 0)
				.sorted(Map.Entry.<String, Long>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()))
				.limit(limit)
				.map(e -> "+" + e.getValue() + "\t" + after.get(e.getKey()) + "\t" + e.getKey())
				.toList();
	}

	private static boolean matches(String name, String filter, boolean hideJdk) {
		return (filter == null || name.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT)))
				&& !(hideJdk && JDK_PACKAGES.stream().anyMatch(name::startsWith));
	}

	/**
	 * The chain of references keeping an instance of {@code className} alive in the running Run/Debug tab named like
	 * {@code session}: dumps its heap with jcmd (a full GC first, so only live objects), finds the shortest chain from a
	 * GC root in the dump, and deletes the dump.
	 */
	private static String path(String session, String className, int limit, boolean hideJdk, double seconds) throws InterruptedException {
		Process process = Edt.compute(() -> RunConsoles.process(session));
		long start = System.nanoTime();
		Path directory = null;
		try {
			directory = Files.createTempDirectory("idepilot-heap");
			Path dump = directory.resolve("heap.hprof"); // jcmd refuses an existing file
			String output = jcmd(process, seconds, "GC.heap_dump", dump.toString());
			if (!Files.exists(dump)) {
				throw new IllegalStateException("jcmd could not dump the heap of process " + process.pid() + ": " + output);
			}
			long dumped = System.nanoTime();
			String path = HeapDump.path(dump, className, limit, holder -> matches(holder, null, hideJdk));
			return path + String.format(Locale.ROOT, "\n(heap dump of %,d MB in %.1f s, analysed in %.1f s)", Files.size(dump) >> 20,
					(dumped - start) / 1e9, (System.nanoTime() - dumped) / 1e9);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot dump or read the heap: " + e.getMessage(), e);
		} finally {
			delete(directory);
		}
	}

	/** Runs jcmd {@code command} on {@code process} and returns its output; fails after {@code seconds} or with a non-zero exit. */
	private static String jcmd(Process process, double seconds, String... command) throws IOException, InterruptedException {
		ProcessHandle target = target(process);
		List<String> line = new ArrayList<>(List.of(jcmdFile(target).toString(), Long.toString(target.pid())));
		line.addAll(List.of(command));
		Process jcmd = new ProcessBuilder(line).redirectErrorStream(true).start();
		try {
			// read while it runs: a class histogram outgrows the pipe's buffer, and jcmd would block writing it
			CompletableFuture<String> output = CompletableFuture.supplyAsync(() -> {
				try {
					return new String(jcmd.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
			});
			if (!jcmd.waitFor((long) (seconds * 1000), TimeUnit.MILLISECONDS)) {
				throw new IllegalStateException("jcmd " + command[0] + " took longer than " + seconds + " s; the program may still be busy with it, "
						+ "so wait before a retry with a longer timeout");
			}
			String text = output.join();
			if (jcmd.exitValue() != 0) {
				throw new IllegalStateException("jcmd " + command[0] + " failed for process " + target.pid() + ": " + text);
			}
			return text;
		} finally {
			if (jcmd.isAlive()) {
				jcmd.destroyForcibly();
			}
		}
	}

	/**
	 * The JVM to attach to: {@code process} when it is java, else (cmd.exe or a shell running mvn or a script) its only
	 * java descendant that starts no other java, such as the program Maven forked; else {@code process}.
	 */
	static ProcessHandle target(Process process) {
		ProcessHandle root = process.toHandle();
		if (isJava(root)) {
			return root;
		}
		List<ProcessHandle> leaves = root.descendants().filter(DebuggeeMemory::isJava)
				.filter(java -> java.descendants().noneMatch(DebuggeeMemory::isJava)).toList();
		return leaves.size() == 1 ? leaves.getFirst() : root;
	}

	private static boolean isJava(ProcessHandle process) {
		return process.info().command().map(command -> Path.of(command).getFileName().toString().toLowerCase(Locale.ROOT))
				.filter(name -> name.equals("java") || name.equals("java.exe") || name.equals("javaw.exe")).isPresent();
	}

	/** The jcmd of the JDK that runs {@code target}, else of the IDE's runtime. */
	private static Path jcmdFile(ProcessHandle target) {
		String name = SystemInfo.isWindows ? "jcmd.exe" : "jcmd";
		List<Path> candidates = new ArrayList<>();
		target.info().command().map(java -> Path.of(java).resolveSibling(name)).ifPresent(candidates::add);
		candidates.add(Path.of(System.getProperty("java.home"), "bin", name));
		return candidates.stream().filter(Files::isExecutable).findFirst().orElseThrow(() -> new IllegalStateException(
				"No jcmd: neither the program's JDK nor the IDE's runtime has one (" + candidates + ")"));
	}

	/** Deletes {@code directory} and its dump; while that is still open (jcmd timed out, the program still writes it), retries for an hour. */
	private static void delete(Path directory) {
		if (directory == null || deleted(directory)) {
			return;
		}
		AppExecutorUtil.getAppExecutorService().execute(() -> {
			try {
				for (int i = 0; i < 720 && !deleted(directory); i++) {
					Thread.sleep(5000);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
	}

	private static boolean deleted(Path directory) {
		try (var files = Files.list(directory)) {
			for (Path file : files.toList()) {
				Files.deleteIfExists(file);
			}
			Files.deleteIfExists(directory);
			return true;
		} catch (IOException e) {
			return false;
		}
	}
}
