# RS-Bingo

Follow your clan's [rs-bingo.com](https://rs-bingo.com) bingo event from inside
RuneLite: your team's board, what each tile needs, who has done what, and how your
team is placed — plus submitting drops without leaving the game.

## Getting started

1. Open **RS-Bingo** in the sidebar.
2. Open the settings (the wrench) and either:
   - paste the **Event code** your organiser gave you (something like `NSM930`), or
   - paste an **Account token** and pick your event from a list — see below.
3. The board loads, and refreshes itself every minute.

### Listing your own events

Rather than typing event codes, you can link your account once and have every event
you belong to appear in an **Event** dropdown at the top of the panel.

1. Sign in on rs-bingo.com and open your **Dashboard**.
2. In the **RuneLite plugin** panel, press **Show**, then **Copy**.
3. Paste it into *Settings → RS-Bingo → **Account token***.

That token only lists your events. It cannot change an event, submit anything, or
sign in as you. To invalidate it, press **Generate new** on the dashboard — the old
one stops working immediately, so remember to paste the new one.

## The panel

Across the top: your event, the colour theme, and the team you're viewing. Below:

**The board.** Each tile shows its artwork, a progress bar along the bottom, and in
the corner either what it's worth (`10p`) or — on Showdown events — the tier reached
(`T3`). A gold border means finished. Hover for a summary, click for the full view.

**Standings.** Every team, ranked, with the one you're viewing highlighted.

**Team.** Who is on that team, with your own character marked **(you)**.

Under the event name you'll find the countdown — how long is left, or how long until
the event starts — and the event codeword once the organiser has released it.

## Reading a tile

Click any tile:

- **Point Rates** — what each boss, skill and item is actually worth, for example
  `Boss: Nex — 6000 pts/KC`. The quickest way to see what's worth chasing.
- **Player Progress** — each teammate's total on that tile, and where it came from.
- **Checklist** — what the tile needs, in three states:

  | Mark | Meaning |
  | --- | --- |
  | `✓` | approved |
  | `?` | submitted, waiting on a reviewer |
  | `○` | nobody has done this yet |

  Whoever submitted an item is named beside it.
- **XP tiles** show the team's total against the goal, and who contributed.

## Submitting a drop

Open the tile, pick the item in the **Submit** box, and press **Take screenshot &
submit**. Your game window is captured, stamped with the event name, your character
and the event codeword, and uploaded for review. The item turns to `?` immediately.

Nothing counts until an organiser approves it.

The Submit box only appears when **all** of these hold:

- the character you're logged in as is on the team you're viewing
- the event has started and has not ended
- the tile isn't already complete
- the item isn't already approved or awaiting review

If you can't see it, one of those is the reason.

## Settings

| Setting | What it does |
| --- | --- |
| **Account token** | Optional. Lists your events in the panel. Comes from your dashboard. |
| **Event code** | Which event to show. Set for you if you use the Event dropdown. |
| **Refresh (seconds)** | How often the board updates. `0` turns it off. |
| **Show tile images** | Turn off to save bandwidth — a board's artwork can run to several MB the first time it loads. |

## What the plugin sends

- **Always:** the event code, to fetch the board. Nothing about your account.
- **If you link an account:** your token, to list your events.
- **Only when you press submit:** a screenshot of your game window, your character
  name, and the item you're claiming.

Screenshots are never taken or sent unless you press the submit button.

## Troubleshooting

**The Submit box isn't showing.** Check the four conditions above. Most often the
character you're logged in as isn't on the team being viewed, or the event hasn't
started yet.

**My events aren't listed.** Make sure the Account token was pasted in full. If you
pressed *Generate new* on the dashboard, the previous token stopped working — copy
the new one.

**A tile shows a plain square instead of artwork.** The artwork failed to load — the
tile itself still works normally, and everything else on it is accurate.

**Nothing loads.** Check the event code is correct and the event still exists on the
site.

---

Not affiliated with Jagex. Licensed BSD 2-Clause — see [LICENSE](LICENSE).
Building the plugin: see [DEVELOPING.md](DEVELOPING.md).
