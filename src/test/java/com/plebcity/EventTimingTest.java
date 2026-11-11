package com.plebcity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import org.junit.Test;

public class EventTimingTest
{
	// Friday 14.11.2025 17:00 UTC = 19:00 in Helsinki, 18:00 in Stockholm
	private static final Instant START = Instant.parse("2025-11-14T17:00:00Z");
	private static final Instant END = START.plus(Duration.ofHours(2));

	private static ClanEvent event(String status)
	{
		return new ClanEvent("1", "PvM night", "", null, START, END, status);
	}

	private static Instant at(Duration fromStart)
	{
		return START.plus(fromStart);
	}

	@Test
	public void stateFromTimes()
	{
		ClanEvent e = event("scheduled");
		assertEquals(EventTiming.State.UPCOMING, EventTiming.state(e, at(Duration.ofMinutes(-1))));
		assertEquals(EventTiming.State.LIVE, EventTiming.state(e, START));
		assertEquals(EventTiming.State.LIVE, EventTiming.state(e, END.minusSeconds(1)));
		assertEquals(EventTiming.State.PAST, EventTiming.state(e, END));
	}

	@Test
	public void stateFromDiscordStatus()
	{
		Instant before = at(Duration.ofHours(-5));
		Instant during = at(Duration.ofHours(1));
		assertEquals(EventTiming.State.LIVE, EventTiming.state(event("active"), before));
		assertEquals(EventTiming.State.PAST, EventTiming.state(event("completed"), during));
		assertEquals(EventTiming.State.HIDDEN, EventTiming.state(event("canceled"), during));
		assertEquals(EventTiming.State.HIDDEN, EventTiming.state(event("canceled"), before));
	}

	@Test
	public void soon()
	{
		ClanEvent e = event("scheduled");
		assertTrue(EventTiming.isSoon(e, at(Duration.ofMinutes(-59))));
		assertFalse(EventTiming.isSoon(e, at(Duration.ofMinutes(-60))));
		assertFalse(EventTiming.isSoon(e, START));
	}

	@Test
	public void relativeTexts()
	{
		ClanEvent e = event("scheduled");
		assertEquals("starts in 2 h 15 min", EventTiming.relative(e, at(Duration.ofMinutes(-135))));
		assertEquals("starts in 3 h", EventTiming.relative(e, at(Duration.ofHours(-3))));
		assertEquals("starts in 45 min", EventTiming.relative(e, at(Duration.ofMinutes(-45))));
		assertEquals("starts in less than a minute", EventTiming.relative(e, at(Duration.ofSeconds(-30))));
		assertEquals("starts in 2 days", EventTiming.relative(e, at(Duration.ofHours(-50))));
		assertEquals("ends in 45 min", EventTiming.relative(e, at(Duration.ofMinutes(75))));
		assertEquals("ended 2 days ago", EventTiming.relative(e, END.plus(Duration.ofHours(49))));
		assertEquals("ended 1 day ago", EventTiming.relative(e, END.plus(Duration.ofHours(24))));
		assertEquals("ended 5 min ago", EventTiming.relative(e, END.plus(Duration.ofMinutes(5))));
	}

	@Test
	public void relativeTextsWithDiscordStatus()
	{
		assertEquals("live now", EventTiming.relative(event("active"), END.plus(Duration.ofMinutes(10))));
		assertEquals("ends in 2 h", EventTiming.relative(event("active"), START));
		assertEquals("ended", EventTiming.relative(event("completed"), at(Duration.ofMinutes(30))));
		assertEquals("", EventTiming.relative(event("canceled"), START));
	}

	@Test
	public void timeRangeInLocalZone()
	{
		ClanEvent e = event("scheduled");
		assertEquals("Fri 14.11. 19:00–21:00", EventTiming.timeRange(e, ZoneId.of("Europe/Helsinki")));
		assertEquals("Fri 14.11. 18:00–20:00", EventTiming.timeRange(e, ZoneId.of("Europe/Stockholm")));
	}

	@Test
	public void timeRangeOverMidnight()
	{
		ClanEvent e = new ClanEvent("2", "Late", "", null, Instant.parse("2025-11-14T21:00:00Z"),
			Instant.parse("2025-11-15T00:30:00Z"), "scheduled");
		assertEquals("Fri 14.11. 23:00 – Sat 15.11. 02:30", EventTiming.timeRange(e, ZoneId.of("Europe/Helsinki")));
	}
}
