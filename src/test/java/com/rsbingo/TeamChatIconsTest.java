package com.rsbingo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Name handling for the chat team icons.
 *
 * The icon itself needs a running client, but everything that decides *where* it
 * goes and *who* it belongs to is plain string work — and that is where this can
 * go wrong quietly: a mismatched name shows nothing, and a badly placed tag eats
 * the client's own icons.
 */
public class TeamChatIconsTest
{
	/**
	 * The client puts account-type icons in the name itself. Ours has to go after
	 * them: inserting at position 0 is harmless, but replacing the string or
	 * writing before an unclosed tag would drop an ironman's badge.
	 */
	@Test
	public void theIconGoesAfterTheClientsOwn()
	{
		assertEquals("<img=2><img=7>Hurt Dark",
			TeamChatIcons.insertIcon("<img=2>Hurt Dark", 7));

		// A name with no icons of its own.
		assertEquals("<img=7>Hurt Dark",
			TeamChatIcons.insertIcon("Hurt Dark", 7));

		// Several already present — a rank icon and an account type, say.
		assertEquals("<img=2><img=5><img=7>Hurt Dark",
			TeamChatIcons.insertIcon("<img=2><img=5>Hurt Dark", 7));
	}

	/** A malformed tag must not make us lose the rest of the name. */
	@Test
	public void anUnclosedTagIsLeftAlone()
	{
		assertEquals("<img=7><img=2 Hurt Dark",
			TeamChatIcons.insertIcon("<img=2 Hurt Dark", 7));
	}

	@Test
	public void matchingIgnoresMarkupCaseAndNonBreakingSpaces()
	{
		// The client reports names with U+00A0 where the site stores a plain space.
		assertEquals("hurt dark", TeamChatIcons.normalise("Hurt Dark"));
		assertEquals("hurt dark", TeamChatIcons.normalise("  HURT DARK  "));

		// Icons have to come off before the name is compared, or nobody matches.
		assertEquals("Hurt Dark", TeamChatIcons.stripTags("<img=2>Hurt Dark"));
		assertEquals("Hurt Dark", TeamChatIcons.stripTags("<col=ff0000>Hurt Dark</col>"));
		assertEquals("Hurt Dark", TeamChatIcons.stripTags("Hurt Dark"));
	}

	/** The two run together on every incoming line, so pin the pair. */
	@Test
	public void aClientNameReducesToTheRosterSpelling()
	{
		assertEquals("hurt dark",
			TeamChatIcons.normalise(TeamChatIcons.stripTags("<img=2>Hurt Dark")));
	}

	/**
	 * A drop broadcast names the player in the text, so the icon is placed by
	 * searching for the name. It has to be the whole name: an item, a boss or a
	 * longer username that merely contains someone's name must not be marked.
	 */
	@Test
	public void onlyAWholeNameIsMarkedInABroadcast()
	{
		final String line = "hurt dark received a drop: twisted bow";
		assertTrue(TeamChatIcons.standsAlone(line, 0, "hurt dark".length()));

		// Broadcasts often open with a colour tag, so '>' counts as a boundary.
		final String coloured = "<col=ff0000>hurt dark received a drop: twisted bow";
		assertTrue(TeamChatIcons.standsAlone(coloured, coloured.indexOf("hurt dark"), "hurt dark".length()));

		// Part of a longer name: "hurt dark" inside "shurt darkly".
		assertFalse(TeamChatIcons.standsAlone("shurt darkly got a drop", 1, "hurt dark".length()));

		// Followed by more of the same word.
		final String longer = "hurt darkness received a drop";
		assertFalse(TeamChatIcons.standsAlone(longer, 0, "hurt dark".length()));

		// At the very end of the line.
		final String trailing = "the drop went to hurt dark";
		assertTrue(TeamChatIcons.standsAlone(trailing, trailing.indexOf("hurt dark"), "hurt dark".length()));
	}

	/**
	 * The colours are the organiser's team badges, not something derived, so they
	 * are pinned: a team's colour has to mean the same thing to every player in the
	 * event whatever theme they run, and changing one silently would re-label teams
	 * mid-event.
	 */
	@Test
	public void teamColoursAreTheBadgeColours()
	{
		final int[] expected = {
			0x417CD5, 0xD83D63, 0xFFAF52, 0x4BC78B,
			0xF96D42, 0x9265E7, 0x89585A, 0xEBDBF6,
		};
		for (int i = 0; i < expected.length; i++)
		{
			assertEquals("team " + (i + 1), expected[i], TeamChatIcons.teamColour(i).getRGB() & 0xFFFFFF);
		}

		// A ninth team wraps rather than running out of colours.
		assertEquals(TeamChatIcons.teamColour(0), TeamChatIcons.teamColour(8));
		assertEquals(TeamChatIcons.teamColour(1), TeamChatIcons.teamColour(9));
	}

	/**
	 * Changing event has to take the previous event's marks off the lines already
	 * in the chat — but only ours. The client's account-type and rank icons live
	 * in the same string and must survive untouched.
	 */
	@Test
	public void onlyOurOwnIconsAreStripped()
	{
		final java.util.Set<Integer> ours = new java.util.HashSet<>(java.util.Arrays.asList(30, 31));

		// Ours goes, the client's ironman badge stays.
		assertEquals("<img=2>Hurt Dark",
			TeamChatIcons.stripIcons("<img=2><img=30>Hurt Dark", ours));

		// Several of ours, anywhere in the string.
		assertEquals("<img=2>Hurt Dark said hello",
			TeamChatIcons.stripIcons("<img=30><img=2>Hurt <img=31>Dark said hello", ours));

		// Nothing of ours present.
		assertEquals("<img=2>Hurt Dark",
			TeamChatIcons.stripIcons("<img=2>Hurt Dark", ours));

		// Not a tag at all, and an unclosed one.
		assertEquals("2 < 3 and 4 > 1", TeamChatIcons.stripIcons("2 < 3 and 4 > 1", ours));
		assertEquals("<img=30 Hurt Dark", TeamChatIcons.stripIcons("<img=30 Hurt Dark", ours));

		// Nothing registered yet: leave everything alone.
		assertEquals("<img=30>Hurt Dark",
			TeamChatIcons.stripIcons("<img=30>Hurt Dark", java.util.Collections.emptySet()));
	}
}
