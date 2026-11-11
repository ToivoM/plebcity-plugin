package com.plebcity;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.Value;
import net.runelite.client.util.Text;

/**
 * Parses the clan coffer broadcast that the game sends to everyone in clan chat, e.g.
 * "ToivoM has deposited 1,000,000 coins into the coffer."
 */
final class CofferMessageParser
{
	private static final Pattern DEPOSIT = Pattern.compile("^(.+) has deposited ([\\d,]+) coins? into the coffer\\.$");
	private static final Pattern WITHDRAW = Pattern.compile("^(.+) has withdrawn ([\\d,]+) coins? from the coffer\\.$");

	enum Type
	{
		DEPOSIT("deposit"),
		WITHDRAW("withdraw");

		final String apiName;

		Type(String apiName)
		{
			this.apiName = apiName;
		}
	}

	@Value
	static class CofferEvent
	{
		Type type;
		String player;
		long amount;
	}

	private CofferMessageParser()
	{
	}

	/** Removes tags and turns non-breaking spaces (common in RSNs) into normal spaces. */
	static String clean(String rawMessage)
	{
		return Text.removeTags(rawMessage).replace(' ', ' ').trim();
	}

	/**
	 * @return the parsed event, or null if the message is not a coffer deposit/withdrawal
	 */
	static CofferEvent parse(String rawMessage)
	{
		if (rawMessage == null)
		{
			return null;
		}
		String message = clean(rawMessage);
		CofferEvent event = match(DEPOSIT, Type.DEPOSIT, message);
		return event != null ? event : match(WITHDRAW, Type.WITHDRAW, message);
	}

	private static CofferEvent match(Pattern pattern, Type type, String message)
	{
		Matcher m = pattern.matcher(message);
		if (!m.matches())
		{
			return null;
		}
		String digits = m.group(2).replace(",", "");
		if (digits.isEmpty())
		{
			return null;
		}
		try
		{
			long amount = Long.parseLong(digits);
			return amount > 0 ? new CofferEvent(type, m.group(1).trim(), amount) : null;
		}
		catch (NumberFormatException e)
		{
			return null; // overflow
		}
	}
}
