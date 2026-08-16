package com.rsbingo;

import java.util.ArrayList;
import java.util.List;

/**
 * The shape of plugin_board.php's response. Field names match the JSON exactly so
 * Gson can map it with no annotations.
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
		 * The event's codeword, sent only once the site itself would show it. Stamped
		 * onto submission screenshots and used to authorise the submission, so the
		 * player never has to type it anywhere. Null while still withheld.
		 */
		public String codeword;

		/** Only present when a team was requested. */
		public String team;
		public List<BoardTile> board = new ArrayList<>();
		public boolean hiddenTiles;

		public boolean isShowdown()
		{
			return "Showdown".equals(format);
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

	public static class TeamSummary
	{
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

		/** Showdown only; 0 on other formats. */
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
				n += i.approved;
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
			int n = 0;
			for (TileItem i : items)
			{
				n += i.need;
			}
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

	public static class TileItem
	{
		public String label;
		public int need;
		public int approved;
		/** Submitted and awaiting review — the site's "?" state. */
		public int pending;
		/** Who sent it. Empty when nobody has, or the submission recorded no player. */
		public String player;
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
