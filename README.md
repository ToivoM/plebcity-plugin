# Plebcity

The RuneLite plugin of the **Plebcity** Old School RuneScape clan. It connects your game client to the
clan's own Discord bot, so that you see clan broadcasts and clan events in game.

**The plugin does nothing over the network until you enter a link code from a clan admin.**
It never sends anything to the game server on your behalf: no chat messages, no menu actions, no input.

## Features

- **Clan broadcasts.** Clan admins send broadcasts in Discord (`/broadcast`). With *Receive broadcasts*
  on, new broadcasts appear within about a second:
  - in your chatbox as `[Plebcity] ...`, shown only to you
  - in a box above the chatbox (*Broadcast overlay*). It closes by itself after *Broadcast duration*
    (default 30 s), with its **X** button, or with the right-click option **Dismiss**. Several broadcasts
    are shown one at a time with a counter such as `1/3`. Only broadcasts less than 5 minutes old are
    shown here; older ones (for example those sent while you were logged out) go to the chatbox only,
    with their age: `[Plebcity] (3 h ago) message`.
  - as a RuneLite notification, if *Notify on broadcast* is on (off by default)
- **Clan events panel.** A "PLEB" button in the sidebar lists the clan's Discord events as
  *Live now*, *Upcoming* and *Past*, with times in your own time zone. Click an event to see its full
  description.
- **Clan coffer reporting (optional, off by default).** When a clan member deposits to or withdraws
  from the clan coffer, the game shows a clan broadcast to everyone in clan chat, for example
  `ToivoM has deposited 1,000,000 coins into the coffer.` With *Report clan coffer events* on, the
  plugin reports these to the clan bot, which posts them to the clan's Discord admin channel.

## Getting started

1. Install **Plebcity** from the Plugin Hub.
2. Ask a Plebcity clan admin for a link code. They create it in Discord with `/link`; it works once and
   expires in 15 minutes.
3. Log in to the game, open the Plebcity settings and paste the code into **Link code**. The plugin
   links your account, shows `[Plebcity] Account linked to the Plebcity clan bot.` and clears the field.
4. Turn on **Receive broadcasts**, and **Report clan coffer events** if your clan asks you to.

## Settings

| Setting | Default | Contacts the server |
|---------|---------|---------------------|
| Link code | empty | Once, when you enter a code |
| Receive broadcasts | off | Yes, while logged in and linked |
| Broadcast overlay | on | No (display only) |
| Broadcast duration | 30 s | No |
| Notify on broadcast | off | No |
| Events panel | on | Yes, but only after you have linked |
| Report clan coffer events | off | Yes, when a coffer broadcast appears in clan chat |
| Advanced → API URL | `https://api.osrsplebcity.com` | Where all requests go |

## Data sent to a third-party server

All requests go **only** to `https://api.osrsplebcity.com` (or the *API URL* under *Advanced*). This
server belongs to the Plebcity clan. It is **not run, controlled or verified by RuneLite or Jagex**.
Like any web request, every request shows your **IP address** to that server.

**Before you enter a link code, the plugin makes no network requests at all.** Every other request
needs the personal token you get by linking.

| When | Request | What is sent |
|------|---------|--------------|
| You enter a link code (once) | `POST /api/link` | the code and your RuneScape name (RSN) |
| *Receive broadcasts* is on, you are logged in and linked | `GET /api/broadcasts/wait` (one request kept open at a time) | your token and the ID of the last broadcast you saw |
| *Events panel* is on and you are linked: when you open the panel and every 5 minutes | `GET /api/events` | your token |
| *Report clan coffer events* is on, you are linked, and a coffer broadcast appears in clan chat | `POST /api/events/coffer` | your token, the type (deposit or withdraw), the clan member's name and the amount from that broadcast, and your computer's clock time |

About the coffer data:

- It comes **only** from the clan's own system broadcast (`... has deposited/withdrawn N coins
  into/from the coffer.`), which the game already shows to every clan member in clan chat.
- The plugin ignores every other chat message, including all messages written by players. It never
  sends the raw message text, only the fields above.
- Several clan members may report the same broadcast; the server counts it once.

What the server stores:

- your RSN and a SHA-256 **hash** of your token (never the token itself)
- the coffer events reported by clan members
- broadcasts and Discord events created by clan admins

Your token is stored in your RuneLite profile. If the server rejects it, the plugin forgets it and
asks you to link again.

## Building

Requires JDK 11 or newer.

```bash
./gradlew build      # compile and run the tests
./gradlew run        # start RuneLite in developer mode with this plugin loaded
```

`./gradlew run` runs `com.plebcity.PlebcityPluginTest`. To log in with a Jagex account in the
development client, see <https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts>.

The icons are generated with RuneLite's own RuneScape font by `tools/generate-icons.sh`
(`tools/generate-icons.sh install b none two` recreates the current ones). The `tools/` folder is not
part of the plugin jar.

The server side (Discord bot and API) is in a separate repository.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
