package com.plebcity;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.Value;

/**
 * Decides where broadcasts are shown. Fresh broadcasts (under 5 minutes old) go to the chatbox and the
 * overlay. Older ones, such as the backlog received at login, go to the chatbox only with their age,
 * and at most 5 of them are listed.
 */
final class BroadcastDelivery
{
	static final Duration OVERLAY_MAX_AGE = Duration.ofMinutes(5);
	static final int MAX_OLD_IN_CHAT = 5;

	@Value
	static class Item
	{
		long id;
		String text;
		/** Null if unknown (e.g. an older server). */
		Instant createdAt;
	}

	@Value
	static class Plan
	{
		/** Chat lines in display order, without the "[Plebcity] " prefix. */
		List<String> chatLines;
		/** Texts for the overlay (and notifications), oldest first. */
		List<String> overlayTexts;
	}

	private BroadcastDelivery()
	{
	}

	/**
	 * @param now the current time, preferably from the server so a wrong local clock doesn't matter
	 * @param unknownAgeIsOld how to treat broadcasts without a created_at: true for the first request
	 *                        after linking (likely backlog), false otherwise
	 */
	static Plan plan(List<Item> items, Instant now, boolean unknownAgeIsOld)
	{
		List<Item> sorted = new ArrayList<>(items);
		sorted.sort(Comparator.comparingLong(Item::getId));

		List<Item> fresh = new ArrayList<>();
		List<Item> old = new ArrayList<>();
		for (Item item : sorted)
		{
			if (isFresh(item, now, unknownAgeIsOld))
			{
				fresh.add(item);
			}
			else
			{
				old.add(item);
			}
		}

		List<String> chat = new ArrayList<>();
		int skipped = Math.max(0, old.size() - MAX_OLD_IN_CHAT);
		if (skipped > 0)
		{
			chat.add("+ " + skipped + " older broadcast" + (skipped == 1 ? "" : "s"));
		}
		for (Item item : old.subList(skipped, old.size()))
		{
			chat.add(item.getCreatedAt() == null
				? item.getText()
				: "(" + ageText(Duration.between(item.getCreatedAt(), now)) + ") " + item.getText());
		}
		List<String> overlay = new ArrayList<>();
		for (Item item : fresh)
		{
			chat.add(item.getText());
			overlay.add(item.getText());
		}
		return new Plan(chat, overlay);
	}

	static boolean isFresh(Item item, Instant now, boolean unknownAgeIsOld)
	{
		if (item.getCreatedAt() == null)
		{
			return !unknownAgeIsOld;
		}
		return Duration.between(item.getCreatedAt(), now).compareTo(OVERLAY_MAX_AGE) < 0;
	}

	/** Rounded down: "3 min ago", "3 h ago", "1 day ago", "2 days ago". */
	static String ageText(Duration age)
	{
		long minutes = Math.max(0, age.getSeconds()) / 60;
		if (minutes < 60)
		{
			return Math.max(1, minutes) + " min ago";
		}
		long hours = minutes / 60;
		if (hours < 24)
		{
			return hours + " h ago";
		}
		long days = hours / 24;
		return days == 1 ? "1 day ago" : days + " days ago";
	}
}
