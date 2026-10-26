# Glock

Virtual glock to shoot each other in chat during discussions.

## Usage

Just add [@glockkobot](https://glockkobot.t.me) to your chat and use the `/shoot` command to shoot your buddies. See
also `/help`.

Reply to a message with `/shoot` to mute its author for the configured duration (5 minutes by default). `/buckshot`
hits a random handful of recent authors for random durations. `/statuette` leaves a 🗿 that mutes the next member who
writes. Messages sent on behalf of channels can be hit too; their ban has no timer in Telegram, so Glock keeps the
deadline itself. The bot needs administrator rights to restrict members. Anonymous group administrators cannot be
targeted, and Telegram refuses to restrict administrators.

Every hit leaves a short, silent kill feed line. The muted side comes first, then after two spaces the explosion glued to the muzzle,
then the hunter:

```
@victim🤐5m  💥🔫 @hunter
@victim🤐5m+5m  💥🔫 @hunter        repeated hit: what was left, plus what this hit added
@a🤐3m · @b🤐7m+2m  💥🔫 @hunter   buckshot, pellets merged by victim
@victim🤐5m  💥🗿 @hunter           statuette
@hunter🤐5m  💥🔫                   shooting your own message leaves nobody at the grip
```

Administrators are armoured: Telegram refuses to restrict them, so the bullet ricochets, with equal chances back
into the hunter, into a random author of the last twelve messages, or into thin air. The shield faces the gun and
takes the explosion. Everyone the bullet met stands on the left, separated by dots, each with their state; a puff at
the shield is the ricochet, and the rest of the line says where the bullet went. Nothing ever vanishes silently:

```
@bystander🤐5m · @admin🛡️💨  💥🔫 @hunter   the bullet flew on into a bystander
@admin🛡️💨  💥🔫 @hunter🤐5m             it came back into the hunter
@admin🛡️💨  💥🔫 @hunter                 it found nobody: a miss, or the bystander was armoured too
@admin🛡️  💥🔫                           an armoured hunter shooting their own message: no ricochet
@admin🛡️💨  💥🗿 @hunter🤐5m             a statuette stepped on by armour hits whoever placed it
```

Buckshot pellets ricochet one by one. The kill feed line is sent together with the restriction and edited when a
ricochet changes it.

Durations are whole minutes, hours and days (`1h30m`, `2d3h`); seconds are never shown, and less than a minute
left counts as nothing. User labels link to their Telegram IDs, public channels link to their usernames, other
channels are shown by title and ID.

Shots restrict only sending messages, media, polls, stickers and link previews; reactions, tags, invitations, pins
and the rest keep the group's default values. Repeated hits stack. A member whom Telegram already reports as
restricted is shot anyway. With the healing code, `/heal` in reply to a message lifts **any** individual restriction
by sending the group's current default permissions, which makes the member a regular member again. The code uses
the command's send time.

## Speed

Speed is the first priority; the cost of a mistake in a toy gun is about zero.

- Every update is handled at once on its own virtual thread. Nothing queues behind an earlier shot.
- A shot costs one round trip to Telegram: the restriction and the kill feed line are sent at the same time.
  Buckshot pellets fly in parallel. Only a ricochet costs a second round trip, for the bounce and the edited line.
- Nothing is read from Telegram before a shot. Deadlines live in memory; the group's default permissions are read
  once per chat and cached for five minutes, prefetched when a chat is first seen.
- One HTTP client serves everything and is pinged every 30 seconds, so the first shot after a lull pays no handshake.
- Mutes are not journaled: Telegram lifts timed user restrictions by itself. After a restart the first hit on a
  still-muted member starts counting from the configured duration again instead of stacking.

Telegram's own limit remains: about twenty bot messages per minute per group. In a very hot firefight some kill
feed lines are refused by Telegram and logged.

## Configuration

| Variable | Meaning | Default |
| --- | --- | --- |
| `BOT_TOKEN` | Bot API token | required |
| `STORAGE_CHANNEL_ID` | numeric ID of a private channel where the bot is an administrator; channel-ban deadlines are kept in its pinned message | required |
| `RESTRICTIONS_DURATION` | mute per hit, `5m`, `90s`, `1h`; between 60 seconds and 366 days | `5m` |
| `HEALING_CONSTANT` | multiplier of the healing code | `7` |
| `HEALING_TIME_ZONE` | time zone of the healing code | `Asia/Jerusalem` |
| `BORING_CHAT_IDS` | comma-separated chat IDs whose messages are not logged to the console | empty |

Channel bans are the only persisted state: a JSON map of deadlines in the storage channel's pinned message, written
within two seconds of a change and read at startup. Expired channel bans are lifted by a background cycle every two
seconds, also after a restart. Earlier versions kept a CBOR index in the channel description; it is ignored, so lift
channel bans that were active during the upgrade with `/heal`.

## Diagnostics

Every failed Bot API call is logged with its method, parameters and Telegram's answer; the token never appears.
Chat messages are logged to the console with chat and author, except in boring chats.

## Build

Use Java 21 and `./gradlew clean test installDist`. The wrapper pins Gradle 9.7.0; Kotlin is 2.4.21. The only
runtime dependencies are OkHttp and Gson: the Bot API is called directly. Docker uses the same wrapper and Java 21
for both build and runtime, and runs tests before assembling the application.
