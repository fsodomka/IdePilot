package com.fsodomka.idepilot;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.intellij.ide.AppLifecycleListener;
import com.intellij.notification.NotificationGroupManager;
import com.intellij.notification.NotificationType;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.concurrency.AppExecutorUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A minimal MCP server (Streamable HTTP, JSON responses only) at {@code http://127.0.0.1:<port>/mcp}, started with the
 * IDE frame, on {@link PilotSettings#port()}. Every request needs
 * the header {@code Authorization: Bearer <token>}, as the tools run code in the IDE; without a token (see
 * {@link PilotSettings#token()}) the server stays off.
 */
public final class PilotServer implements AppLifecycleListener {
	private static final Logger LOG = Logger.getInstance(PilotServer.class);
	static final int DEFAULT_PORT = 63400;
	private static final String PROTOCOL_VERSION = "2025-06-18";
	private static final Gson GSON = new Gson();
	private static HttpServer server;
	private static volatile String token;
	private static final String INFO_GROUP = "IDE Pilot";
	private static final String SECURITY_GROUP = "IDE Pilot security";
	private static final long REJECTED_WARNING_INTERVAL_MS = 5 * 60_000;
	/** The clients (name and version) whose connection was announced since the IDE started. */
	private static final Set<String> announced = ConcurrentHashMap.newKeySet();
	private static int rejected;
	private static long rejectedWarnedAt;
	private static boolean rejectedFlushScheduled;

	@Override
	public void appFrameCreated(@NotNull List<String> commandLineArgs) {
		OutputCapture.install();
		ErrorCapture.install();
		BuildSyncs.install();
		// the token is read from the password store, which may be slow
		ApplicationManager.getApplication().executeOnPooledThread(PilotServer::restart);
	}

	@Override
	public void appWillBeClosed(boolean isRestart) {
		stop();
	}

	/** (Re)starts the server with the current token and port; without a token it stays stopped. */
	static synchronized void restart() {
		stop();
		String current = PilotSettings.token();
		if (current == null) {
			LOG.info("IDE Pilot MCP server is off: no token; generate one in Settings | Tools | IDE Pilot");
			return;
		}
		token = current;
		int port = PilotSettings.port();
		try {
			HttpServer started = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
			started.createContext("/mcp", PilotServer::handle);
			started.setExecutor(Executors.newCachedThreadPool());
			started.start();
			server = started;
			LOG.info("IDE Pilot MCP server at " + url(port));
			notify(INFO_GROUP, "MCP server started at " + url(port), NotificationType.INFORMATION);
		} catch (IOException e) {
			LOG.warn("IDE Pilot could not listen on port " + port, e);
		}
	}

	private static synchronized void stop() {
		if (server != null) {
			server.stop(0);
			server = null;
		}
	}

	private static void notify(String group, String content, NotificationType type) {
		NotificationGroupManager.getInstance().getNotificationGroup(group).createNotification("IDE Pilot", content, type).notify(null);
	}

	/** Announces a client's MCP handshake, once per client name and version until the IDE restarts. */
	private static void connected(JsonObject params) {
		String client = clientName(params);
		LOG.info("IDE Pilot MCP client connected: " + client);
		if (announced.add(client)) {
			notify(INFO_GROUP, client + " connected", NotificationType.INFORMATION);
		}
	}

	/** The name and version of an initialize request's clientInfo; fields that are not strings count as missing. */
	static String clientName(JsonObject params) {
		JsonObject info = params.has("clientInfo") && params.get("clientInfo").isJsonObject() ? params.getAsJsonObject("clientInfo") : new JsonObject();
		String name = text(info, "name");
		String version = text(info, "version");
		return (name != null ? name : "An unnamed client") + (version != null ? " " + version : "");
	}

	private static String text(JsonObject object, String key) {
		return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
	}

	/**
	 * Warns of a request with a wrong or missing token: at once, then at most every few minutes with the count since;
	 * those within the quiet interval are reported at its end.
	 */
	private static void rejected() {
		String content;
		synchronized (PilotServer.class) {
			rejected++;
			long now = System.currentTimeMillis();
			long quiet = rejectedWarnedAt == 0 ? 0 : rejectedWarnedAt + REJECTED_WARNING_INTERVAL_MS - now;
			if (quiet > 0) {
				if (!rejectedFlushScheduled) {
					rejectedFlushScheduled = true;
					AppExecutorUtil.getAppScheduledExecutorService().schedule(PilotServer::flushRejected, quiet, TimeUnit.MILLISECONDS);
				}
				return;
			}
			content = takeRejected(now);
		}
		warnRejected(content);
	}

	/** Reports the rejections counted during the quiet interval, if any. */
	private static void flushRejected() {
		String content;
		synchronized (PilotServer.class) {
			rejectedFlushScheduled = false;
			if (rejected == 0) {
				return;
			}
			content = takeRejected(System.currentTimeMillis());
		}
		warnRejected(content);
	}

	/** The warning for the rejections counted, resetting the count. Call synchronized on PilotServer. */
	private static String takeRejected(long now) {
		String content = (rejected == 1 ? "A request" : rejected + " requests") + " with a wrong or missing token rejected: a local process tries to use the MCP server";
		rejected = 0;
		rejectedWarnedAt = now;
		return content;
	}

	private static void warnRejected(String content) {
		LOG.warn("IDE Pilot " + content);
		notify(SECURITY_GROUP, content, NotificationType.WARNING);
	}

	/** The server's URL while it runs, else null. */
	static synchronized String runningUrl() {
		return server == null ? null : url(server.getAddress().getPort());
	}

	static String url(int port) {
		return "http://127.0.0.1:" + port + "/mcp";
	}

	/** Whether {@code header} is "Bearer <token>", compared in constant time. */
	static boolean authorized(String header, String token) {
		String expected = "Bearer " + token;
		return header != null && token != null && MessageDigest.isEqual(header.strip().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
	}

	private static void handle(HttpExchange exchange) throws IOException {
		try (exchange) {
			if (!authorized(exchange.getRequestHeaders().getFirst("Authorization"), token)) {
				rejected();
				exchange.sendResponseHeaders(401, -1);
				return;
			}
			if (!"POST".equals(exchange.getRequestMethod())) {
				exchange.sendResponseHeaders(405, -1);
				return;
			}
			JsonElement parsed;
			try {
				parsed = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			} catch (JsonParseException e) {
				respond(exchange, error(JsonNull.INSTANCE, -32700, "Parse error: " + e.getMessage()));
				return;
			}
			if (!parsed.isJsonObject()) {
				respond(exchange, error(JsonNull.INSTANCE, -32600, parsed.isJsonArray() ? "Batch requests are not supported" : "Invalid request"));
				return;
			}
			JsonObject request = parsed.getAsJsonObject();
			if (!request.has("id")) {
				exchange.sendResponseHeaders(202, -1); // a notification
				return;
			}
			JsonObject response;
			try {
				response = envelope(request.get("id"));
				String method = request.get("method").getAsString();
				JsonObject params = request.has("params") ? request.getAsJsonObject("params") : new JsonObject();
				response.add("result", dispatch(method, params));
				if (method.equals("initialize")) {
					connected(params);
				}
			} catch (Exception e) {
				LOG.warn("IDE Pilot request failed", e);
				response = error(request.get("id"), -32603, String.valueOf(e.getMessage()));
			}
			respond(exchange, response);
		}
	}

	private static JsonObject envelope(JsonElement id) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		return response;
	}

	private static JsonObject error(JsonElement id, int code, String message) {
		JsonObject response = envelope(id);
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		response.add("error", error);
		return response;
	}

	private static void respond(HttpExchange exchange, JsonObject response) throws IOException {
		byte[] body = GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(200, body.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(body);
		}
	}

	static JsonElement dispatch(String method, JsonObject params) {
		return switch (method) {
			case "initialize" -> {
				JsonObject result = new JsonObject();
				result.addProperty("protocolVersion", params.has("protocolVersion") ? params.get("protocolVersion").getAsString() : PROTOCOL_VERSION);
				JsonObject capabilities = new JsonObject();
				capabilities.add("tools", new JsonObject());
				result.add("capabilities", capabilities);
				JsonObject info = new JsonObject();
				info.addProperty("name", "ide-pilot");
				info.addProperty("version", "0.1");
				result.add("serverInfo", info);
				yield result;
			}
			case "ping" -> new JsonObject();
			case "tools/list" -> PilotTools.list();
			case "tools/call" -> PilotTools.call(params.get("name").getAsString(),
					params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject());
			default -> throw new IllegalArgumentException("Unknown method " + method);
		};
	}
}
