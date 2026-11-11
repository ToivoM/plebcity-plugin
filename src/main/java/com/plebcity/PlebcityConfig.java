package com.plebcity;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(PlebcityConfig.GROUP)
public interface PlebcityConfig extends Config
{
	String GROUP = "plebcity";

	String THIRD_PARTY_WARNING = "This feature submits your IP address to a 3rd-party server not controlled or verified by RuneLite developers";

	/** Appended to the description of every setting that makes the plugin contact the Plebcity server. */
	String IP_NOTE = " Your IP address is visible to the Plebcity server (a third-party server, not run by RuneLite).";

	@ConfigSection(
		name = "Advanced",
		description = "Settings you normally don't need to change",
		position = 100,
		closedByDefault = true
	)
	String ADVANCED_SECTION = "advanced";

	@ConfigItem(
		keyName = "linkCode",
		name = "Link code",
		description = "One-time code from a Plebcity clan admin (Discord /link). Nothing is sent anywhere until you enter one."
			+ " When you enter it, your RuneScape name is sent to the Plebcity server to link your account."
			+ " Cleared automatically after a successful link." + IP_NOTE,
		warning = THIRD_PARTY_WARNING,
		position = 1
	)
	default String linkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showAnnouncements",
		name = "Receive broadcasts",
		description = "Receive clan broadcasts (Discord /broadcast) from the Plebcity server while you are logged in"
			+ " and linked, and show them in your chatbox." + IP_NOTE,
		warning = THIRD_PARTY_WARNING,
		position = 2
	)
	default boolean showAnnouncements()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBroadcastOverlay",
		name = "Broadcast overlay",
		description = "Also show new broadcasts in a box above the chatbox",
		position = 3
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
		position = 4
	)
	default int broadcastDurationSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "notifyOnBroadcast",
		name = "Notify on broadcast",
		description = "Send a RuneLite notification when a new broadcast arrives",
		position = 5
	)
	default boolean notifyOnBroadcast()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showEventsPanel",
		name = "Events panel",
		description = "Show the Plebcity events button in the sidebar. Events are loaded from the Plebcity server"
			+ " only when your account is linked." + IP_NOTE,
		position = 6
	)
	default boolean showEventsPanel()
	{
		return true;
	}

	@ConfigItem(
		keyName = "reportCoffer",
		name = "Report clan coffer events",
		description = "Send clan coffer deposits and withdrawals from the clan's own broadcast in clan chat"
			+ " (player name, amount, deposit/withdraw) to the Plebcity server." + IP_NOTE,
		warning = THIRD_PARTY_WARNING,
		position = 7
	)
	default boolean reportCoffer()
	{
		return false;
	}

	@ConfigItem(
		keyName = "apiUrl",
		name = "API URL",
		description = "Address of the Plebcity server. Change only if a Plebcity clan admin asks you to." + IP_NOTE,
		section = ADVANCED_SECTION,
		position = 101
	)
	default String apiUrl()
	{
		return "https://api.osrsplebcity.com";
	}
}
