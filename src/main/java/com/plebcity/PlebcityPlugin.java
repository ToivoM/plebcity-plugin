package com.plebcity;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.inject.Provides;
import java.awt.Color;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.ColorUtil;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Plebcity clan plugin.
 *
 * Only talks to the Plebcity API over HTTP and shows things locally in the chatbox.
 * It never sends anything to the game server on the player's behalf.
 */
@Slf4j
@PluginDescriptor(
	name = "Plebcity",
	description = "Plebcity clan tools: clan announcements in chat and Discord bot linking",
	tags = {"clan", "discord", "plebcity"}
)
public class PlebcityPlugin extends Plugin
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final long POLL_INTERVAL_SECONDS = 60;
	private static final Color PLEBCITY_COLOR = new Color(0x00, 0x8C, 0xFF);

	// Stored per RuneScape profile via ConfigManager; intentionally not @ConfigItems, so they are hidden.
	private static final String KEY_TOKEN = "token";
	private static final String KEY_LAST_ANNOUNCEMENT_ID = "lastAnnouncementId";

	@Inject
	private Client client;

	@Inject
	private PlebcityConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private Gson gson;

	@Inject
	private ScheduledExecutorService executor;

	private ScheduledFuture<?> pollFuture;

	/** Local player's name, updated on the client thread every tick; null when logged out. */
	private volatile String rsn;
	/** Set when we should (re)check whether a link request needs to be sent; consumed on the client thread. */
	private volatile boolean linkCheckPending;
	/** Last code we tried, so a bad code isn't retried over and over. */
	private volatile String lastAttemptedCode;
	private volatile boolean linkInFlight;
	private volatile boolean pollInFlight;

	@Override
	protected void startUp()
	{
		linkCheckPending = true;
		pollFuture = executor.scheduleWithFixedDelay(this::pollAnnouncements, 10, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
		log.debug("Plebcity started");
	}

	@Override
	protected void shutDown()
	{
		if (pollFuture != null)
		{
			pollFuture.cancel(true);
			pollFuture = null;
		}
		rsn = null;
		lastAttemptedCode = null;
		log.debug("Plebcity stopped");
	}

	@Provides
	PlebcityConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PlebcityConfig.class);
	}

	// ------------------------------------------------------------------
	// Events
	// ------------------------------------------------------------------

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGGED_IN)
		{
			linkCheckPending = true;
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			rsn = null;
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		Player local = client.getLocalPlayer();
		if (local == null || local.getName() == null)
		{
			return;
		}
		rsn = local.getName();

		if (linkCheckPending)
		{
			linkCheckPending = false;
			tryLink();
		}
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!PlebcityConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}
		if ("linkCode".equals(event.getKey()) || "apiUrl".equals(event.getKey()))
		{
			// Picked up on the next game tick, where we know the RSN.
			linkCheckPending = true;
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (config.debugLogChat())
		{
			// Opt-in diagnostics, used to find out how a clan coffer deposit shows up in game.
			log.info("[Plebcity debug] chat type={} name={} message={}",
				event.getType(), event.getName(), event.getMessage());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (config.debugLogChat())
		{
			log.info("[Plebcity debug] var varbitId={} varpId={} value={}",
				event.getVarbitId(), event.getVarpId(), event.getValue());
		}
	}

	// ------------------------------------------------------------------
	// Linking
	// ------------------------------------------------------------------

	/** Called on the client thread. */
	private void tryLink()
	{
		final String code = config.linkCode() == null ? "" : config.linkCode().trim();
		final String name = rsn;
		if (code.isEmpty() || name == null || linkInFlight || getToken() != null || code.equals(lastAttemptedCode))
		{
			return;
		}

		HttpUrl url = apiUrl("api/link");
		if (url == null)
		{
			return;
		}

		Map<String, String> body = new HashMap<>();
		body.put("code", code);
		body.put("rsn", name);

		lastAttemptedCode = code;
		linkInFlight = true;
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();

		enqueue(request, "link", responseBody ->
		{
			LinkResponse resp = gson.fromJson(responseBody, LinkResponse.class);
			if (resp == null || resp.token == null || resp.token.isEmpty())
			{
				log.warn("Plebcity link response did not contain a token");
				showLocalMessage("Linking failed: unexpected response from the server.");
				return;
			}
			configManager.setRSProfileConfiguration(PlebcityConfig.GROUP, KEY_TOKEN, resp.token);
			configManager.unsetConfiguration(PlebcityConfig.GROUP, "linkCode");
			showLocalMessage("Account linked to the Plebcity clan bot.");
			log.debug("Plebcity linked as {}", name);
		}, status ->
		{
			if (status == 400 || status == 404 || status == 410)
			{
				showLocalMessage("Linking failed: the code is invalid, used or expired. Ask an admin for a new one.");
			}
			else
			{
				showLocalMessage("Linking failed (HTTP " + status + "). Check the API URL.");
			}
		}, () -> linkInFlight = false);
	}

	// ------------------------------------------------------------------
	// Announcements
	// ------------------------------------------------------------------

	/** Runs on the shared executor, never on the client thread. */
	private void pollAnnouncements()
	{
		try
		{
			if (!config.showAnnouncements() || rsn == null || pollInFlight)
			{
				return;
			}
			final String token = getToken();
			if (token == null)
			{
				return;
			}
			HttpUrl base = apiUrl("api/announcements");
			if (base == null)
			{
				return;
			}
			final long since = getLastAnnouncementId();
			HttpUrl url = base.newBuilder().addQueryParameter("since", Long.toString(since)).build();
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.get()
				.build();

			pollInFlight = true;
			enqueue(request, "announcements", responseBody ->
			{
				Announcement[] list = gson.fromJson(responseBody, Announcement[].class);
				if (list == null)
				{
					return;
				}
				long maxId = since;
				for (Announcement a : list)
				{
					if (a == null || a.id <= since || a.text == null)
					{
						continue;
					}
					showLocalMessage(a.text);
					maxId = Math.max(maxId, a.id);
				}
				if (maxId > since)
				{
					configManager.setRSProfileConfiguration(PlebcityConfig.GROUP, KEY_LAST_ANNOUNCEMENT_ID, Long.toString(maxId));
				}
			}, status ->
			{
				if (status == 401)
				{
					// Token revoked or replaced (e.g. relinked elsewhere). Forget it so a new code can be used.
					log.warn("Plebcity token rejected by API; clearing it");
					configManager.unsetRSProfileConfiguration(PlebcityConfig.GROUP, KEY_TOKEN);
					showLocalMessage("Your Plebcity link is no longer valid. Enter a new link code in the plugin settings.");
				}
			}, () -> pollInFlight = false);
		}
		catch (Exception e)
		{
			// Never let an exception kill the scheduled task.
			pollInFlight = false;
			log.warn("Plebcity announcement poll failed", e);
		}
	}

	// ------------------------------------------------------------------
	// Events to the API
	// ------------------------------------------------------------------

	/**
	 * Reports a clan coffer deposit to the Plebcity API.
	 * Not called anywhere yet: detection of deposits will be added once we know how they appear in game.
	 */
	public void sendCofferDeposit(long amount)
	{
		final String token = getToken();
		if (token == null)
		{
			log.debug("Plebcity not linked; not sending coffer deposit");
			return;
		}
		HttpUrl url = apiUrl("api/events/coffer");
		if (url == null)
		{
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(Collections.singletonMap("amount", amount))))
			.build();
		enqueue(request, "coffer", body -> log.debug("Plebcity coffer deposit of {} sent", amount), null, null);
	}

	// ------------------------------------------------------------------
	// Helpers
	// ------------------------------------------------------------------

	private String getToken()
	{
		String token = configManager.getRSProfileConfiguration(PlebcityConfig.GROUP, KEY_TOKEN);
		return token == null || token.isEmpty() ? null : token;
	}

	private long getLastAnnouncementId()
	{
		String value = configManager.getRSProfileConfiguration(PlebcityConfig.GROUP, KEY_LAST_ANNOUNCEMENT_ID);
		if (value == null)
		{
			return 0;
		}
		try
		{
			return Math.max(0, Long.parseLong(value));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private HttpUrl apiUrl(String path)
	{
		String base = config.apiUrl() == null ? "" : config.apiUrl().trim();
		HttpUrl url = HttpUrl.parse(base);
		if (url == null)
		{
			log.warn("Plebcity API URL is invalid: '{}'", base);
			return null;
		}
		return url.newBuilder().addPathSegments(path).build();
	}

	/** Shows a message in the local chatbox only. Nothing is sent to the game server. */
	private void showLocalMessage(String text)
	{
		String safe = text.replace("<", "<lt>").replace(">", "<gt>");
		chatMessageManager.queue(QueuedMessage.builder()
			.type(ChatMessageType.GAMEMESSAGE)
			.runeLiteFormattedMessage(ColorUtil.wrapWithColorTag("[Plebcity] " + safe, PLEBCITY_COLOR))
			.build());
	}

	/**
	 * Runs the request on OkHttp's thread pool. All failures are logged; nothing is thrown.
	 *
	 * @param onSuccess called with the response body for 2xx responses
	 * @param onHttpError optional, called with the status code for non-2xx responses
	 * @param onDone optional, always called once the request has finished
	 */
	private void enqueue(Request request, String what, Consumer<String> onSuccess, Consumer<Integer> onHttpError, Runnable onDone)
	{
		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Plebcity {} request failed: {}", what, e.getMessage());
				finish();
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					ResponseBody body = r.body();
					String content = body == null ? "" : body.string();
					if (!r.isSuccessful())
					{
						log.warn("Plebcity {} request returned HTTP {}", what, r.code());
						if (onHttpError != null)
						{
							onHttpError.accept(r.code());
						}
						return;
					}
					onSuccess.accept(content);
				}
				catch (IOException | JsonParseException | IllegalStateException e)
				{
					log.warn("Plebcity {} response handling failed", what, e);
				}
				catch (Exception e)
				{
					log.warn("Plebcity {} unexpected error", what, e);
				}
				finally
				{
					finish();
				}
			}

			private void finish()
			{
				if (onDone != null)
				{
					onDone.run();
				}
			}
		});
	}

	private static class LinkResponse
	{
		String token;
	}

	private static class Announcement
	{
		long id;
		String text;
	}
}
