# Plebcity

RuneLite plugin for the **Plebcity** Old School RuneScape clan. It connects your game client to the
Plebcity clan bot (a Discord bot with a small HTTP API, run by the clan).

## What it does

- **Account linking.** A clan admin creates a one-time code in Discord (`/linkita`). You paste it into the
  plugin settings, and the plugin swaps it for a personal token that is stored in your RuneLite profile.
- **Clan broadcasts.** When *Receive broadcasts* is on, the plugin asks the API for new broadcasts
  (sent by clan admins in Discord) every 60 seconds. Each new broadcast is shown:
  - in your chatbox as `[Plebcity] ...`
  - in a box above the chatbox (*Broadcast overlay*, on by default). The box closes after
    *Broadcast duration* (30 s by default). You can also close it with its **X** button or the
    right-click option **Dismiss**. If several broadcasts arrive, they are shown one at a time with a
    counter such as `1/3`.
  - as a RuneLite notification, if *Notify on broadcast* is on (off by default).

  These messages are only shown locally. Nobody else sees them, and nothing is typed or sent in game.
  The first time you poll after linking, the latest few broadcasts are shown in the chatbox only.
- **Clan coffer tracking** (off by default, setting *Report clan coffer events*). When a clan member
  deposits to or withdraws from the clan coffer, the game sends a clan broadcast to everyone in clan chat,
  for example `ToivoM has deposited 1,000,000 coins into the coffer.` When this setting is on and you are
  linked, the plugin reports these broadcasts to the clan bot. Several members may report the same event,
  and the server removes the duplicates. The player who deposits does not need the plugin.
- **Debug logging** (off by default). Writes chat messages and varbit changes to the RuneLite client log,
  on your own machine only. This helps with development. Debug output is never sent anywhere, and the
  plugin never sends raw clan chat messages to the API. It only sends the parsed coffer event.

The plugin **never** sends anything to the game server for you: no chat messages, no menu actions, no input.

## What data is sent, and where

All requests go **only** to the API URL set in the plugin settings, which is the clan's own server.
That server is not run, controlled or verified by RuneLite or Jagex.

| When | Request | Data sent |
|------|---------|-----------|
| You enter a link code | `POST /api/link` | the link code and your RuneScape name (RSN) |
| Every 60 s, if *Receive broadcasts* is on and you are linked | `GET /api/announcements?since=<id>` | your token and the ID of the last announcement you saw |
| A coffer deposit/withdrawal is broadcast in clan chat, *Report clan coffer events* is on and you are linked | `POST /api/events/coffer` | your token and the parsed event (see below) |

### Clan coffer events

When *Report clan coffer events* is on, the plugin sends exactly these fields for each coffer broadcast:

| Field | Example | Meaning |
|-------|---------|---------|
| `type` | `deposit` or `withdraw` | whether coins went into or out of the coffer |
| `player` | `ToivoM` | the name of the clan member who deposited or withdrew (often not you) |
| `amount` | `1000000` | number of coins |
| `clientTime` | `1794429148000` | your computer's clock time in milliseconds |

This data comes **only** from the clan's own broadcast messages ("... has deposited/withdrawn ... coins
into/from the coffer."). The game already shows these broadcasts to every clan member who is in clan chat.
The plugin does not read or send any other chat messages, including other players' clan chat
messages. It never sends the raw message text, only the four fields above.

Like any HTTP request, every call shows your **IP address** to the server. The server stores your RSN,
a hash of your token, and the coffer events you report. Admins can see this data in Discord.

No requests are made until you enter a link code or turn on *Receive broadcasts* or
*Report clan coffer events*. Both settings are off by default.

## Installation

1. In RuneLite, open the **Plugin Hub** and search for **Plebcity**. Install it.
2. Open the plugin settings:
   - **API URL**: the address your clan admin gives you.
   - **Link code**: the code from `/linkita`. Log in to the game, and the plugin links your account and
     then clears the field.
   - **Receive broadcasts**: turn it on to get clan broadcasts in chat and above the chatbox.
   - **Report clan coffer events**: turn it on to report clan coffer deposits and withdrawals to the
     clan bot.

## Development

Requires JDK 11 or newer.

```bash
./gradlew build      # compile and test
./gradlew run        # start RuneLite in developer mode with this plugin loaded
```

`./gradlew run` runs `com.plebcity.PlebcityPluginTest`, which loads the plugin into a development
RuneLite client. To log in with a Jagex account, see
<https://github.com/runelite/runelite/wiki/Using-Jagex-Accounts>.

## License

BSD 2-Clause. See [LICENSE](LICENSE).
