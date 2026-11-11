package com.plebcity;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

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
		return "http://localhost:8000";
	}

	@ConfigItem(
		keyName = "linkCode",
		name = "Link code",
		description = "One-time code from a clan admin (Discord /linkita). Cleared automatically after a successful link.",
		warning = THIRD_PARTY_WARNING,
		position = 2
	)
	default String linkCode()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showAnnouncements",
		name = "Show announcements",
		description = "Fetch clan announcements from the Plebcity API every 60 seconds and show them in your chatbox",
		warning = THIRD_PARTY_WARNING,
		position = 3
	)
	default boolean showAnnouncements()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugLogChat",
		name = "Debug: log chat & varbits",
		description = "Write every chat message and varbit/varp change to the client log (for development)",
		position = 4
	)
	default boolean debugLogChat()
	{
		return false;
	}
}
