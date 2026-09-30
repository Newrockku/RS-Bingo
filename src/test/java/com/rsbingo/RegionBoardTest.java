package com.rsbingo;

import com.google.gson.Gson;
import java.awt.Color;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Pins the contract for tile regions - named areas outlined on the board.
 *
 * The fixture is a real plugin_board.php response for a 5x5 Hybrid event with
 * three regions:
 *   Varrock     squares 1, 2, 6      (an L)
 *   Wilderness  squares 9, 10, 14, 15 (a 2x2 block)
 *   Zanaris     square 22            (alone)
 *
 * The one thing here that is easy to get wrong, and silently: a region's colour is
 * its index in {@link BoardModels.Board#regions}, and that list is ordered by the
 * event's tile *definitions*. The panel only ever receives tiles by board position,
 * so it cannot rebuild that order itself - which is why the server sends it. Order
 * it any other way and the panel paints a region a different colour from the one the
 * website draws around the same tiles.
 */
public class RegionBoardTest
{
	private static BoardModels.Board sample()
	{
		final InputStream in = RegionBoardTest.class.getResourceAsStream("/board_regions_sample.json");
		assertNotNull("board_regions_sample.json missing from test resources", in);
		return new Gson().fromJson(
			new InputStreamReader(in, StandardCharsets.UTF_8), BoardModels.Board.class);
	}

	@Test
	public void regionsArriveInTheServersOrder()
	{
		final BoardModels.Board b = sample();

		assertTrue(b.hasRegions());
		assertEquals(java.util.Arrays.asList("Varrock", "Wilderness", "Zanaris"), b.regions);

		assertEquals(0, b.regionIndex("Varrock"));
		assertEquals(1, b.regionIndex("Wilderness"));
		assertEquals(2, b.regionIndex("Zanaris"));
		assertEquals("a name that is not a region", -1, b.regionIndex("Lumbridge"));
		assertEquals("no region", -1, b.regionIndex(""));
		assertEquals("null is not a region", -1, b.regionIndex(null));
	}

	@Test
	public void tilesReportTheRegionTheySitIn()
	{
		final BoardModels.Board b = sample();

		assertEquals("Varrock", b.regionAt(1));
		assertEquals("Varrock", b.regionAt(2));
		assertEquals("Varrock", b.regionAt(6));
		assertEquals("Wilderness", b.regionAt(9));
		assertEquals("Wilderness", b.regionAt(15));
		assertEquals("Zanaris", b.regionAt(22));

		// Squares in no region, and squares off the board, both answer "".
		assertEquals("", b.regionAt(3));
		assertEquals("", b.regionAt(25));
		assertEquals("", b.regionAt(999));
	}

	@Test
	public void eachRegionGetsItsOwnColour()
	{
		final BoardModels.Board b = sample();

		final Color varrock = Brand.regionColor(b.regionIndex("Varrock"));
		final Color wilderness = Brand.regionColor(b.regionIndex("Wilderness"));
		final Color zanaris = Brand.regionColor(b.regionIndex("Zanaris"));

		assertNotEquals(varrock, wilderness);
		assertNotEquals(wilderness, zanaris);
		assertNotEquals(varrock, zanaris);

		// Stable: the same region asked twice is the same colour.
		assertEquals(varrock, Brand.regionColor(b.regionIndex("Varrock")));
	}

	@Test
	public void regionColoursMatchTheWebsitesFormula()
	{
		// The website resolves hsl(calc(var(--accent-h) + index * 137.508) 70% 60%) in
		// CSS. These are the values a browser actually computes for that rule, read off
		// the page rather than worked out here - the panel has to match what a player
		// is looking at on the board beside it, and a shade of drift is invisible in
		// code and obvious on screen.
		//
		// Note --accent-h is a whole number: themes.js rounds it, so the panel rounds
		// the accent's hue the same way before rotating. Skipping that put index 1 one
		// step off green.
		Brand.applyPalette(java.util.Collections.singletonMap("--accent", "#d8a830"));

		assertEquals(new Color(224, 184, 82), Brand.regionColor(0));
		assertEquals(new Color(82, 223, 224), Brand.regionColor(1));
		assertEquals(new Color(224, 82, 182), Brand.regionColor(2));
	}

	@Test
	public void regionColoursFollowTheTheme()
	{
		Brand.applyPalette(java.util.Collections.singletonMap("--accent", "#d8a830"));
		final Color gold = Brand.regionColor(0);

		Brand.applyPalette(java.util.Collections.singletonMap("--accent", "#ff6090"));
		final Color berry = Brand.regionColor(0);

		assertNotEquals("a region must re-colour with the theme", gold, berry);
		// Berry's accent hue is 342. Browser-measured, like the values above.
		assertEquals(new Color(224, 82, 124), berry);
		assertEquals(new Color(83, 224, 82), Brand.regionColor(1));
		assertEquals(new Color(122, 82, 224), Brand.regionColor(2));

		// Leave the palette as the rest of the suite expects to find it.
		Brand.applyPalette(java.util.Collections.singletonMap("--accent", "#d8a830"));
	}

	@Test
	public void anUnknownRegionFallsBackRatherThanThrowing()
	{
		// regionIndex returns -1 for a tile with no region, and every caller passes
		// that straight through. It must produce a colour, not an exception.
		assertEquals(Brand.TEXT_DIM, Brand.regionColor(-1));
	}

	@Test
	public void aBoardWithRegionsOffCarriesNone()
	{
		// What the panel receives when the event has regions switched off: an empty
		// list and no region on any tile, which is what stands the feature down without
		// the panel needing to know the setting exists.
		final BoardModels.Board off = new BoardModels.Board();
		assertFalse(off.hasRegions());
		assertEquals(-1, off.regionIndex("Varrock"));
		assertEquals("", off.regionAt(1));
	}

	@Test
	public void regionsAreIndependentOfHowATileScores()
	{
		final BoardModels.Board b = sample();

		// Square 9 is a showdown tile and square 1 a blackout one; both sit in regions.
		// Regions are a layout idea, so they must not track the scoring rules.
		assertEquals("showdown", tileAt(b, 9).tileType);
		assertEquals("Wilderness", tileAt(b, 9).region);
		assertEquals("blackout", tileAt(b, 1).tileType);
		assertEquals("Varrock", tileAt(b, 1).region);
	}

	private static BoardModels.BoardTile tileAt(BoardModels.Board b, int pos)
	{
		for (BoardModels.BoardTile t : b.board)
		{
			if (t.pos == pos)
			{
				return t;
			}
		}
		throw new AssertionError("no tile at position " + pos);
	}
}
