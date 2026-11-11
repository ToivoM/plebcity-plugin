package com.plebcity;

import static org.junit.Assert.assertEquals;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;

public class BroadcastCursorTest
{
	private static final String PROD = "https://api.osrsplebcity.com";

	@Test
	public void usesStoredIdForTheSameApi()
	{
		assertEquals(42, BroadcastCursor.since("42", PROD, PROD));
		assertEquals(42, BroadcastCursor.since("42", PROD + "/", " " + PROD + "  "));
	}

	@Test
	public void resetsForAnotherApi()
	{
		assertEquals(0, BroadcastCursor.since("57", "http://localhost:8000", PROD));
	}

	@Test
	public void resetsValuesFromOlderPluginVersionsWithoutApi()
	{
		assertEquals(0, BroadcastCursor.since("57", null, PROD));
	}

	@Test
	public void resetsMissingOrInvalidValues()
	{
		assertEquals(0, BroadcastCursor.since(null, PROD, PROD));
		assertEquals(0, BroadcastCursor.since("abc", PROD, PROD));
		assertEquals(0, BroadcastCursor.since("-5", PROD, PROD));
	}

	@Test
	public void nextIsTheNewestReturnedId()
	{
		assertEquals(12, BroadcastCursor.next(10, Arrays.asList(11L, 12L)));
	}

	@Test
	public void nextKeepsSinceWhenNothingWasReturned()
	{
		assertEquals(10, BroadcastCursor.next(10, Collections.emptyList()));
	}

	@Test
	public void nextMovesBackWhenTheServerRestartedItsIds()
	{
		// Old database ended at 57; the new server returns its own ids 1..3.
		assertEquals(3, BroadcastCursor.next(57, Arrays.asList(1L, 2L, 3L)));
	}
}
