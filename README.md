# Glock

Virtual glock to shoot each other in chat during discussions.

## Usage

Just add [@glockkobot](https://glockkobot.t.me) to your chat and use the `/shoot` command to shoot your buddies. See
also `/help`.

Messages sent on behalf of channels can also be hit by `/shoot`, `/buckshot`, and `/statuette`, and healed with
`/heal`. The bot needs administrator rights to restrict members. Anonymous group administrators cannot be targeted.

Successful hits leave a short, silent log in the chat: `🤐5m @target 💥🔫 @shooter`. The muzzle points at the
muted side. The duration is the total remaining mute time, including earlier hits; seconds are shown when needed
(`5m 30s`), and a hit that extended an existing mute also shows its own addition (`🤐10m (+5m)`).
A buckshot volley is one line with a duration beside each victim: `🤐3m @a · 🤐7m @b 💥💥🔫 @shooter`.
Shooting your own message leaves nobody at the grip: `🤐5m @you 💥🔫`.
User labels link to their Telegram IDs; public channel labels link to their usernames.
Channels without a username are shown by title and ID. Statuette hits use `💥🗿` and name the person who placed
the statuette. Ban logs remain in the chat history. A statuette remains armed until a restriction succeeds; failed hits,
bots, anonymous administrators and basic service messages do not consume it.

Shots restrict only sending messages, media, polls, stickers and link previews. Reactions, member tag editing,
invitations, pins, chat information and topic permissions retain their existing values. Tags themselves are never
changed. Glock extends its own active mutes without re-reading the member, so a moderator change made during such a mute
is overwritten by the next hit; group default permissions are cached for five minutes. A member whom Telegram
reports as restricted without a Glock record, such as after a lift that left an exception entry or a moderator
restriction, is shot anyway, and the new mute stacks on the existing deadline. Only administrators and absent
members cannot be shot. The configured duration must be between 60 seconds and 366 days. Very slow
storage operations can cause a shot to be skipped rather than accidentally creating a permanent restriction.

With the healing code, `/heal` removes **any** individual restriction, including moderator restrictions and old
Glock mutes. Members then inherit the group's current default permissions: the lift sends exactly those, because
granting every permission would leave the member as a "restricted" exception entry. The bot confirms healing only after
Telegram confirms success, without waiting for journal uploads. The healing code uses the command's send time,
so waiting in a busy chat queue does not invalidate it. Automatic cleanup is conservative: it only releases a still-matching Glock restriction,
so it does not intentionally overwrite a later moderator change. Telegram offers no atomic conditional update;
a moderator change concurrent with the final request cannot be completely excluded.

## Persistence

Set `STORAGE_CHANNEL_ID` to the numeric ID of a dedicated private channel and give the bot full administrator rights
there. Keep its description and stored files unchanged. No persistent local disk is needed. Run one Glock instance
per storage channel. When upgrading from the old storage library, stop the old process completely before starting
the new version: its shutdown hook can otherwise publish an outdated index.

Both channel-ban deadlines and user restriction snapshots share a CBOR index in that channel. Each save uploads a
snapshot and publishes the index before returning. Shots never wait for that: a user restriction is recorded in
memory and applied at once, and a maintenance loop publishes the journal about once a second, one upload for every
shot made in the meantime. Channel bans are still published before Telegram receives them, because the API has no
timed ban to fall back on. Failed or ambiguous requests retain enough information for reconciliation after a restart. Unreadable storage stops a write
instead of silently creating an empty registry. The existing `telegram-storage:2025.03.20` index and channel-ban
snapshots remain readable; the old library is no longer required, and saves no longer depend on graceful shutdown.
Healing and other bookkeeping update memory immediately as well. Network uploads do not hold the shared in-memory
registry lock, and a confirmed publication keeps the index in memory, so the channel is re-read only after a failed
or ambiguous upload. If the process stops before a deferred write, user records are reconciled against Telegram after
restart; a mute whose record was never published keeps Telegram's own deadline, and Glock does not extend it until it
expires, although `/heal` still releases it. Channel deadlines are retried, because the API does not expose an
equivalent channel restriction state.

Telegram expires ordinary user mutes even when the bot is offline. Channel senders have no timed ban in the Bot API,
so Glock removes their saved bans when they expire, or after restarting if the deadline passed while offline.
Repeated hits extend the remaining time. Telegram's channel ban prevents the channel owner from writing on behalf
of any of their channels in that chat. See the [Telegram Bot API](https://core.telegram.org/bots/api#banchatsenderchat).

## Error diagnostics

Failures include the originating command and arguments, actor and target IDs/names/usernames, chat and topic,
message IDs, Telegram method and requested parameters, HTTP status and server response. Exceptions retain their
stack and cause; rejected API requests include the local call stack. Healing codes may appear in these logs;
bot API tokens are redacted. One failed maintenance task does not stop subsequent cleanup cycles.

## Build

Use Java 21 and `./gradlew clean test installDist`. The wrapper pins Gradle 9.7.0; Kotlin is 2.4.21.
Docker uses the same wrapper and Java 21 for both build and runtime, and runs tests before assembling the application.
The Telegram client remains `telegram:10.0.0`, its latest release. A small HTTP adapter supplies the complete current
permissions schema, including reactions and member tags, with independent permissions explicitly enabled.
