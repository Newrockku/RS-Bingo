package com.rsbingo;

import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The shape of plugin_board.php's response. Field names match the JSON exactly, so
 * Gson maps it by name; the only annotations here are the ones tolerating a field
 * whose type changed on the server.
 *
 * Note what is NOT here: no scoring inputs, no snapshots, no submissions. The
 * server decides what is complete and how many points a team has; this plugin only
 * draws the answer. That keeps one scoring implementation instead of adding a
 * sixth in a language the rest of the project doesn't use.
 */
public class BoardModels
{
	public static class Board
	{
		public String eventId;
		public String name;
		public String format;
		public int cols;
		public int rows;
		public int numTiers;
		public String startDate;
		public String endDate;
		public List<TeamSummary> teams = new ArrayList<>();

		/**
		 * The board's named areas, in the order the server lists them — which is the
		 * order they first appear in the event's tile definitions, not board order.
		 *
		 * A region's colour is derived from its index here, so this ordering is what
		 * keeps the panel painting a region the same colour the website does. Working
		 * it out from {@link #board} instead would order by position and drift.
		 *
		 * Empty when the event has regions switched off.
		 */
		public List<String> regions = new ArrayList<>();

		public boolean hasRegions()
		{
			return regions != null && !regions.isEmpty();
		}

		/** Where a region sits in {@link #regions}, or -1 when it is not one. */
		public int regionIndex(String region)
		{
			if (region == null || region.isEmpty() || regions == null)
			{
				return -1;
			}
			return regions.indexOf(region);
		}

		/** The region on a 1-based board position, or "" for none. */
		public String regionAt(int pos)
		{
			for (BoardTile t : board)
			{
				if (t.pos == pos)
				{
					return t.region == null ? "" : t.region;
				}
			}
			return "";
		}

		/**
		 * The event's codeword, sent only once the site itself would show it. Stamped
		 * onto submission screenshots and used to authorise the submission, so the
		 * player never has to type it anywhere. Null while still withheld.
		 */
		public String codeword;

		/** Only present when a team was requested. */
		public String team;
		public List<BoardTile> board = new ArrayList<>();
		public boolean hiddenTiles;

		/**
		 * When withheld tiles become visible, ISO 8601. Null when the organiser set
		 * no release time, in which case they stay hidden until the setting changes.
		 */
		public String tilesReleaseAt;

		/** How the viewed team's total was arrived at. Null until a team is chosen. */
		public Points points;

		public boolean isShowdown()
		{
			return "Showdown".equals(format);
		}

		/**
		 * A board that mixes both kinds of tile. Its showdown tiles have no tier
		 * ladder: each one is complete or not and pays its own flat points, so the
		 * board is scored like a Blackout one — which is why {@link #isShowdown()}
		 * is deliberately false here. Only the per-tile presentation differs.
		 */
		public boolean isHybrid()
		{
			return "Hybrid".equals(format);
		}

		/** Submissions are only open between the start and end dates. */
		public boolean submissionsOpen()
		{
			return Text.withinEventWindow(startDate, endDate, java.time.Instant.now());
		}

		/** Why submissions are shut, or null when they are open. */
		public String submissionsClosedReason()
		{
			final java.time.Instant now = java.time.Instant.now();
			if (Text.withinEventWindow(startDate, endDate, now))
			{
				return null;
			}
			// Outside the window is one of exactly two things, and the countdown line
			// above already tells them which; this names it in the submit box too.
			return Text.startsAfter(startDate, now)
				? "This event has not started yet."
				: "This event has ended.";
		}
	}

	/** plugin_events.php: the events a linked account belongs to. */
	public static class EventList
	{
		public String username;
		public List<EventSummary> events = new ArrayList<>();
	}

	public static class EventSummary
	{
		public String eventId;
		public String name;
		public String role;
		public String format;
		public String startDate;
		public String endDate;

		@Override
		public String toString()
		{
			// The event dropdown renders this directly. The code is worth showing:
			// it is what someone reads out to a teammate who has not linked an account.
			return (name == null || name.isEmpty() ? eventId : name) + "  (" + eventId + ")";
		}
	}

	/** plugin_themes.php: the site's colour themes. */
	public static class ThemeList
	{
		public String defaultKey;
		public List<Theme> themes = new ArrayList<>();
	}

	public static class Theme
	{
		public String key;
		public String label;
		/** CSS custom properties, e.g. "--accent" -> "#d8a830". */
		public java.util.Map<String, String> vars = new java.util.HashMap<>();

		@Override
		public String toString()
		{
			// The theme dropdown renders this directly.
			return label == null ? (key == null ? "" : key) : label;
		}
	}

	/**
	 * The itemised points for one team, as the website's points tooltip shows them.
	 * Computed by the server — sc_computeAllTeamPointsBreakdown() — which is also
	 * where the total on the standings comes from, so the two cannot disagree.
	 */
	public static class Points
	{
		public boolean isShowdown;
		public int tilePts;
		public int rowPts;
		public int colPts;
		public int diagPts;
		public int clogPts;

		/** Completed line counts. Only meaningful off Showdown, where the site
		 *  labels the rows "Rows (x2)" rather than naming a flat bonus. */
		public int rows;
		public int cols;
		public int diags;

		public int total;

		/** Per-tile contributions, already sorted high to low by the server. */
		public List<PointsTile> tiles = new ArrayList<>();

		/** True when any bonus applies; the site only prints a Total line then. */
		public boolean hasBonuses()
		{
			return rowPts != 0 || colPts != 0 || diagPts != 0 || clogPts != 0;
		}
	}

	public static class PointsTile
	{
		public String label;
		public int pts;
	}

	public static class TeamSummary
	{
		/**
		 * The event's own team number, 1-8, which names the organiser's team badge.
		 * 0 on an event that never set one.
		 */
		public int number;

		public String name;
		public int points;
		public List<String> players = new ArrayList<>();

		@Override
		public String toString()
		{
			// The team dropdown renders this directly.
			return name == null ? "" : name;
		}
	}

	public static class BoardTile
	{
		public int pos;
		public int id;
		public String title;
		public String description;
		public String img;
		public int points;
		public int goal;
		public boolean done;
		public boolean empty;
		public List<String> tags = new ArrayList<>();
		public List<TileItem> items = new ArrayList<>();
		public List<TileGroup> groups = new ArrayList<>();

		/**
		 * Which rules score this tile: "showdown" (accumulate a score toward a
		 * threshold) or "blackout" (collect the items listed). Sent per tile because a
		 * Hybrid board mixes both. Null from a server that predates it, where the
		 * board's format decides for every tile — see {@link #scoredByShowdownRules}.
		 */
		public String tileType;

		/** The named board area this tile belongs to; "" when it has none, and always
		 *  "" while the event has regions switched off. */
		public String region;

		/** Showdown only; absent on Hybrid, whose showdown tiles do not tier. */
		public Integer tier;
		public Integer maxTier;

		/**
		 * Showdown only, and only on tiles that actually have uimTags: the raw tile
		 * score and the points one tier costs. The server derives {@link #tier} from
		 * exactly these two, so the "x% to T3" line below cannot disagree with it.
		 */
		public Double score;
		public Integer tierThreshold;

		/**
		 * What each boss/skill/activity/item on this tile is worth — the site's
		 * "Point Rates" card. Present on every Showdown tile; it is small and static.
		 */
		public List<Rate> rates = new ArrayList<>();

		/**
		 * Who earned what, the site's "Player Progress" card. Only sent for the tile
		 * the panel asked about (`&tile=N`), because it is the bulk of the response —
		 * so this is empty on every other tile and null-safe to iterate.
		 */
		public List<PlayerProgress> players = new ArrayList<>();

		/**
		 * Set only on XP tiles, which are judged on snapshot XP rather than items —
		 * their checklist is always empty, so without this the panel had nothing to
		 * show for them.
		 */
		public XpProgress xp;

		/**
		 * Everything on this tile a player could submit, with the array indices
		 * submit_item.php expects. Empty when the tile takes no submissions, which is
		 * what hides the submit controls.
		 */
		public List<SubmitOption> submitItems = new ArrayList<>();

		public boolean hasTierProgress()
		{
			return score != null && tierThreshold != null && tierThreshold > 0;
		}

		/**
		 * Whether this tile is judged on an accumulated score rather than on a
		 * checklist of items — which decides what collected/required are counted in,
		 * and so whether they may be labelled "items".
		 *
		 * Asked of the tile and the board together: on a Showdown board every tile is
		 * scored this way and older servers send no tileType at all, while on a Hybrid
		 * board only the tiles authored as showdown tiles are.
		 */
		public boolean scoredByShowdownRules(Board board)
		{
			return (board != null && board.isShowdown()) || "showdown".equals(tileType);
		}

		/**
		 * Progress as the server counted it, with its unit — "3/4 items" for a tile
		 * that collects drops, "600/500 pts" for one that accumulates a score. Getting
		 * this wrong reads as a tile needing six hundred of something.
		 */
		public String progressTextWithUnit(Board board)
		{
			return progressText() + (scoredByShowdownRules(board) ? " pts" : " items");
		}

		/** True once every tier is banked — the site labels this "MAX". */
		public boolean tierMaxed()
		{
			return tier != null && maxTier != null && maxTier > 0 && tier >= maxTier;
		}

		/** Score at which the next tier lands. Meaningless once maxed. */
		public double nextTierAt()
		{
			if (!hasTierProgress())
			{
				return 0;
			}
			return ((tier == null ? 0 : tier) + 1) * (double) tierThreshold;
		}

		/** 0..100 toward the next tier. */
		public double pctToNextTier()
		{
			final double target = nextTierAt();
			if (target <= 0)
			{
				return 0;
			}
			return Math.max(0, Math.min(100, (score / target) * 100.0));
		}

		public String displayTitle()
		{
			if (empty)
			{
				return "";
			}
			return (title == null || title.isEmpty()) ? ("Tile " + pos) : title;
		}

		/**
		 * The counts the server judged completion on. Not derivable here: a group
		 * capped by "any 1" contributes once no matter how many options it lists, so
		 * summing the checklist gives a different — and wrong — answer. A Godsword
		 * tile needing 3 shards plus any 1 of 5 hilts is 1/4 done, not 1/8.
		 *
		 * Null when talking to a server that predates these fields; callers fall back
		 * to the checklist sums below.
		 */
		public Integer collected;
		public Integer required;

		public boolean hasCounts()
		{
			return collected != null && required != null && required > 0;
		}

		/** 0..100 for a non-Showdown tile, from the server's counts where available. */
		public double itemsPercent()
		{
			if (done)
			{
				return 100;
			}
			if (hasCounts())
			{
				return Math.max(0, Math.min(100, (collected * 100.0) / required));
			}
			final int needed = neededCount();
			return needed <= 0 ? 0 : Math.max(0, Math.min(100, (approvedCount() * 100.0) / needed));
		}

		/** "1/4" as the server counts it, else the checklist's own tally. */
		public String progressText()
		{
			return hasCounts()
				? (collected + "/" + required)
				: (approvedCount() + "/" + neededCount());
		}

		/** Approved / needed across the flat item list, for the grid's progress line. */
		public int approvedCount()
		{
			int n = 0;
			for (TileItem i : items)
			{
				if (i.approved)
				{
					n++;
				}
			}
			for (TileGroup g : groups)
			{
				for (TileGroupItem gi : g.items)
				{
					if (gi.approved)
					{
						n++;
					}
				}
			}
			return n;
		}

		public int neededCount()
		{
			// One entry per slot, so the slots are the count.
			int n = items.size();
			for (TileGroup g : groups)
			{
				n += g.items.size();
			}
			return n;
		}
	}

	/** An XP tile's team total against its goal, and who contributed. */
	public static class XpProgress
	{
		public String skill;
		public double collected;
		public double required;
		public List<XpPlayer> players = new ArrayList<>();

		/** 0..100 toward the goal. */
		public double percent()
		{
			if (required <= 0)
			{
				return 0;
			}
			return Math.max(0, Math.min(100, (collected / required) * 100.0));
		}
	}

	public static class XpPlayer
	{
		public String name;
		public double xp;
	}

	/** One submittable item, carrying the indices the submission endpoint wants. */
	public static class SubmitOption
	{
		public String label;
		public String type;
		public int index;
		public int groupIndex;
		public int itemIndex;
		public boolean approved;
		public boolean pending;

		/** Already approved or awaiting review, so there is nothing to file again. */
		public boolean alreadySubmitted()
		{
			return approved || pending;
		}

		@Override
		public String toString()
		{
			// The submit dropdown renders this directly.
			return label == null ? "" : label;
		}
	}

	/** One row of the point-rates table: "Boss: Nex" / "6000 pts/KC". */
	public static class Rate
	{
		public String label;
		public String rate;
	}

	public static class PlayerProgress
	{
		public String name;
		public double points;
		public List<ProgressLine> lines = new ArrayList<>();
	}

	/** One earning: "Nex", +163, "KC", 978000 points. */
	public static class ProgressLine
	{
		public String label;
		public double gain;
		public String unit;
		public double points;

		/** Items are counted ("x3"); everything else is a gain ("+163 KC"). */
		public String gainText()
		{
			if ("item".equals(unit))
			{
				return "x" + Text.compact(gain);
			}
			return "+" + Text.compact(gain) + (unit == null || unit.isEmpty() ? "" : " " + unit);
		}
	}

	/**
	 * One checklist slot. A tile wanting four of something sends four of these, as
	 * the website's modal lists them, so each can name whoever filled it.
	 */
	public static class TileItem
	{
		public String label;

		@JsonAdapter(LenientBoolean.class)
		public boolean approved;

		/** Submitted and awaiting review — the site's "?" state. */
		@JsonAdapter(LenientBoolean.class)
		public boolean pending;
		/** Who sent it. Empty when nobody has, or the submission recorded no player. */
		public String player;
	}

	/**
	 * Reads a boolean that may arrive as a number.
	 *
	 * These fields were counts before they were flags, and a site that has not been
	 * updated yet still sends "approved": 1. Gson refuses a number for a boolean and
	 * aborts the whole response, so without this a plugin update that reached players
	 * before the site did would take the board out entirely rather than showing a
	 * slightly wrong checklist.
	 */
	static class LenientBoolean extends TypeAdapter<Boolean>
	{
		@Override
		public Boolean read(JsonReader in) throws IOException
		{
			switch (in.peek())
			{
				case BOOLEAN:
					return in.nextBoolean();
				case NUMBER:
					return in.nextInt() > 0;
				case STRING:
					return Boolean.parseBoolean(in.nextString());
				case NULL:
					in.nextNull();
					return false;
				default:
					in.skipValue();
					return false;
			}
		}

		@Override
		public void write(JsonWriter out, Boolean value) throws IOException
		{
			out.value(value != null && value);
		}
	}

	public static class TileGroup
	{
		public String name;
		public String logic;
		public List<TileGroupItem> items = new ArrayList<>();
	}

	public static class TileGroupItem
	{
		public String label;
		public boolean approved;
		/** Submitted and awaiting review — the site's "?" state. */
		public boolean pending;
		/** Who sent it. Empty when nobody has, or the submission recorded no player. */
		public String player;
	}
}
