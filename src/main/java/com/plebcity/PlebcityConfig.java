package com.plebcity;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(PlebcityConfig.GROUP)
public interface PlebcityConfig extends Config
{
	String GROUP = "plebcity";

	String THIRD_PARTY_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "Address of the Plebcity clan bot API",
		position = 1
	)
	default String apiUrl()
	{
		return "https://api.osrsplebcity.com";
	}

	@ConfigItem(
		keyName = "linkCode",
		name = "Link code",
		description = "One-time code from a clan admin (Discord /link). Cleared automatically after a successful link.",
		warning = THIRD_PARTY_WARNING,
		position = 2
	)
	default String linkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showAnnouncements",
		name = "Receive broadcasts",
		description = "Receive clan broadcasts (Discord /broadcast) from the Plebcity API as they are sent and show them in your chatbox",
		warning = THIRD_PARTY_WARNING,
		position = 3
	)
	default boolean showAnnouncements()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBroadcastOverlay",
		name = "Broadcast overlay",
		description = "Also show new broadcasts in a box above the chatbox",
		position = 4
	)
	default boolean showBroadcastOverlay()
	{
		return true;
	}

	@Range(min = 5, max = 300)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "broadcastDurationSeconds",
		name = "Broadcast duration",
		description = "How long a broadcast stays above the chatbox",
		position = 5
	)
	default int broadcastDurationSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "notifyOnBroadcast",
		name = "Notify on broadcast",
		description = "Send a RuneLite notification when a new broadcast arrives",
		position = 6
	)
	default boolean notifyOnBroadcast()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showEventsPanel",
		name = "Events panel",
		description = "Show the Plebcity events button in the sidebar. Events are loaded only when your account is linked.",
		position = 7
	)
	default boolean showEventsPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportCoffer",
		name = "Report clan coffer events",
		description = "Send clan coffer deposits and withdrawals announced in clan chat (player name, amount, type) to the Plebcity API",
		warning = THIRD_PARTY_WARNING,
		position = 8
	)
	default boolean reportCoffer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugLogChat",
		name = "Debug: log chat & varbits",
		description = "Write every chat message and varbit/varp change to the client log (for development)",
		position = 9
	)
	default boolean debugLogChat()
	{
		return false;
	}
}
