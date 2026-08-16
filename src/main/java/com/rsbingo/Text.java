package com.rsbingo;

/**
 * Formatting shared by the board and the tile view.
 *
 * Tile titles, descriptions and item names are typed by event organisers and get
 * rendered through Swing's HTML labels, so they are escaped in exactly one place
 * rather than in each panel that happens to display them.
 */
final class Text
{
	private Text()
	{
	}

	static String escape(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;")
			.replace("'", "&#39;");
	}

	/**
	 * How long an event has left, from the ISO timestamps the site stores
	 * ({@code 2026-08-05T17:00:00.000Z}). Returns null when the dates are missing or
	 * unparseable, which is the signal to hide the line rather than show "Ended".
	 */
	static String eventCountdown(String startIso, String endIso, java.time.Instant now)
	{
		final java.time.Instant start = parseInstant(startIso);
		final java.time.Instant end = parseInstant(endIso);

		if (start != null && now.isBefore(start))
		{
			return "Starts in " + duration(start.toEpochMilli() - now.toEpochMilli());
		}
		if (end == null)
		{
			return null;
		}
		if (now.isAfter(end))
		{
			return "Ended";
		}
		return "Ends in " + duration(end.toEpochMilli() - now.toEpochMilli());
	}

	/**
	 * Whether now sits inside the event's window. Missing or unparseable dates are
	 * treated as "no limit at that end" — the same reading eventCountdown takes, and
	 * the server enforces the real answer regardless.
	 */
	static boolean withinEventWindow(String startIso, String endIso, java.time.Instant now)
	{
		final java.time.Instant start = parseInstant(startIso);
		if (start != null && now.isBefore(start))
		{
			return false;
		}
		final java.time.Instant end = parseInstant(endIso);
		return end == null || !now.isAfter(end);
	}

	/** True when the event's start is still ahead of us. */
	static boolean startsAfter(String startIso, java.time.Instant now)
	{
		final java.time.Instant start = parseInstant(startIso);
		return start != null && now.isBefore(start);
	}

	/** Two units at most: "2d 14h", "3h 20m", "12m", "<1m". */
	static String duration(long millis)
	{
		if (millis < 60_000)
		{
			return "<1m";
		}

		final long minutes = millis / 60_000;
		final long days = minutes / 1440;
		final long hours = (minutes % 1440) / 60;
		final long mins = minutes % 60;

		if (days > 0)
		{
			return hours > 0 ? (days + "d " + hours + "h") : (days + "d");
		}
		if (hours > 0)
		{
			return mins > 0 ? (hours + "h " + mins + "m") : (hours + "h");
		}
		return mins + "m";
	}

	private static java.time.Instant parseInstant(String iso)
	{
		if (iso == null || iso.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return java.time.Instant.parse(iso.trim());
		}
		catch (java.time.format.DateTimeParseException e)
		{
			return null;
		}
	}

	/**
	 * Compact numbers, matching game.html's modal: 1.2M, 4.5K, 850. Showdown scores
	 * run into the millions and the panel is 225px wide.
	 */
	static String compact(double n)
	{
		final double abs = Math.abs(n);
		if (abs >= 1_000_000)
		{
			return trimZero(n / 1_000_000) + "M";
		}
		if (abs >= 1_000)
		{
			return trimZero(n / 1_000) + "K";
		}
		return String.valueOf(Math.round(n));
	}

	private static String trimZero(double v)
	{
		final String s = String.format("%.1f", v);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}
}
