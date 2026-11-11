package com.plebcity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Runs the long-poll loop against a local HTTP server. Backoff is scaled down to milliseconds. */
public class BroadcastPollerTest
{
	/** What the fake server does with one request. */
	private interface Reply
	{
		void handle(HttpExchange ex) throws Exception;
	}

	private HttpServer server;
	private ScheduledExecutorService executor;
	private OkHttpClient client;
	private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
	/** Arrival time (ms) and "since" query of every request. */
	private final List<long[]> requests = new CopyOnWriteArrayList<>();
	private final List<String> received = new CopyOnWriteArrayList<>();
	private final AtomicInteger unauthorized = new AtomicInteger();
	private final AtomicLong since = new AtomicLong();
	private final List<String> createdAt = new CopyOnWriteArrayList<>();
	private final AtomicReference<Instant> lastServerNow = new AtomicReference<>();
	private BroadcastPoller poller;

	@Before
	public void setUp() throws IOException
	{
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.setExecutor(Executors.newCachedThreadPool());
		server.createContext("/api/broadcasts/wait", ex ->
		{
			String query = ex.getRequestURI().getQuery();
			long s = Long.parseLong(query.replaceAll(".*since=(\\d+).*", "$1"));
			requests.add(new long[]{System.currentTimeMillis(), s});
			try
			{
				Reply reply = replies.poll(5, TimeUnit.SECONDS);
				(reply == null ? hold(10_000) : reply).handle(ex);
			}
			catch (Exception e)
			{
				// client went away
			}
			finally
			{
				ex.close();
			}
		});
		server.start();

		executor = Executors.newScheduledThreadPool(2);
		client = new OkHttpClient.Builder().readTimeout(10, TimeUnit.SECONDS).build();
		HttpUrl base = HttpUrl.parse("http://127.0.0.1:" + server.getAddress().getPort() + "/api/broadcasts/wait");
		poller = new BroadcastPoller(client, new Gson(), executor, new Backoff(50, 400), since::get,
			s -> new Request.Builder().url(base.newBuilder().addQueryParameter("since", Long.toString(s)).build()).build(),
			new BroadcastPoller.Listener()
			{
				@Override
				public void onBroadcasts(List<BroadcastPoller.Broadcast> broadcasts, long sinceId, Instant serverNow)
				{
					lastServerNow.set(serverNow);
					for (BroadcastPoller.Broadcast b : broadcasts)
					{
						received.add(b.text);
						createdAt.add(b.createdAt);
						since.set(Math.max(since.get(), b.id));
					}
				}

				@Override
				public void onUnauthorized()
				{
					unauthorized.incrementAndGet();
				}
			});
	}

	@After
	public void tearDown()
	{
		poller.stop();
		server.stop(0);
		executor.shutdownNow();
		client.dispatcher().executorService().shutdownNow();
	}

	private static Reply json(int status, String body)
	{
		return ex ->
		{
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			ex.getResponseHeaders().add("Content-Type", "application/json");
			ex.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = ex.getResponseBody())
			{
				out.write(bytes);
			}
		};
	}

	private static Reply hold(long millis)
	{
		return ex ->
		{
			Thread.sleep(millis);
			json(200, "[]").handle(ex);
		};
	}

	private void waitForRequests(int n) throws InterruptedException
	{
		long deadline = System.currentTimeMillis() + 5_000;
		while (requests.size() < n && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue("expected " + n + " requests, got " + requests.size(), requests.size() >= n);
	}

	@Test
	public void deliversBroadcastsAndPollsAgainRightAway() throws Exception
	{
		replies.add(json(200, "[{\"id\": 1, \"text\": \"hello\"}, {\"id\": 2, \"text\": \"world\"}]"));
		poller.start();
		waitForRequests(2);
		assertEquals(java.util.Arrays.asList("hello", "world"), received);
		assertEquals(0, requests.get(0)[1]);
		assertEquals("second request continues from the newest id", 2, requests.get(1)[1]);
		assertTrue("next request starts immediately", requests.get(1)[0] - requests.get(0)[0] < 1_000);
	}

	@Test
	public void passesCreatedAtAndTheServerTimeFromTheDateHeader() throws Exception
	{
		// The JDK HttpServer always writes its own Date header, so this test uses a raw socket to send
		// one fixed response with a chosen Date, then holds further requests open.
		try (ServerSocket raw = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")))
		{
			Thread serverThread = new Thread(() ->
			{
				try (Socket socket = raw.accept())
				{
					BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
					String line;
					while ((line = in.readLine()) != null && !line.isEmpty())
					{
						// skip request line and headers
					}
					String body = "[{\"id\": 1, \"text\": \"hi\", \"created_at\": \"2025-11-14T16:58:00Z\"}]";
					String response = "HTTP/1.1 200 OK\r\n"
						+ "Date: Fri, 14 Nov 2025 17:00:00 GMT\r\n"
						+ "Content-Type: application/json\r\n"
						+ "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n"
						+ "Connection: close\r\n\r\n" + body;
					socket.getOutputStream().write(response.getBytes(StandardCharsets.UTF_8));
					socket.getOutputStream().flush();
					raw.accept(); // the next long poll: keep it open until the test ends
				}
				catch (IOException e)
				{
					// test finished
				}
			});
			serverThread.setDaemon(true);
			serverThread.start();

			HttpUrl url = HttpUrl.parse("http://127.0.0.1:" + raw.getLocalPort() + "/api/broadcasts/wait");
			BroadcastPoller p = new BroadcastPoller(client, new Gson(), executor, new Backoff(50, 400), since::get,
				s -> new Request.Builder().url(url).build(), new BroadcastPoller.Listener()
				{
					@Override
					public void onBroadcasts(List<BroadcastPoller.Broadcast> broadcasts, long sinceId, Instant serverNow)
					{
						lastServerNow.set(serverNow);
						broadcasts.forEach(b -> createdAt.add(b.createdAt));
					}

					@Override
					public void onUnauthorized()
					{
					}
				});
			p.start();
			long deadline = System.currentTimeMillis() + 5_000;
			while (lastServerNow.get() == null && System.currentTimeMillis() < deadline)
			{
				Thread.sleep(10);
			}
			p.stop();
			assertEquals(java.util.Collections.singletonList("2025-11-14T16:58:00Z"), createdAt);
			assertEquals(Instant.parse("2025-11-14T17:00:00Z"), lastServerNow.get());
		}
	}

	@Test
	public void emptyResponseStartsTheNextRequestImmediately() throws Exception
	{
		replies.add(json(200, "[]"));
		replies.add(json(200, "[]"));
		poller.start();
		waitForRequests(3);
		assertTrue(requests.get(2)[0] - requests.get(0)[0] < 1_000);
		assertTrue(received.isEmpty());
	}

	@Test
	public void backsOffExponentiallyOnErrorsAndResetsAfterSuccess() throws Exception
	{
		replies.add(json(500, "{}"));
		replies.add(json(503, "{}"));
		replies.add(json(500, "{}"));
		replies.add(json(200, "[]"));
		replies.add(json(500, "{}"));
		poller.start();
		waitForRequests(6);
		long gap1 = requests.get(1)[0] - requests.get(0)[0];
		long gap2 = requests.get(2)[0] - requests.get(1)[0];
		long gap3 = requests.get(3)[0] - requests.get(2)[0];
		long gapAfterSuccess = requests.get(4)[0] - requests.get(3)[0];
		long gapReset = requests.get(5)[0] - requests.get(4)[0];
		assertTrue("50 ms: " + gap1, gap1 >= 45);
		assertTrue("100 ms: " + gap2, gap2 >= 95);
		assertTrue("200 ms: " + gap3, gap3 >= 195);
		assertTrue("immediately after success: " + gapAfterSuccess, gapAfterSuccess < 150);
		assertTrue("backoff reset to 50 ms: " + gapReset, gapReset >= 45 && gapReset < 195);
	}

	@Test
	public void networkErrorIsRetried() throws Exception
	{
		replies.add(ex ->
		{
			// Close the connection without a response.
			ex.getResponseBody().close();
		});
		replies.add(json(200, "[{\"id\": 5, \"text\": \"after error\"}]"));
		poller.start();
		waitForRequests(3);
		assertTrue(received.contains("after error"));
	}

	@Test
	public void unauthorizedStopsTheLoop() throws Exception
	{
		replies.add(json(401, "{}"));
		poller.start();
		waitForRequests(1);
		Thread.sleep(300);
		assertEquals(1, unauthorized.get());
		assertEquals(1, requests.size());
		assertFalse(poller.isRunning());
	}

	@Test
	public void stopCancelsTheOpenRequest() throws Exception
	{
		CountDownLatch released = new CountDownLatch(1);
		replies.add(ex ->
		{
			try
			{
				hold(3_000).handle(ex);
			}
			finally
			{
				released.countDown();
			}
		});
		poller.start();
		waitForRequests(1);
		Thread.sleep(100);
		poller.stop();
		assertEquals("cancelled call is not retried", 0, client.dispatcher().runningCallsCount());
		Thread.sleep(500);
		assertEquals(1, requests.size());
		assertFalse(poller.isRunning());
	}

	@Test
	public void requestFactoryReturningNullStopsTheLoop() throws Exception
	{
		BroadcastPoller p = new BroadcastPoller(client, new Gson(), executor, new Backoff(50, 400), () -> 0, s -> null,
			new BroadcastPoller.Listener()
			{
				@Override
				public void onBroadcasts(List<BroadcastPoller.Broadcast> broadcasts, long sinceId, Instant serverNow)
				{
				}

				@Override
				public void onUnauthorized()
				{
				}
			});
		p.start();
		Thread.sleep(200);
		assertFalse(p.isRunning());
		assertTrue(requests.isEmpty());
	}

	@Test
	public void restartAfterStopWorks() throws Exception
	{
		// The first request gets its own reply, so the server thread of the cancelled request
		// doesn't take the reply meant for the request after the restart.
		replies.add(hold(3_000));
		poller.start();
		waitForRequests(1);
		poller.stop();
		replies.add(json(200, "[{\"id\": 9, \"text\": \"again\"}]"));
		poller.start();
		long deadline = System.currentTimeMillis() + 5_000;
		while (!received.contains("again") && System.currentTimeMillis() < deadline)
		{
			Thread.sleep(10);
		}
		assertTrue(received.contains("again"));
	}
}
