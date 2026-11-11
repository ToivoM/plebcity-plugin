package com.plebcity;

import java.util.List;

/**
 * The "since" id for broadcast requests: the newest broadcast already shown. It is stored together with
 * the API URL it came from, because ids from one server (or database) mean nothing on another.
 */
final class BroadcastCursor
{
	private BroadcastCursor()
	{
	}

	/** Normalizes an API URL for comparison: trimmed, without trailing slashes. */
	static String normalizeApi(String apiUrl)
	{
		String s = apiUrl == null ? "" : apiUrl.trim();
		while (s.endsWith("/"))
		{
			s = s.substring(0, s.length() - 1);
		}
		return s;
	}

	/**
	 * The since id to use, from the stored id and the API URL it was stored for. Returns 0 (first
	 * request) if nothing is stored, the value is invalid, or it belongs to another API URL. Values
	 * stored by older plugin versions have no API URL and are also reset.
	 */
	static long since(String storedId, String storedApi, String currentApi)
	{
		if (storedId == null || storedApi == null || !normalizeApi(storedApi).equals(normalizeApi(currentApi)))
		{
			return 0;
		}
		try
		{
			return Math.max(0, Long.parseLong(storedId.trim()));
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	/**
	 * The since id after a response. The server decides what is new: if it returns ids lower than the
	 * old since (its database was replaced), the cursor moves back to them instead of ignoring them.
	 */
	static long next(long since, List<Long> returnedIds)
	{
		long max = -1;
		for (Long id : returnedIds)
		{
			if (id != null)
			{
				max = Math.max(max, id);
			}
		}
		return max < 0 ? since : max;
	}
}
