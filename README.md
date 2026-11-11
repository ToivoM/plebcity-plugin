# Plebcity

RuneLite plugin for the **Plebcity** Old School RuneScape clan. It connects your game client to the
Plebcity clan bot (a Discord bot with a small HTTP API, run by the clan).

## What it does

- **Account linking.** A clan admin creates a one-time code in Discord (`/linkita`). You paste it into the
  plugin settings, and the plugin swaps it for a personal token that is stored in your RuneLite profile.
- **Clan announcements.** When *Show announcements* is on, the plugin asks the API for new announcements
  every 60 seconds and shows them in your chatbox as `[Plebcity] ...`. These messages are only shown
  locally. Nobody else sees them, and nothing is typed or sent in game.
- **Clan coffer deposits.** Planned. The plugin will report your own clan coffer deposits to the clan bot.
  Detection is not implemented yet.
- **Debug logging** (off by default). Writes chat messages and varbit changes to the RuneLite client log,
  on your own machine only. This helps with development.

The plugin **never** sends anything to the game server for you: no chat messages, no menu actions, no input.

## What data is sent, and where

All requests go **only** to the API URL set in the plugin settings, which is the clan's own server.
That server is not run, controlled or verified by RuneLite or Jagex.

| When | Request | Data sent |
|------|---------|-----------|
| You enter a link code | `POST /api/link` | the link code and your RuneScape name (RSN) |
| Every 60 s, if *Show announcements* is on and you are linked | `GET /api/announcements?since=<id>` | your token and the ID of the last announcement you saw |
| (future) You deposit to the clan coffer | `POST /api/events/coffer` | your token and the deposit amount |

Like any HTTP request, every call shows your **IP address** to the server. The server stores your RSN,
a hash of your token, and your coffer deposits. Admins can see this data in Discord.

No requests are made until you enter a link code or turn on *Show announcements*.

## Installation

1. In RuneLite, open the **Plugin Hub** and search for **Plebcity**. Install it.
2. Open the plugin settings:
   - **API URL**: the address your clan admin gives you.
   - **Link code**: the code from `/linkita`. Log in to the game, and the plugin links your account and
     then clears the field.
   - **Show announcements**: turn it on to get clan announcements in chat.

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
