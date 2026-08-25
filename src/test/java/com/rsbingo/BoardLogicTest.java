package com.rsbingo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The plugin deliberately does no scoring — the server does that. What it does do
 * is derive display values from the server's numbers, and those are worth pinning:
 * a wrong "% to next tier" is the kind of thing nobody notices until an event is
 * live and a team is arguing about it.
 *
 * Figures below are real ones from event NSM930.
 */
public class BoardLogicTest
{
	private static BoardModels.BoardTile showdownTile(int tier, int maxTier, double score, int threshold)
	{
		final BoardModels.BoardTile t = new BoardModels.BoardTile();
		t.pos = 1;
		t.tier = tier;
		t.maxTier = maxTier;
		t.score = score;
		t.tierThreshold = threshold;
		return t;
	}

	@Test
	public void tierProgressMatchesTheServersTier()
	{
		// pos 1: 3,076,134.4 pts at 1M per tier -> tier 3, 76.9% of the way to T4.
		final BoardModels.BoardTile t = showdownTile(3, 5, 3_076_134.4, 1_000_000);

		assertTrue(t.hasTierProgress());
		assertFalse(t.tierMaxed());
		assertEquals(4_000_000.0, t.nextTierAt(), 0.001);
		assertEquals(76.9, t.pctToNextTier(), 0.05);
	}

	@Test
	public void maxedTileIsFlaggedRatherThanShowingAnUnreachableTarget()
	{
		// pos 6: 6.17M with 5 tiers of 1M all banked. The next threshold would be
		// 6M, which it has passed — so the panel must say MAX, not "102% to T6".
		final BoardModels.BoardTile t = showdownTile(5, 5, 6_165_465.2, 1_000_000);

		assertTrue(t.tierMaxed());
	}

	/**
	 * On a Showdown board {@code done} reports the tile's item checklist, which moves
	 * independently of the tier. Real NSM930 tiles sit on both sides of this: Demonic
	 * is T3 with done=true, while Multi Mayhem is a maxed T5 with done=false. Anything
	 * that treats one as the other mislabels both.
	 */
	@Test
	public void tierCompletionIsIndependentOfItemCompletion()
	{
		final BoardModels.BoardTile demonic = showdownTile(3, 5, 3_055_031, 1_000_000);
		demonic.done = true;
		assertFalse("done items must not imply a maxed tier", demonic.tierMaxed());

		final BoardModels.BoardTile multiMayhem = showdownTile(5, 5, 6_165_465, 1_000_000);
		multiMayhem.done = false;
		assertTrue("a maxed tier must not depend on the item checklist", multiMayhem.tierMaxed());
	}

	@Test
	public void percentIsClampedAndNeverExceedsFull()
	{
		final BoardModels.BoardTile over = showdownTile(2, 5, 9_999_999, 1_000_000);
		assertEquals(100.0, over.pctToNextTier(), 0.001);

		final BoardModels.BoardTile none = showdownTile(0, 5, 0, 1_000_000);
		assertEquals(0.0, none.pctToNextTier(), 0.001);
	}

	@Test
	public void nonShowdownTilesHaveNoTierProgress()
	{
		final BoardModels.BoardTile t = new BoardModels.BoardTile();
		t.done = true;

		assertFalse(t.hasTierProgress());
		assertFalse(t.tierMaxed());
		assertEquals(0.0, t.pctToNextTier(), 0.001);
	}

	/**
	 * A Godsword needs three shards plus any one of five hilts — four things. The
	 * checklist lists eight rows, so summing it reports 1/8 (12.5%) for a tile that
	 * is really 1/4 (25%) done. The server caps a group by its logic and sends the
	 * counts it judged completion on; the panel must use those.
	 */
	@Test
	public void groupedTilesUseTheServersCountsNotTheChecklistLength()
	{
		final BoardModels.BoardTile godsword = new BoardModels.BoardTile();
		for (String shard : new String[]{"Godsword Shard 1", "Godsword Shard 2", "Godsword Shard 3"})
		{
			final BoardModels.TileItem item = new BoardModels.TileItem();
			item.label = shard;
			item.need = 1;
			godsword.items.add(item);
		}
		final BoardModels.TileGroup hilts = new BoardModels.TileGroup();
		hilts.name = "Hilt";
		hilts.logic = "any1";
		for (String hilt : new String[]{"Armadyl", "Bandos", "Saradomin", "Zamorak", "Zaros"})
		{
			final BoardModels.TileGroupItem gi = new BoardModels.TileGroupItem();
			gi.label = hilt;
			gi.approved = "Bandos".equals(hilt);
			hilts.items.add(gi);
		}
		godsword.groups.add(hilts);

		// What summing the checklist would have said, and why it is wrong.
		assertEquals(8, godsword.neededCount());
		assertEquals(1, godsword.approvedCount());

		godsword.collected = 1;
		godsword.required = 4;

		assertTrue(godsword.hasCounts());
		assertEquals("1/4", godsword.progressText());
		assertEquals(25.0, godsword.itemsPercent(), 0.001);
	}

	@Test
	public void tilesFallBackToTheChecklistWhenTheServerSendsNoCounts()
	{
		// An older deployment omits these fields; the panel still has to draw a bar.
		final BoardModels.BoardTile tile = new BoardModels.BoardTile();
		final BoardModels.TileItem item = new BoardModels.TileItem();
		item.label = "Twisted Bow";
		item.need = 4;
		item.approved = 1;
		tile.items.add(item);

		assertFalse(tile.hasCounts());
		assertEquals("1/4", tile.progressText());
		assertEquals(25.0, tile.itemsPercent(), 0.001);
	}

	@Test
	public void completedTilesReadAsFullRegardlessOfCounts()
	{
		final BoardModels.BoardTile tile = new BoardModels.BoardTile();
		tile.done = true;
		assertEquals(100.0, tile.itemsPercent(), 0.001);
	}

	@Test
	public void itemCountsSpanFlatItemsAndGroups()
	{
		final BoardModels.BoardTile t = new BoardModels.BoardTile();

		final BoardModels.TileItem dwh = new BoardModels.TileItem();
		dwh.label = "Dragon Warhammer";
		dwh.need = 2;
		dwh.approved = 1;
		t.items.add(dwh);

		final BoardModels.TileGroup g = new BoardModels.TileGroup();
		g.name = "Barrows";
		final BoardModels.TileGroupItem a = new BoardModels.TileGroupItem();
		a.approved = true;
		final BoardModels.TileGroupItem b = new BoardModels.TileGroupItem();
		b.approved = false;
		g.items.add(a);
		g.items.add(b);
		t.groups.add(g);

		assertEquals(2, t.approvedCount());
		assertEquals(4, t.neededCount());
	}

	@Test
	public void hiddenTilesFallBackToTheirPosition()
	{
		final BoardModels.BoardTile t = new BoardModels.BoardTile();
		t.pos = 7;
		t.title = "";

		assertEquals("Tile 7", t.displayTitle());
	}

	@Test
	public void perEventUploadsResolveFromTheSiteRoot()
	{
		assertEquals("https://rs-bingo.com/events/NSM930/images/x.png",
			TileImageCache.resolve("https://rs-bingo.com", "events/NSM930/images/x.png"));

		// A trailing slash on the configured site URL must not double up.
		assertEquals("https://rs-bingo.com/events/NSM930/images/x.png",
			TileImageCache.resolve("https://rs-bingo.com/", "events/NSM930/images/x.png"));
	}

	/**
	 * Anything that isn't an events/ upload is a shared gallery image living under
	 * images/. Resolving these from the site root instead left most of OFM055's
	 * board blank — every path 404'd.
	 */
	@Test
	public void galleryImagesResolveUnderTheImagesDirectory()
	{
		assertEquals("https://rs-bingo.com/images/OFM055/Champion_s_cape.png",
			TileImageCache.resolve("https://rs-bingo.com", "OFM055/Champion_s_cape.png"));

		assertEquals("https://rs-bingo.com/images/default/raids_fit.png",
			TileImageCache.resolve("https://rs-bingo.com", "default/raids_fit.png"));

		// Filenames really do contain spaces; a raw space is not a valid URL.
		assertEquals("https://rs-bingo.com/images/gallery/Abyssal%20whip.png",
			TileImageCache.resolve("https://rs-bingo.com", "gallery/Abyssal whip.png"));

		assertEquals("https://rs-bingo.com/images/a/b.png",
			TileImageCache.resolve("https://rs-bingo.com", "a\\b.png"));

		// "." and ".." are stripped, not resolved — so a path can never climb out of
		// images/. Matches the site, which filters those segments the same way.
		assertEquals("https://rs-bingo.com/images/a/a/b.png",
			TileImageCache.resolve("https://rs-bingo.com", "./a/../a/b.png"));
	}

	/**
	 * WebP has no reader in the JDK, so those tiles are fetched through the site's
	 * converter instead. The whole path goes in one query value — encoded slashes
	 * included — because it is a parameter, not a path.
	 */
	@Test
	public void webpIsRoutedThroughTheConverter()
	{
		assertEquals("https://rs-bingo.com/plugin_img.php?src=events%2FNSM930%2Fimages%2Fx.webp",
			TileImageCache.resolve("https://rs-bingo.com", "events/NSM930/images/x.webp"));

		assertEquals("https://rs-bingo.com/plugin_img.php?src=images%2Fdefault%2Fraids_fit.webp",
			TileImageCache.resolve("https://rs-bingo.com", "default/raids_fit.webp"));

		// The converter is told the cleaned path, so "." and ".." cannot reach it.
		assertEquals("https://rs-bingo.com/plugin_img.php?src=images%2Fa%2Fa%2Fb.webp",
			TileImageCache.resolve("https://rs-bingo.com", "./a/../a/b.webp"));

		// Case is the file system's business, not ours.
		assertEquals("https://rs-bingo.com/plugin_img.php?src=images%2Fg%2FA.WEBP",
			TileImageCache.resolve("https://rs-bingo.com", "g/A.WEBP"));

		// Formats the client can already decode keep going straight to the file.
		assertEquals("https://rs-bingo.com/images/g/a.png",
			TileImageCache.resolve("https://rs-bingo.com", "g/a.png"));

		// Off-site art is refused outright rather than converted — see
		// absoluteImageReferencesAreRefused().
		assertNull(TileImageCache.resolve("https://rs-bingo.com", "https://i.imgur.com/abc.webp"));
	}

	/**
	 * An organiser can paste an absolute link into the tile editor and the website
	 * renders it, but the plugin must not fetch it: the address arrives in an API
	 * response, and a plugin may only contact addresses that are hardcoded or entered
	 * by the user. Returning null leaves the cell on its colour fill.
	 */
	@Test
	public void absoluteImageReferencesAreRefused()
	{
		assertNull(TileImageCache.resolve("https://rs-bingo.com", "https://i.imgur.com/abc.png"));
		assertNull(TileImageCache.resolve("https://rs-bingo.com", "http://i.imgur.com/abc.png"));

		// Not just http(s): any scheme is a way out of the site.
		assertNull(TileImageCache.resolve("https://rs-bingo.com", "file:///etc/passwd"));
		assertNull(TileImageCache.resolve("https://rs-bingo.com", "//evil.example.com/x.png"));

		assertNull(TileImageCache.resolve("https://rs-bingo.com", ""));
		assertNull(TileImageCache.resolve("https://rs-bingo.com", null));
	}

	@Test
	public void scoresAreCompactedTheWayTheSiteDoes()
	{
		assertEquals("3.1M", Text.compact(3_076_134.4));
		assertEquals("5M", Text.compact(5_006_500));
		assertEquals("4.5K", Text.compact(4_500));
		assertEquals("850", Text.compact(850));
		assertEquals("0", Text.compact(0));
	}

	@Test
	public void progressLinesReadAsGainsOrCounts()
	{
		final BoardModels.ProgressLine kc = new BoardModels.ProgressLine();
		kc.label = "Nex";
		kc.gain = 163;
		kc.unit = "KC";
		assertEquals("+163 KC", kc.gainText());

		final BoardModels.ProgressLine xp = new BoardModels.ProgressLine();
		xp.gain = 259408;
		xp.unit = "XP";
		assertEquals("+259.4K XP", xp.gainText());

		// Drops are a count, not a gain — the site writes them as a multiplier.
		final BoardModels.ProgressLine drop = new BoardModels.ProgressLine();
		drop.gain = 1;
		drop.unit = "item";
		assertEquals("x1", drop.gainText());
	}

	@Test
	public void countdownReadsFromTheEventsOwnTimestamps()
	{
		// NSM930's real window.
		final String start = "2026-07-22T17:00:00.000Z";
		final String end = "2026-08-05T17:00:00.000Z";

		assertEquals("Starts in 2d 3h",
			Text.eventCountdown(start, end, java.time.Instant.parse("2026-07-20T14:00:00Z")));
		assertEquals("Ends in 3d 7h",
			Text.eventCountdown(start, end, java.time.Instant.parse("2026-08-02T10:00:00Z")));
		assertEquals("Ended",
			Text.eventCountdown(start, end, java.time.Instant.parse("2026-08-06T10:00:00Z")));

		// Missing or unparseable dates hide the line rather than claiming "Ended".
		assertNull(Text.eventCountdown(null, null, java.time.Instant.now()));
		assertNull(Text.eventCountdown("", "not a date", java.time.Instant.now()));
	}

	/**
	 * Submissions are only open between the two dates. The server enforces this too,
	 * but the panel must not offer a button that is guaranteed to be refused.
	 */
	@Test
	public void submissionsOnlyOpenInsideTheEventWindow()
	{
		final String start = "2026-07-22T17:00:00.000Z";
		final String end = "2026-08-05T17:00:00.000Z";

		assertFalse("before the start",
			Text.withinEventWindow(start, end, java.time.Instant.parse("2026-07-20T14:00:00Z")));
		assertTrue("during",
			Text.withinEventWindow(start, end, java.time.Instant.parse("2026-08-01T10:00:00Z")));
		assertFalse("after the end",
			Text.withinEventWindow(start, end, java.time.Instant.parse("2026-08-06T10:00:00Z")));

		// An event with no dates set is not thereby closed forever.
		assertTrue(Text.withinEventWindow(null, null, java.time.Instant.now()));
	}

	@Test
	public void closedSubmissionsSayWhichEndTheyAreOn()
	{
		final BoardModels.Board board = new BoardModels.Board();
		board.startDate = "2099-01-01T00:00:00.000Z";
		assertEquals("This event has not started yet.", board.submissionsClosedReason());

		board.startDate = "2020-01-01T00:00:00.000Z";
		board.endDate = "2020-02-01T00:00:00.000Z";
		assertEquals("This event has ended.", board.submissionsClosedReason());

		board.endDate = "2099-02-01T00:00:00.000Z";
		assertNull("open events give no reason", board.submissionsClosedReason());
	}

	@Test
	public void durationsCollapseToTwoUnits()
	{
		assertEquals("2d 14h", Text.duration(((2 * 24) + 14) * 3_600_000L));
		assertEquals("2d", Text.duration(2 * 24 * 3_600_000L));
		assertEquals("3h 20m", Text.duration((3 * 3_600_000L) + (20 * 60_000L)));
		assertEquals("12m", Text.duration(12 * 60_000L));
		assertEquals("<1m", Text.duration(30_000L));
	}

	/**
	 * The stamped frame is the whole point of the plugin's submission flow: it is
	 * what a reviewer looks at. It must come back as a decodable PNG the same size as
	 * the captured frame, never a null that silently drops the evidence.
	 */
	@Test
	public void proofScreenshotsEncodeAsPng() throws Exception
	{
		final java.awt.image.BufferedImage frame =
			new java.awt.image.BufferedImage(765, 503, java.awt.image.BufferedImage.TYPE_INT_RGB);

		final byte[] png = ProofShot.stamp(frame, "Hurt Summer Bingo 2026", "codeword123", "NewrockkuOS", "Twisted Bow");

		assertNotNull("a captured frame must always produce proof bytes", png);
		assertTrue(png.length > 0);

		// PNG magic, so this is a real image and not some other encoding.
		assertEquals((byte) 0x89, png[0]);
		assertEquals('P', png[1]);
		assertEquals('N', png[2]);
		assertEquals('G', png[3]);

		final java.awt.image.BufferedImage decoded =
			javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
		assertNotNull(decoded);
		assertEquals(765, decoded.getWidth());
		assertEquals(503, decoded.getHeight());
	}

	@Test
	public void proofScreenshotsSurviveAMissingFrame()
	{
		// requestNextFrameListener can hand back nothing if the client is not drawing.
		assertNull(ProofShot.stamp(null, "Hurt Summer Bingo 2026", "codeword123", "NewrockkuOS", "Twisted Bow"));
	}

	@Test
	public void organiserSuppliedTitlesCannotInjectMarkupIntoHtmlTooltips()
	{
		// Tile titles reach the board's tooltips, which really are HTML.
		assertEquals("&lt;b&gt;bold&lt;/b&gt;", Text.escape("<b>bold</b>"));
		assertEquals("Tom &amp; Jerry", Text.escape("Tom & Jerry"));
		assertEquals("", Text.escape(null));
	}
}
