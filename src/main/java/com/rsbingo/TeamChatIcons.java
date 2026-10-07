package com.rsbingo;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.game.ChatIconManager;

/**
 * Team colours beside the names of event participants in chat.
 *
 * Who is on which team is otherwise invisible in game: the panel knows the roster,
 * but a line of clan chat does not say whether the person speaking is on your team
 * or racing you. This marks every participant's name with their team's colour,
 * using the same rotation the board uses for regions so a colour means the same
 * thing in both places.
 */
@Singleton
class TeamChatIcons
{
	/**
	 * The event's team colours, taken from the organiser's team badges.
	 *
	 * Fixed rather than rotated off the theme accent like the board's regions are:
	 * a team's colour is the same for everyone in the event, so it cannot depend on
	 * which theme each player happens to have chosen. Teams beyond the eighth wrap
	 * round, which is also what the badges do.
	 */
	private static final Color[] TEAM_COLOURS = {
		new Color(0x417CD5), // 1 blue
		new Color(0xD83D63), // 2 crimson
		new Color(0xFFAF52), // 3 amber
		new Color(0x4BC78B), // 4 green
		new Color(0xF96D42), // 5 orange
		new Color(0x9265E7), // 6 purple
		new Color(0x89585A), // 7 maroon
		new Color(0xEBDBF6), // 8 lavender
	};

	/** Chat lines are short; anything larger than this crowds the text. */
	private static final int SIZE = 10;

	/** Above a head there is room, and it is read at a glance rather than up close. */
	private static final int OVERHEAD_SIZE = 14;

	private final ChatIconManager chatIconManager;

	/** Team name -> the id ChatIconManager gave us. Registered once, recoloured in place. */
	private final Map<String, Integer> iconIds = new HashMap<>();

	/** Team name -> its position in the stable ordering, which decides its colour. */
	private final Map<String, Integer> teamOrder = new HashMap<>();

	/** Team name -> the larger swatch drawn over a player, built on first use. */
	private final Map<String, BufferedImage> overheads = new HashMap<>();

	/** Normalised player name -> team name. */
	private volatile Map<String, String> players = Collections.emptyMap();

	@Inject
	TeamChatIcons(ChatIconManager chatIconManager)
	{
		this.chatIconManager = chatIconManager;
	}

	/**
	 * Take the rosters from a board response.
	 *
	 * A team's colour is its own team number — the 1-8 the event assigns, which is
	 * what names the organiser's badges, so Team 1 is the Team 1 colour wherever it
	 * sits in the table. The list arrives sorted by points, so position in it says
	 * nothing; ordering by that would have recoloured both teams the moment one
	 * overtook the other.
	 *
	 * Teams on an older event with no number fall back to name order, which is at
	 * least stable, rather than to the order they arrived in.
	 */
	void setRoster(BoardModels.Board board)
	{
		if (board == null || board.teams.isEmpty())
		{
			return;
		}

		final Map<String, Integer> numbered = new LinkedHashMap<>();
		final List<String> unnumbered = new ArrayList<>();
		for (BoardModels.TeamSummary team : board.teams)
		{
			if (team.name == null || team.name.trim().isEmpty())
			{
				continue;
			}
			final String name = team.name.trim();
			if (team.number >= 1)
			{
				numbered.put(name, team.number - 1);
			}
			else
			{
				unnumbered.add(name);
			}
		}
		unnumbered.sort(String.CASE_INSENSITIVE_ORDER);

		final Map<String, String> byPlayer = new LinkedHashMap<>();
		for (BoardModels.TeamSummary team : board.teams)
		{
			if (team.name == null)
			{
				continue;
			}
			final String teamName = team.name.trim();
			for (String player : team.players)
			{
				final String key = normalise(player);
				if (!key.isEmpty())
				{
					byPlayer.put(key, teamName);
				}
			}
		}

		synchronized (this)
		{
			teamOrder.clear();
			overheads.clear();
			teamOrder.putAll(numbered);
			for (int i = 0; i < unnumbered.size(); i++)
			{
				teamOrder.put(unnumbered.get(i), i);
			}
			applyColours();
		}

		players = byPlayer;
	}

	/** Registers what is missing and recolours what is not, for the current theme. */
	private void applyColours()
	{
		for (Map.Entry<String, Integer> entry : teamOrder.entrySet())
		{
			final BufferedImage image = swatch(teamColour(entry.getValue()));
			final Integer existing = iconIds.get(entry.getKey());
			if (existing == null)
			{
				iconIds.put(entry.getKey(), chatIconManager.registerChatIcon(image));
			}
			else
			{
				// Reusing the id rather than registering again: every registration is
				// permanent for the session, so re-registering on each theme change
				// would grow the client's icon table for as long as the client runs.
				chatIconManager.updateChatIcon(existing, image);
			}
		}
	}

	/**
	 * The name with the sender's team icon added, or null when they are not in the
	 * event and the name should be left exactly as it is.
	 */
	String decorate(String name)
	{
		if (name == null || name.isEmpty())
		{
			return null;
		}

		final String team = players.get(normalise(stripTags(name)));
		if (team == null)
		{
			return null;
		}

		final Integer id;
		synchronized (this)
		{
			id = iconIds.get(team);
		}
		if (id == null)
		{
			return null;
		}

		return insertIcon(name, chatIconManager.chatIconIndex(id));
	}

	/**
	 * Puts the icon after any the client has already put there, so an ironman badge
	 * keeps its place and ours sits between it and the name rather than replacing it.
	 */
	static String insertIcon(String name, int iconIndex)
	{
		int at = 0;
		while (at < name.length() && name.charAt(at) == '<')
		{
			final int close = name.indexOf('>', at);
			if (close < 0)
			{
				break;
			}
			at = close + 1;
		}
		return name.substring(0, at) + "<img=" + iconIndex + ">" + name.substring(at);
	}

	/**
	 * A broadcast with the subject's team icon added, or null to leave it alone.
	 *
	 * A drop broadcast names the player inside the message rather than in the name
	 * field — "Hurt Dark received a drop: Twisted bow" — so the icon has to be
	 * placed in the text. Only a name standing on its own counts, so an item or a
	 * boss that happens to contain someone's name is not marked.
	 */
	String decorateBroadcast(String message)
	{
		if (message == null || message.isEmpty() || players.isEmpty())
		{
			return null;
		}

		// Case and non-breaking spaces are folded without changing any character's
		// position, so an index into this is an index into the original.
		final String haystack = message.replace(' ', ' ').toLowerCase(java.util.Locale.ROOT);

		String bestTeam = null;
		int bestAt = -1;
		int bestLength = 0;

		for (Map.Entry<String, String> entry : players.entrySet())
		{
			final String name = entry.getKey();
			int at = haystack.indexOf(name);
			while (at >= 0)
			{
				if (standsAlone(haystack, at, name.length())
					&& (bestAt < 0 || at < bestAt || (at == bestAt && name.length() > bestLength)))
				{
					bestTeam = entry.getValue();
					bestAt = at;
					bestLength = name.length();
				}
				at = haystack.indexOf(name, at + 1);
			}
		}

		if (bestTeam == null)
		{
			return null;
		}

		final Integer id;
		synchronized (this)
		{
			id = iconIds.get(bestTeam);
		}
		if (id == null)
		{
			return null;
		}

		return message.substring(0, bestAt)
			+ "<img=" + chatIconManager.chatIconIndex(id) + ">"
			+ message.substring(bestAt);
	}

	/**
	 * Whether the match is a whole name rather than part of a longer word. The
	 * character before may also be '>', which is where a colour tag ends.
	 */
	static boolean standsAlone(String text, int at, int length)
	{
		if (at > 0)
		{
			final char before = text.charAt(at - 1);
			if (before != '>' && !Character.isWhitespace(before))
			{
				return false;
			}
		}
		final int after = at + length;
		if (after < text.length())
		{
			final char c = text.charAt(after);
			return !Character.isLetterOrDigit(c);
		}
		return true;
	}

	/**
	 * The swatch to draw above a player's head, or null when they are not in the
	 * event. Larger than the chat one, which is sized to sit inside a line of text.
	 */
	BufferedImage overheadSwatch(String playerName)
	{
		final String team = players.get(normalise(stripTags(playerName == null ? "" : playerName)));
		if (team == null)
		{
			return null;
		}

		synchronized (this)
		{
			final Integer order = teamOrder.get(team);
			if (order == null)
			{
				return null;
			}
			return overheads.computeIfAbsent(team,
				t -> swatch(teamColour(order), OVERHEAD_SIZE));
		}
	}

	/** Drops the client's markup, leaving the name itself. */
	static String stripTags(String name)
	{
		return name.replaceAll("<[^>]*>", "");
	}

	/**
	 * Names are compared case-insensitively and with the client's non-breaking
	 * spaces turned back into ordinary ones — the website stores "Hurt Dark" where
	 * the client reports "Hurt Dark", and they are the same player.
	 */
	static String normalise(String name)
	{
		return name == null ? "" : name.replace(' ', ' ').trim().toLowerCase();
	}

	private static BufferedImage swatch(Color colour)
	{
		return swatch(colour, SIZE);
	}

	/** The badge colour for a team's position in the ordering, wrapping past the eighth. */
	static Color teamColour(int order)
	{
		return TEAM_COLOURS[Math.floorMod(order, TEAM_COLOURS.length)];
	}

	/** A rounded square in the team's colour, outlined so it reads on any background. */
	private static BufferedImage swatch(Color colour, int size)
	{
		final BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = image.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			// An outline, because the same colour has to carry over a bright chat
			// background and a dark scene without picking a second palette.
			g.setColor(new Color(0, 0, 0, 170));
			g.fillRoundRect(0, 0, size - 1, size - 1, size / 3, size / 3);
			g.setColor(colour);
			g.fillRoundRect(1, 1, size - 3, size - 3, size / 4, size / 4);
		}
		finally
		{
			g.dispose();
		}
		return image;
	}
}
