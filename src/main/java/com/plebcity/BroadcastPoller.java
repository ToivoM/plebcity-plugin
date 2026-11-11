package com.plebcity;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Long-poll loop for broadcasts: keeps one GET /api/broadcasts/wait request open and starts the next one
 * as soon as a response arrives. Errors (network, 5xx, unexpected status) are retried with exponential
 * backoff; 401 stops the loop. All network IO runs on OkHttp's threads.
 */
@Slf4j
final class BroadcastPoller
{
	static final class Broadcast
	{
		long id;
		String text;
	}

	interface Listener
	{
		/** New broadcasts for a request made with the given since id. Called on an OkHttp thread. */
		void onBroadcasts(List<Broadcast> broadcasts, long since);

		/** The API rejected the token. The loop has already stopped. */
		void onUnauthorized();
	}

	private final OkHttpClient client;
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final Backoff backoff;
	/** Current "since" id (the newest broadcast already shown). */
	private final LongSupplier since;
	/** Builds the request for a since id, or returns null if polling isn't possible (e.g. no token). */
	private final LongFunction<Request> requestFactory;
	private final Listener listener;

	// Guarded by this. The generation changes on every start/stop, so callbacks of an old loop are ignored.
	private boolean running;
	private int generation;
	private Call call;
	private ScheduledFuture<?> retry;

	BroadcastPoller(OkHttpClient client, Gson gson, ScheduledExecutorService executor, Backoff backoff,
		LongSupplier since, LongFunction<Request> requestFactory, Listener listener)
	{
		this.client = client;
		this.gson = gson;
		this.executor = executor;
		this.backoff = backoff;
		this.since = since;
		this.requestFactory = requestFactory;
		this.listener = listener;
	}

	synchronized boolean isRunning()
	{
		return running;
	}

	/** Starts the loop if it isn't running. */
	synchronized void start()
	{
		if (running)
		{
			return;
		}
		running = true;
		generation++;
		backoff.reset();
		final int gen = generation;
		executor.execute(() -> poll(gen));
		log.debug("Plebcity broadcast long polling started");
	}

	/** Stops the loop and cancels the open request, if any. */
	synchronized void stop()
	{
		if (!running)
		{
			return;
		}
		running = false;
		generation++;
		if (call != null)
		{
			call.cancel();
			call = null;
		}
		if (retry != null)
		{
			retry.cancel(false);
			retry = null;
		}
		log.debug("Plebcity broadcast long polling stopped");
	}

	private void poll(int gen)
	{
		final long sinceId;
		final Call newCall;
		synchronized (this)
		{
			if (!running || gen != generation)
			{
				return;
			}
			sinceId = since.getAsLong();
			Request request;
			try
			{
				request = requestFactory.apply(sinceId);
			}
			catch (RuntimeException e)
			{
				log.warn("Plebcity could not build the broadcast request", e);
				request = null;
			}
			if (request == null)
			{
				stop();
				return;
			}
			newCall = client.newCall(request);
			call = newCall;
		}

		newCall.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call c, IOException e)
			{
				if (c.isCanceled() || !isCurrent(gen))
				{
					return;
				}
				log.debug("Plebcity broadcast long poll failed: {}", e.getMessage());
				retryLater(gen);
			}

			@Override
			public void onResponse(Call c, Response response)
			{
				try (Response r = response)
				{
					if (!isCurrent(gen))
					{
						return;
					}
					if (r.code() == 401)
					{
						stop();
						listener.onUnauthorized();
						return;
					}
					if (!r.isSuccessful())
					{
						log.debug("Plebcity broadcast long poll returned HTTP {}", r.code());
						retryLater(gen);
						return;
					}
					ResponseBody body = r.body();
					Broadcast[] list = gson.fromJson(body == null ? "[]" : body.string(), Broadcast[].class);
					if (list != null && list.length > 0)
					{
						listener.onBroadcasts(Arrays.asList(list), sinceId);
					}
					synchronized (BroadcastPoller.this)
					{
						backoff.reset();
					}
					next(gen);
				}
				catch (IOException | JsonParseException e)
				{
					log.debug("Plebcity broadcast response could not be read: {}", e.getMessage());
					retryLater(gen);
				}
				catch (RuntimeException e)
				{
					log.warn("Plebcity broadcast handling failed", e);
					retryLater(gen);
				}
			}
		});
	}

	private synchronized boolean isCurrent(int gen)
	{
		return running && gen == generation;
	}

	private synchronized void next(int gen)
	{
		if (isCurrent(gen))
		{
			executor.execute(() -> poll(gen));
		}
	}

	private synchronized void retryLater(int gen)
	{
		if (!isCurrent(gen))
		{
			return;
		}
		long delay = backoff.next();
		log.debug("Plebcity retrying broadcast long poll in {} ms", delay);
		retry = executor.schedule(() -> poll(gen), delay, TimeUnit.MILLISECONDS);
	}
}
