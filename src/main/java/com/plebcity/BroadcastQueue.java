package com.plebcity;

import java.util.ArrayDeque;
import java.util.Deque;
import lombok.Value;

/**
 * Broadcasts waiting to be shown in the overlay, one at a time. Thread-safe: messages arrive on
 * OkHttp threads, the overlay reads on the client thread and dismissals come from input/menu handlers.
 */
final class BroadcastQueue
{
	/** Keep at most this many waiting broadcasts; older ones are still in the chatbox. */
	static final int MAX_PENDING = 10;

	@Value
	static class Shown
	{
		String text;
		/** 1-based position of this broadcast in the current batch. */
		int index;
		/** Shown so far in this batch plus the ones still waiting. */
		int total;
		long remainingMillis;
	}

	private final Deque<String> pending = new ArrayDeque<>();
	private String current;
	private long shownAtMillis;
	private int shownInBatch;

	synchronized void add(String text)
	{
		pending.addLast(text);
		while (pending.size() > MAX_PENDING)
		{
			pending.removeFirst();
		}
	}

	/**
	 * The broadcast to show now, or null. Expired broadcasts are replaced by the next one; the display
	 * time of a broadcast starts when it is first returned here.
	 */
	synchronized Shown current(long nowMillis, long durationMillis)
	{
		if (current != null && nowMillis - shownAtMillis >= durationMillis)
		{
			advance(nowMillis);
		}
		if (current == null && !pending.isEmpty())
		{
			advance(nowMillis);
		}
		if (current == null)
		{
			return null;
		}
		long remaining = Math.max(0, durationMillis - (nowMillis - shownAtMillis));
		return new Shown(current, shownInBatch, shownInBatch + pending.size(), remaining);
	}

	/** Closes the current broadcast; the next one (if any) is shown with a fresh timer. */
	synchronized void dismiss(long nowMillis)
	{
		if (current != null)
		{
			advance(nowMillis);
		}
	}

	synchronized void clear()
	{
		pending.clear();
		current = null;
		shownInBatch = 0;
	}

	private void advance(long nowMillis)
	{
		current = pending.pollFirst();
		if (current == null)
		{
			shownInBatch = 0;
		}
		else
		{
			shownInBatch++;
			shownAtMillis = nowMillis;
		}
	}
}
