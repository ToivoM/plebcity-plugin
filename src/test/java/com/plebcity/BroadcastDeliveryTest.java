package com.plebcity;

import static org.junit.Assert.assertEquals;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class BroadcastDeliveryTest
{
	private static final Instant NOW = Instant.parse("2025-11-14T17:00:00Z");

	private static BroadcastDelivery.Item item(long id, String text, Duration age)
	{
		return new BroadcastDelivery.Item(id, text, age == null ? null : NOW.minus(age));
	}

	private static BroadcastDelivery.Plan plan(boolean unknownAgeIsOld, BroadcastDelivery.Item... items)
	{
		return BroadcastDelivery.plan(Arrays.asList(items), NOW, unknownAgeIsOld);
	}

	@Test
	public void ageText()
	{
		assertEquals("1 min ago", BroadcastDelivery.ageText(Duration.ofSeconds(20)));
		assertEquals("5 min ago", BroadcastDelivery.ageText(Duration.ofMinutes(5)));
		assertEquals("59 min ago", BroadcastDelivery.ageText(Duration.ofMinutes(59).plusSeconds(59)));
		assertEquals("1 h ago", BroadcastDelivery.ageText(Duration.ofMinutes(60)));
		assertEquals("3 h ago", BroadcastDelivery.ageText(Duration.ofMinutes(3 * 60 + 59)));
		assertEquals("23 h ago", BroadcastDelivery.ageText(Duration.ofHours(23)));
		assertEquals("1 day ago", BroadcastDelivery.ageText(Duration.ofHours(24)));
		assertEquals("2 days ago", BroadcastDelivery.ageText(Duration.ofHours(50)));
		assertEquals("1 min ago", BroadcastDelivery.ageText(Duration.ofSeconds(-30)));
	}

	@Test
	public void freshBroadcastGoesToChatAndOverlay()
	{
		BroadcastDelivery.Plan p = plan(false, item(1, "raid at 8", Duration.ofSeconds(2)));
		assertEquals(Collections.singletonList("raid at 8"), p.getChatLines());
		assertEquals(Collections.singletonList("raid at 8"), p.getOverlayTexts());
	}

	@Test
	public void fiveMinuteBoundary()
	{
		BroadcastDelivery.Plan p = plan(false,
			item(1, "almost old", Duration.ofMinutes(5).minusSeconds(1)),
			item(2, "old", Duration.ofMinutes(5)));
		assertEquals(Collections.singletonList("almost old"), p.getOverlayTexts());
		assertEquals(Arrays.asList("(5 min ago) old", "almost old"), p.getChatLines());
	}

	@Test
	public void oldBroadcastGoesToChatOnlyWithAge()
	{
		BroadcastDelivery.Plan p = plan(false, item(1, "message", Duration.ofHours(3)));
		assertEquals(Collections.singletonList("(3 h ago) message"), p.getChatLines());
		assertEquals(Collections.emptyList(), p.getOverlayTexts());
	}

	@Test
	public void mixedBacklogAndFresh()
	{
		BroadcastDelivery.Plan p = plan(false,
			item(3, "new", Duration.ofSeconds(1)),
			item(1, "yesterday", Duration.ofHours(20)),
			item(2, "earlier", Duration.ofMinutes(30)));
		assertEquals(Arrays.asList("(20 h ago) yesterday", "(30 min ago) earlier", "new"), p.getChatLines());
		assertEquals(Collections.singletonList("new"), p.getOverlayTexts());
	}

	@Test
	public void moreThanFiveOldShowsOnlyTheFiveNewest()
	{
		List<BroadcastDelivery.Item> items = new ArrayList<>();
		for (int i = 1; i <= 8; i++)
		{
			items.add(item(i, "old " + i, Duration.ofHours(10 - i)));
		}
		items.add(item(9, "fresh", Duration.ofMinutes(1)));
		BroadcastDelivery.Plan p = BroadcastDelivery.plan(items, NOW, false);
		assertEquals(Arrays.asList(
			"+ 3 older broadcasts",
			"(6 h ago) old 4",
			"(5 h ago) old 5",
			"(4 h ago) old 6",
			"(3 h ago) old 7",
			"(2 h ago) old 8",
			"fresh"), p.getChatLines());
		assertEquals(Collections.singletonList("fresh"), p.getOverlayTexts());
	}

	@Test
	public void exactlyFiveOldShowsAllWithoutSummary()
	{
		List<BroadcastDelivery.Item> items = new ArrayList<>();
		for (int i = 1; i <= 5; i++)
		{
			items.add(item(i, "old " + i, Duration.ofHours(1)));
		}
		assertEquals(5, BroadcastDelivery.plan(items, NOW, false).getChatLines().size());
	}

	@Test
	public void singularSummary()
	{
		List<BroadcastDelivery.Item> items = new ArrayList<>();
		for (int i = 1; i <= 6; i++)
		{
			items.add(item(i, "old " + i, Duration.ofHours(1)));
		}
		assertEquals("+ 1 older broadcast", BroadcastDelivery.plan(items, NOW, false).getChatLines().get(0));
	}

	@Test
	public void unknownAgeDependsOnWhetherItIsBacklog()
	{
		BroadcastDelivery.Item unknown = item(1, "no time", null);
		BroadcastDelivery.Plan live = plan(false, unknown);
		assertEquals(Collections.singletonList("no time"), live.getOverlayTexts());
		BroadcastDelivery.Plan backlog = plan(true, unknown);
		assertEquals(Collections.emptyList(), backlog.getOverlayTexts());
		assertEquals(Collections.singletonList("no time"), backlog.getChatLines());
	}
}
