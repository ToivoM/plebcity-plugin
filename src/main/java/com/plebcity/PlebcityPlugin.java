package com.plebcity;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.google.common.collect.ImmutableSet;
import com.google.inject.Provides;
import java.awt.Color;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.runelite.client.Notifier;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
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
	private static final long EVENTS_REFRESH_SECONDS = 5 * 60;
	static final Color PLEBCITY_COLOR = new Color(0x00, 0x8C, 0xFF);

	// Stored per RuneScape profile via ConfigManager; intentionally not @ConfigItems, so they are hidden.
	private static final String KEY_TOKEN = "token";
	private static final String KEY_LAST_ANNOUNCEMENT_ID = "lastAnnouncementId";

	// Debug log: clock-like vars that change constantly and are never interesting.
	private static final Set<Integer> IGNORED_VARPS = ImmutableSet.of(1042, 3077, 3078, 3079);
	private static final Set<Integer> IGNORED_VARBITS = ImmutableSet.of(8354, 12391, 12392);
	/** Debug log: don't log var changes this soon after logging in (the client receives hundreds then). */
	private static final long VAR_LOG_LOGIN_GRACE_MILLIS = 5_000;

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

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private Notifier notifier;

	@Inject
	private BroadcastOverlay broadcastOverlay;

	@Inject
	private ClientToolbar clientToolbar;

	private ScheduledFuture<?> pollFuture;
	private ScheduledFuture<?> eventsFuture;
	private EventsPanel eventsPanel;
	private NavigationButton eventsButton;
	private volatile boolean eventsInFlight;

	/** Local player's name, updated on the client thread every tick; null when logged out. */
	private volatile String rsn;
	/** Set when we should (re)check whether a link request needs to be sent; consumed on the client thread. */
	private volatile boolean linkCheckPending;
	/** Last code we tried, so a bad code isn't retried over and over. */
	private volatile String lastAttemptedCode;
	private volatile boolean linkInFlight;
	private volatile boolean pollInFlight;
	/** Client thread only: whether the next LOGGED_IN state is a fresh login (or world hop). */
	private boolean loggingIn;
	/** Client thread only: when we last finished logging in, used to mute the burst of var changes at login. */
	private long loggedInAtMillis;

	@Override
	protected void startUp()
	{
		linkCheckPending = true;
		overlayManager.add(broadcastOverlay);
		mouseManager.registerMouseListener(broadcastOverlay.mouseListener);
		pollFuture = executor.scheduleWithFixedDelay(this::pollAnnouncements, 10, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);

		eventsPanel = new EventsPanel();
		eventsPanel.setOnOpen(() -> executor.execute(this::fetchEvents));
		eventsButton = NavigationButton.builder()
			.tooltip("Plebcity events")
			.icon(ImageUtil.loadImageResource(PlebcityPlugin.class, "events_icon.png"))
			.priority(8)
			.panel(eventsPanel)
			.build();
		if (config.showEventsPanel())
		{
			clientToolbar.addNavigation(eventsButton);
		}
		eventsFuture = executor.scheduleWithFixedDelay(this::fetchEvents, 15, EVENTS_REFRESH_SECONDS, TimeUnit.SECONDS);
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
		if (eventsFuture != null)
		{
			eventsFuture.cancel(true);
			eventsFuture = null;
		}
		clientToolbar.removeNavigation(eventsButton);
		eventsPanel.shutdown();
		mouseManager.unregisterMouseListener(broadcastOverlay.mouseListener);
		overlayManager.remove(broadcastOverlay);
		broadcastOverlay.clear();
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
		if (state == GameState.LOGGING_IN || state == GameState.HOPPING)
		{
			loggingIn = true;
		}
		else if (state == GameState.LOGGED_IN)
		{
			linkCheckPending = true;
			if (loggingIn)
			{
				loggingIn = false;
				loggedInAtMillis = System.currentTimeMillis();
				// Give RuneLite a moment to switch to this account's profile, where the token is stored.
				executor.schedule(this::fetchEvents, 5, TimeUnit.SECONDS);
			}
		}
		else if (state == GameState.LOGIN_SCREEN)
		{
			rsn = null;
			eventsPanel.showStatus(EventsPanel.Status.NOT_LOGGED_IN);
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
		if ("showEventsPanel".equals(event.getKey()))
		{
			if (config.showEventsPanel())
			{
				clientToolbar.addNavigation(eventsButton);
				executor.execute(this::fetchEvents);
			}
			else
			{
				clientToolbar.removeNavigation(eventsButton);
			}
		}
		if ("showBroadcastOverlay".equals(event.getKey()) && !config.showBroadcastOverlay())
		{
			broadcastOverlay.clear();
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
		final boolean debug = config.debugLogChat();
		if (debug)
		{
			// Opt-in diagnostics, written to the local client log only.
			log.info("[Plebcity debug] chat type={} name={} message={}",
				event.getType(), event.getName(), event.getMessage());
		}

		if (event.getType() != ChatMessageType.CLAN_MESSAGE)
		{
			return;
		}

		// Only the parsed coffer event (type, player, amount) is ever sent to the API, never the raw message.
		CofferMessageParser.CofferEvent coffer = CofferMessageParser.parse(event.getMessage());
		if (coffer != null)
		{
			if (config.reportCoffer())
			{
				sendCofferEvent(coffer);
			}
		}
		else if (debug && event.getMessage() != null
			&& CofferMessageParser.clean(event.getMessage()).toLowerCase().contains("coffer"))
		{
			// In case Jagex changes the message format.
			log.info("[Plebcity debug] unrecognised coffer message: {}", event.getMessage());
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.debugLogChat()
			|| System.currentTimeMillis() - loggedInAtMillis < VAR_LOG_LOGIN_GRACE_MILLIS
			|| IGNORED_VARPS.contains(event.getVarpId())
			|| IGNORED_VARBITS.contains(event.getVarbitId()))
		{
			return;
		}
		log.info("[Plebcity debug] var varbitId={} varpId={} value={}",
			event.getVarbitId(), event.getVarpId(), event.getValue());
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
			executor.execute(this::fetchEvents);
			log.debug("Plebcity linked as {}", name);
		}, status ->
		{
			if (status == -1)
			{
				// Network problem: allow another try with the same code later.
				lastAttemptedCode = null;
				showLocalMessage("Linking failed: could not reach the Plebcity API. Check the API URL.");
			}
			else if (status == 400 || status == 404 || status == 410)
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
				// The first poll after linking returns the latest few broadcasts as a backlog:
				// put those in the chatbox only, not in the overlay or notifications.
				final boolean backlog = since == 0;
				for (Announcement a : list)
				{
					if (a == null || a.id <= since || a.text == null)
					{
						continue;
					}
					showBroadcast(a.text, backlog);
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
					handleUnauthorized();
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
	// Clan events (sidebar panel)
	// ------------------------------------------------------------------

	/** Loads events for the panel. Runs on the shared executor, never on the client thread or EDT. */
	private void fetchEvents()
	{
		try
		{
			if (!config.showEventsPanel() || eventsInFlight)
			{
				return;
			}
			if (configManager.getRSProfileKey() == null)
			{
				eventsPanel.showStatus(EventsPanel.Status.NOT_LOGGED_IN);
				return;
			}
			final String token = getToken();
			if (token == null)
			{
				eventsPanel.showStatus(EventsPanel.Status.NOT_LINKED);
				return;
			}
			HttpUrl url = apiUrl("api/events");
			if (url == null)
			{
				eventsPanel.showStatus(EventsPanel.Status.ERROR);
				return;
			}
			Request request = new Request.Builder()
				.url(url)
				.header("Authorization", "Bearer " + token)
				.get()
				.build();

			eventsInFlight = true;
			enqueue(request, "events", body ->
			{
				EventsResponse response = gson.fromJson(body, EventsResponse.class);
				List<ClanEvent> events = new ArrayList<>();
				if (response != null)
				{
					addEvents(events, response.ongoing);
					addEvents(events, response.upcoming);
					addEvents(events, response.past);
				}
				eventsPanel.showEvents(events);
			}, status ->
			{
				if (status == 401)
				{
					handleUnauthorized();
				}
				else
				{
					eventsPanel.showStatus(EventsPanel.Status.ERROR);
				}
			}, () -> eventsInFlight = false);
		}
		catch (Exception e)
		{
			eventsInFlight = false;
			log.warn("Plebcity events fetch failed", e);
		}
	}

	private static void addEvents(List<ClanEvent> into, List<EventDto> dtos)
	{
		if (dtos == null)
		{
			return;
		}
		for (EventDto d : dtos)
		{
			if (d == null || d.id == null || d.name == null || d.startTime == null || d.endTime == null)
			{
				continue;
			}
			try
			{
				into.add(new ClanEvent(d.id, d.name, d.description, d.location,
					Instant.parse(d.startTime), Instant.parse(d.endTime), d.status));
			}
			catch (DateTimeParseException e)
			{
				log.debug("Plebcity skipping event {} with bad time", d.id);
			}
		}
	}

	/** The API rejected our token (revoked, or relinked elsewhere): forget it so a new code can be used. */
	private void handleUnauthorized()
	{
		if (getToken() == null)
		{
			return; // already handled by another request
		}
		log.warn("Plebcity token rejected by API; clearing it");
		configManager.unsetRSProfileConfiguration(PlebcityConfig.GROUP, KEY_TOKEN);
		showLocalMessage("Your Plebcity link is no longer valid. Enter a new link code in the plugin settings.");
		eventsPanel.showStatus(EventsPanel.Status.NOT_LINKED);
	}

	// ------------------------------------------------------------------
	// Events to the API
	// ------------------------------------------------------------------

	/**
	 * Reports a coffer deposit/withdrawal seen in clan chat. The reporter is identified by the token;
	 * the player who deposited may be someone else in the clan.
	 */
	private void sendCofferEvent(CofferMessageParser.CofferEvent event)
	{
		final String token = getToken();
		if (token == null)
		{
			log.debug("Plebcity not linked; not sending coffer event");
			return;
		}
		HttpUrl url = apiUrl("api/events/coffer");
		if (url == null)
		{
			return;
		}
		Map<String, Object> body = new HashMap<>();
		body.put("type", event.getType().apiName);
		body.put("player", event.getPlayer());
		body.put("amount", event.getAmount());
		body.put("clientTime", System.currentTimeMillis());

		Request request = new Request.Builder()
			.url(url)
			.header("Authorization", "Bearer " + token)
			.post(RequestBody.create(JSON, gson.toJson(body)))
			.build();
		enqueue(request, "coffer", response -> log.debug("Plebcity coffer {} by {} of {} sent",
			event.getType().apiName, event.getPlayer(), event.getAmount()), null, null);
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

	/** Shows a broadcast in the chatbox and, unless it is old backlog, in the overlay and as a notification. */
	private void showBroadcast(String text, boolean backlog)
	{
		showLocalMessage(text);
		if (backlog)
		{
			return;
		}
		if (config.showBroadcastOverlay())
		{
			broadcastOverlay.add(text);
		}
		if (config.notifyOnBroadcast())
		{
			notifier.notify("[Plebcity] " + text);
		}
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
	 * @param onHttpError optional, called with the status code for non-2xx responses, or -1 if the request failed
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
				try
				{
					if (onHttpError != null)
					{
						onHttpError.accept(-1);
					}
				}
				catch (Exception ex)
				{
					log.warn("Plebcity {} error handling failed", what, ex);
				}
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

	private static class EventsResponse
	{
		List<EventDto> ongoing;
		List<EventDto> upcoming;
		List<EventDto> past;
	}

	private static class EventDto
	{
		String id;
		String name;
		String description;
		String location;
		@SerializedName("start_time")
		String startTime;
		@SerializedName("end_time")
		String endTime;
		String status;
	}
}
