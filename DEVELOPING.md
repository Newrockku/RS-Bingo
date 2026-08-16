# Developing the RS-Bingo plugin

Build instructions and the reasoning behind the parts that are easy to break.
Player-facing documentation lives in [README.md](README.md).

## Building

```sh
cd runelite-plugin
./gradlew build          # compile + jar + tests
./gradlew run            # launch RuneLite with the plugin side-loaded
```

The Gradle wrapper is checked in, so no Gradle install is needed; it fetches its
own on first run. The client itself comes from `https://repo.runelite.net`.

Any JDK 11 or newer works. The build sets `options.release = 11`, which pins both
the bytecode *and* the API surface to Java 11 — so building on a newer JDK cannot
quietly produce class files, or link against methods, that the client won't load.

`./gradlew run` starts a real client via `RsBingoPluginTest`, which is the normal
way to develop an external plugin.

## Previewing without a client

`./gradlew run` needs a full client and a login. To just *look* at the panel:

```sh
./gradlew preview -Pevent=NSM930
```

That draws the real panel — same components, same fonts, same live data — and
writes `board.png` and `tile.png` to `build/preview/`. It takes a few seconds and
never opens a window.

| Property | Default | Meaning |
| --- | --- | --- |
| `-Pevent=` | *(required)* | Event code to load. |
| `-Purl=` | `https://rs-bingo.com` | Site to read from, e.g. `http://localhost/bingotest`. |
| `-Pteam=` | top of the table | Team to show. |
| `-Ptile=` | `0` | Which tile to open for `tile.png`, by board order. |
| `-Pimages=` | `true` | `false` renders the no-artwork fallback. |
| `-Pout=` | `build/preview` | Where to write the PNGs. |

Two things it has to do that are easy to get wrong if you rewrite it: the frame is
undecorated (so the panel gets exactly the 225px the client gives it, not 225 plus
window borders), and layout is forced synchronously before each capture —
`revalidate()` only *queues* a layout pass, and nothing pumps that queue for a
window that is never shown, so the board would otherwise render blank.

## Configuring

RuneLite → Settings → **RS-Bingo**:

| Setting | Meaning |
| --- | --- |
| Account token | Optional. From the site's dashboard; lists your events in the panel. |
| Event code | The ID from the organiser, e.g. `NSM930`. Blank hides the board. |
| Refresh (seconds) | Board re-fetch interval. `0` disables it; anything under 15 is floored to 15. |
| Show tile images | Draw tile artwork. Off falls back to tier-graded colour fills. |

The event code lives here and only here. It used to be editable in the panel as
well, which meant two copies of one setting: the panel wrote its copy back over
whatever had been typed in this pane. With an account linked you rarely touch it —
the panel's **Event** dropdown sets it for you. The team you pick is remembered the
same way.

## How it talks to the site

Everything comes from rs-bingo.com. Reads need only an event code; the last two are
the exceptions described above.

```
plugin_board.php?eventId=NSM930                            -> event info + team list
plugin_board.php?eventId=NSM930&team=The%20Dark%20Knights  -> the above + that board
plugin_board.php?eventId=NSM930&team=...&tile=3            -> plus tile 3's player breakdown
plugin_themes.php                                          -> the site's colour themes
plugin_events.php   (X-Bingo-Token)                        -> the linked account's events
submit_item.php     (source=plugin)                        -> file a submission
```

## Linking an account (optional)

The panel can list the events you belong to instead of making you type codes. Sign
in on the site, open **/plugin_link.php**, and paste the token into *Settings → RS
Bingo → Account token*.

The token is deliberately **not** a session. It is a third signed type alongside
`event` and `user`, it names only the user id, and `plugin_events.php` is the only
endpoint that accepts it — `auth_sessionClaims()` stays cookie-only, so this cannot
become account access by being pasted somewhere else. Losing it reveals which events
you are in; it cannot change one, submit as you, or sign you in. Rotate
`events/auth_secret.json` to revoke every token at once.

Everything else works without it: an event code alone still views a board, and
submitting is authorised by roster membership, not by this.

The response is already scored. The plugin does **no** scoring of its own, on
purpose: those rules already exist five times over in this project (`score_lib.php`,
`game.html`, `overview.html`, and both overlays) and have drifted apart before — a
Showdown tile tagged `XP` was once scored two different ways depending on which
page you looked at. A sixth copy, in a language nothing else here uses, is the
last place that bug should be able to hide. The server decides; the plugin draws.

That extends to the Showdown tier line and the per-player breakdown.
`sc_computeUimTileBreakdown()` in `score_lib.php` produces the itemised lines
*and* the tile's total, with the total summed from those very lines — so the
"Player Progress" card always adds up to the score the tier was derived from.
`sc_computeUimTileScore()` is a one-line wrapper over it, which is what keeps a
single implementation feeding both the site's points and the panel's display.

**Payload split.** Point rates are small and static, so every tile carries them.
The per-player breakdown is neither — it was 80% of the response — so only the
tile being looked at carries one. The panel asks by appending `&tile=N` to the
board fetch it was already making, so a refresh stays one request either way:

| Request | Size (NSM930, 16 tiles, 9 players) |
| --- | --- |
| Board, no tile open | ~13KB |
| Board with a tile open | ~15KB |
| Breakdowns for every tile (rejected) | ~40KB |
| Raw event file | ~616KB |

## Notes on the UI

A RuneLite side panel is about 225px wide, so the tile "modal" is a full-panel
view swapped in over the grid with a back button, rather than a floating dialog.
It follows the website's modal section for section: artwork, title, tier and
points, a progress bar, then **Description**, **Point Rates** and **Player
Progress**.

Checklist items carry three states, as the site draws them: `✓` approved, `?`
submitted and awaiting review, `○` untouched. The middle one matters — without it
an item someone has already sent proof for is indistinguishable from one nobody
has attempted, and players re-submit. Pending submissions are indexed by
`sc_buildSubmissionMapByTeam()`, the same function that indexes approved ones, so
the two lookups cannot key items differently.

Point Rates is the one that matters for actually playing: every boss, skill,
activity and item on the tile with what one of each is worth (`Boss: Nex` /
`6000 pts/KC`). Showdown tiles show it instead of the bare tag list, which named
the same things without saying what any of them paid. Player Progress then shows
each teammate's total and the kills, XP and drops behind it. On Showdown tiles
these replace the plain item checklist, as they do on the site.

Board cells show the tile's artwork at full brightness with a progress bar along
the bottom, and in the corner either the tier (Showdown) or what the tile is worth
(`10p`). Progress is carried by the bar and the border rather than by dimming the
art, which only made tiles harder to tell apart at 50px. The tooltip repeats the
title, completion and item progress.

Three things are load-bearing and easy to undo by accident:

- **Both cards scroll.** `PluginPanel(false)` gives no scrolling of its own, and a
  7x7 board or a long checklist outruns the panel's height.
- **Scrolled content tracks the viewport width** (`VerticalContent`). Without it a
  panel keeps its widest child's preferred width, and with no horizontal scrollbar
  the overflow is silently cut off.
- **Wrapping text is a `JTextArea`, not an HTML `JLabel`.** Swing treats
  `width:Npx` as a hint, not a constraint; tag lists and descriptions rendered past
  the panel edge and lost words mid-token. Plain text also can't be tripped up by
  markup in an organiser's description.
- **Every `JTextArea` is told its width** (`setWrapped`, and the `label.setSize`
  in `valueRow`). A text area derives its wrapped height from its current width,
  and inside these layouts it is asked for that height before it has been given a
  width — so it answers "one line" and everything past the first wrap is clipped.
  This bites hardest the moment a scrollbar appears and takes another 8px.
- **Caret updates are disabled on them too.** `setText()` moves the caret, and a
  text area drags its scroll pane along to follow it — opening a tile landed you
  in the middle of Player Progress instead of at the tile's title.

A refresh landing while a tile is open updates that tile in place rather than
returning to the grid — at the default 60s interval, the alternative is being
thrown out of whatever you were reading.

## Tests

`./gradlew test` covers the display logic the plugin does own: tier progress and
the MAX case, item counting, image URL resolution, number formatting, and tooltip
escaping. `BoardParsingTest` parses a real captured `plugin_board.php` response
from `src/test/resources/board_sample.json` — Gson maps by field name with no
annotations, so a rename on the PHP side would otherwise fail silently at runtime
and simply draw an empty board.

## Tile images

A tile's stored `img` comes in three shapes and they are **not** interchangeable.
`TileImageCache.resolve()` mirrors `safeImgSrc()` in `game.html`:

| Stored value | Resolves to |
| --- | --- |
| `https://…` | itself |
| `events/<id>/images/x.png` | `<site>/events/<id>/images/x.png` (per-event upload) |
| `OFM055/x.png`, `default/x.png` | `<site>/images/OFM055/x.png` (shared gallery) |

Getting the third case wrong is not subtle — every gallery path 404s and most of a
board renders as blank fills. Segments are percent-encoded (gallery filenames
contain spaces), and `.`/`..` are stripped rather than resolved, so a path can
never climb out of `images/`.

**WebP tile art needs a decoder.** Java's `ImageIO` ships no WebP reader — it has
JPG, PNG, GIF, BMP, TIFF and WBMP — so a `.webp` tile decoded to null and fell back
to a plain cell while the site rendered it fine. `build.gradle` therefore depends on
`com.twelvemonkeys.imageio:imageio-webp`, a pure-Java reader that registers itself
through the ImageIO service loader; no code references it directly. Drop that
dependency and WebP tiles go blank again.

## A note on image weight

Tile art is served at full resolution — one tile on `NSM930` is 2.4MB, and the
16-tile board totals 6.6MB. The plugin decodes each image down to 96px and caches
it, and RuneLite's shared `OkHttpClient` has a disk cache, so this is a one-off
per player rather than per session. It is still worth serving thumbnails from the
site if boards get much larger; the plugin never draws above 96px.
