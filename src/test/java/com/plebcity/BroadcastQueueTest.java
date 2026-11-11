package com.plebcity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import org.junit.Test;

public class BroadcastQueueTest
{
	private static final long DURATION = 30_000;

	@Test
	public void emptyQueueShowsNothing()
	{
		assertNull(new BroadcastQueue().current(0, DURATION));
	}

	@Test
	public void showsOneAtATimeWithCounter()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.add("b");
		q.add("c");
		BroadcastQueue.Shown s = q.current(1_000, DURATION);
		assertEquals("a", s.getText());
		assertEquals(1, s.getIndex());
		assertEquals(3, s.getTotal());

		s = q.current(1_000 + DURATION, DURATION);
		assertEquals("b", s.getText());
		assertEquals(2, s.getIndex());
		assertEquals(3, s.getTotal());
	}

	@Test
	public void expiresAfterDuration()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		assertEquals("a", q.current(0, DURATION).getText());
		assertEquals(1_000, q.current(DURATION - 1_000, DURATION).getRemainingMillis());
		assertNull(q.current(DURATION, DURATION));
	}

	@Test
	public void timerStartsWhenFirstShown()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.add("b");
		q.current(0, DURATION);
		// "b" becomes visible at 30 s and gets its own full duration.
		assertEquals("b", q.current(DURATION, DURATION).getText());
		assertEquals("b", q.current(2 * DURATION - 1, DURATION).getText());
		assertNull(q.current(2 * DURATION, DURATION));
	}

	@Test
	public void dismissShowsNextWithFreshTimer()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.add("b");
		q.current(0, DURATION);
		q.dismiss(20_000);
		BroadcastQueue.Shown s = q.current(20_000, DURATION);
		assertEquals("b", s.getText());
		assertEquals(DURATION, s.getRemainingMillis());
		q.dismiss(21_000);
		assertNull(q.current(21_000, DURATION));
	}

	@Test
	public void counterResetsAfterBatch()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.add("b");
		q.current(0, DURATION);
		q.dismiss(1);
		q.dismiss(2);
		assertNull(q.current(3, DURATION));
		q.add("c");
		BroadcastQueue.Shown s = q.current(4, DURATION);
		assertEquals(1, s.getIndex());
		assertEquals(1, s.getTotal());
	}

	@Test
	public void newArrivalsIncreaseTotal()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.current(0, DURATION);
		q.add("b");
		assertEquals(2, q.current(1, DURATION).getTotal());
	}

	@Test
	public void pendingIsCapped()
	{
		BroadcastQueue q = new BroadcastQueue();
		for (int i = 0; i < BroadcastQueue.MAX_PENDING + 5; i++)
		{
			q.add("m" + i);
		}
		BroadcastQueue.Shown s = q.current(0, DURATION);
		assertEquals("m5", s.getText());
		assertEquals(BroadcastQueue.MAX_PENDING, s.getTotal());
	}

	@Test
	public void clearRemovesEverything()
	{
		BroadcastQueue q = new BroadcastQueue();
		q.add("a");
		q.add("b");
		q.current(0, DURATION);
		q.clear();
		assertNull(q.current(1, DURATION));
	}
}
