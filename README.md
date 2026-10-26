# Glock

Virtual glock to shoot each other in chat during discussions.

## Usage

Just add [@glockkobot](https://glockkobot.t.me) to your chat and use the `/shoot` command to shoot your buddies. See
also `/help`.

Messages sent on behalf of channels can also be hit by `/shoot`, `/buckshot`, and `/statuette`, and healed with
`/heal`. The bot needs administrator rights to restrict members. Anonymous group administrators cannot be targeted.

Successful hits leave a short, silent log in the chat: `💥 @shooter → @target — 5m`.
The duration is the total remaining mute time, including earlier hits; seconds are shown when needed (`5m 30s`).
User labels link to their Telegram IDs; public channel labels link to their usernames.
Channels without a username are shown by title and ID. Statuette hits name the person who placed the statuette.
Ban logs remain in the chat history.

Shots restrict only sending messages, media, polls, stickers and link previews. Reactions, member tag editing,
invitations, pins, chat information and topic permissions retain their existing values. Tags themselves are never
changed. Glock extends its own active mutes; it skips members already restricted by another administrator or by an
older version without a saved record. The configured duration must be between 60 seconds and 366 days. Very slow
storage operations can cause a shot to be skipped rather than accidentally creating a permanent restriction.

With the healing code, `/heal` removes **any** individual restriction, including moderator restrictions and old
Glock mutes. Members then inherit the group's current default permissions. The bot confirms healing only after
Telegram confirms success. Automatic cleanup is conservative: it only releases a still-matching Glock restriction,
so it does not intentionally overwrite a later moderator change. Telegram offers no atomic conditional update;
a moderator change concurrent with the final request cannot be completely excluded.

## Persistence

Set `STORAGE_CHANNEL_ID` to the numeric ID of a dedicated private channel and give the bot full administrator rights
there. Keep its description and stored files unchanged. No persistent local disk is needed. Run one Glock instance
per storage channel. When upgrading from the old storage library, stop the old process completely before starting
the new version: its shutdown hook can otherwise publish an outdated index.

Both channel-ban deadlines and user restriction snapshots share a CBOR index in that channel. Each save uploads a
snapshot and publishes the index before returning, including before a user restriction is applied. Failed or
ambiguous requests retain enough information for reconciliation after a restart. Unreadable storage stops a write
instead of silently creating an empty registry. The existing `telegram-storage:2025.03.20` index and channel-ban
snapshots remain readable; the old library is no longer required, and saves no longer depend on graceful shutdown.

Telegram expires ordinary user mutes even when the bot is offline. Channel senders have no timed ban in the Bot API,
so Glock removes their saved bans when they expire, or after restarting if the deadline passed while offline.
Repeated hits extend the remaining time. Telegram's channel ban prevents the channel owner from writing on behalf
of any of their channels in that chat. See the [Telegram Bot API](https://core.telegram.org/bots/api#banchatsenderchat).

## Build

Use Java 21 and `./gradlew clean test installDist`. The wrapper pins Gradle 9.7.0; Kotlin is 2.4.21.
Docker uses the same wrapper and Java 21 for both build and runtime, and runs tests before assembling the application.
The Telegram client remains `telegram:10.0.0`, its latest release. A small HTTP adapter supplies the complete current
permissions schema, including reactions and member tags, with independent permissions explicitly enabled.
