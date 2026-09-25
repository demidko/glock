# Glock

Virtual glock to shoot each other in chat during discussions.

## Usage

Just add [@glockkobot](https://glockkobot.t.me) to your chat and use the `/shoot` command to shoot your buddies. See
also `/help`.

Messages sent on behalf of channels can also be hit by `/shoot`, `/buckshot`, and `/statuette`, and healed with
`/heal`. The bot needs administrator rights to restrict members. Anonymous group administrators cannot be targeted.

Telegram has no timed ban for channel senders, so the bot saves ban deadlines with
[telegram-storage](https://github.com/demidko/telegram-storage) and removes the bans when they expire.
Repeated hits extend the remaining time. Set `STORAGE_CHANNEL_ID` to the numeric ID of a dedicated private channel
and give the bot full administrator rights there. Keep its description and stored files unchanged.
The bot uses `com.github.demidko:telegram-storage:2025.03.20`; no persistent disk is needed.
This version publishes its index in `close()`, called by the library's shutdown hook on a normal shutdown.
Allow the bot to shut down gracefully during deployments; a forced stop can lose changes since the last published index.
If the bot is offline when a channel's ban expires, it removes the ban after starting again.
Telegram's channel ban prevents the channel owner from writing on behalf of any of their channels in that chat.
See the [Telegram Bot API](https://core.telegram.org/bots/api#banchatsenderchat).
