package com.rsbingo;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Tile artwork, fetched once and kept.
 *
 * The board re-renders on every refresh and every card switch, so the two things
 * that matter here are that a repaint never triggers a second fetch of the same
 * image, and that a board full of art can't grow without bound. Images are scaled
 * down at decode time — nothing draws them larger than a panel cell — which caps
 * a full 200-entry cache at roughly 7MB rather than the tens of megabytes the
 * originals would take.
 */
@Slf4j
@Singleton
class TileImageCache
{
	/** Nothing is drawn bigger than this, so nothing is kept bigger than this. */
	private static final int MAX_DIM = 96;
	private static final int MAX_ENTRIES = 200;

	/** Access-ordered LRU. Synchronized: fetches complete on OkHttp's threads. */
	private final Map<String, BufferedImage> cache = Collections.synchronizedMap(
		new LinkedHashMap<String, BufferedImage>(32, 0.75f, true)
		{
			@Override
			protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest)
			{
				return size() > MAX_ENTRIES;
			}
		});

	/** In-flight and permanently-failed URLs: both stop repaints re-queueing work. */
	private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
	private final Set<String> failed = ConcurrentHashMap.newKeySet();

	private final OkHttpClient httpClient;

	// Package-private rather than private so PanelPreview can build one directly.
	@Inject
	TileImageCache(OkHttpClient httpClient)
	{
		this.httpClient = httpClient;
	}

	/**
	 * @param onLoad run on the EDT if and when the image is available — immediately
	 *               for a cache hit, later for a fetch, never on failure.
	 */
	void get(String url, Consumer<BufferedImage> onLoad)
	{
		if (url == null || url.isEmpty() || failed.contains(url))
		{
			return;
		}

		final BufferedImage hit = cache.get(url);
		if (hit != null)
		{
			onLoad.accept(hit);
			return;
		}

		// A board of 100 cells paints repeatedly; only the first paint should fetch.
		if (!inFlight.add(url))
		{
			return;
		}

		final HttpUrl parsed = HttpUrl.parse(url);
		if (parsed == null)
		{
			inFlight.remove(url);
			failed.add(url);
			return;
		}

		httpClient.newCall(new Request.Builder().url(parsed).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				inFlight.remove(url);
				log.debug("rs-bingo tile image failed: {}", url, e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					if (!res.isSuccessful() || res.body() == null)
					{
						// A missing image stays missing; don't ask again.
						failed.add(url);
						return;
					}

					final BufferedImage decoded = decodeScaled(res.body().byteStream());
					if (decoded == null)
					{
						failed.add(url);
						return;
					}

					cache.put(url, decoded);
					SwingUtilities.invokeLater(() -> onLoad.accept(decoded));
				}
				catch (IOException e)
				{
					log.debug("rs-bingo tile image unreadable: {}", url, e);
				}
				finally
				{
					inFlight.remove(url);
				}
			}
		});
	}

	private static BufferedImage decodeScaled(InputStream in) throws IOException
	{
		final BufferedImage src = ImageIO.read(in);
		if (src == null)
		{
			return null;
		}

		final int w = src.getWidth();
		final int h = src.getHeight();
		if (w <= 0 || h <= 0)
		{
			return null;
		}
		if (w <= MAX_DIM && h <= MAX_DIM)
		{
			return src;
		}

		final double scale = Math.min(MAX_DIM / (double) w, MAX_DIM / (double) h);
		final int tw = Math.max(1, (int) Math.round(w * scale));
		final int th = Math.max(1, (int) Math.round(h * scale));

		final BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		try
		{
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(src, 0, 0, tw, th, null);
		}
		finally
		{
			g.dispose();
		}
		return out;
	}

	/**
	 * Turns a tile's stored image reference into a URL, following the same rule the
	 * website uses (game.html's {@code safeImgSrc}). There are three shapes and they
	 * are not interchangeable:
	 *
	 * <ul>
	 *   <li>an absolute {@code http(s)} URL — an organiser pasted a link; use it</li>
	 *   <li>{@code events/<id>/images/x.png} — a per-event upload, already a full
	 *       path from the site root</li>
	 *   <li>anything else ({@code OFM055/x.png}, {@code default/x.png}) — a shared
	 *       gallery image, which lives under {@code images/}</li>
	 * </ul>
	 *
	 * Treating that third case as root-relative is what left most of a board blank:
	 * the paths 404 and the cells fall back to a plain fill.
	 *
	 * WebP is sent the long way round, through the site's converter — see
	 * {@link #CONVERTER}.
	 */
	static String resolve(String baseUrl, String img)
	{
		if (img == null || img.trim().isEmpty())
		{
			return null;
		}

		final String path = img.trim();
		if (path.startsWith("http://") || path.startsWith("https://"))
		{
			// Somebody else's server; we can neither convert it nor should we ask ours
			// to fetch it on our behalf. A WebP link from off-site stays undecodable.
			return path;
		}

		String base = baseUrl == null ? "" : baseUrl.trim();
		while (base.endsWith("/"))
		{
			base = base.substring(0, base.length() - 1);
		}

		if (path.startsWith("events/"))
		{
			return isWebp(path) ? convert(base, path) : base + "/" + path;
		}

		// Gallery images: the stored reference is relative to images/, and the
		// segments are rebuilt rather than concatenated because the filenames
		// contain spaces and quotes.
		final StringBuilder plain = new StringBuilder("images");
		final StringBuilder encoded = new StringBuilder(base).append("/images");
		for (String segment : path.replace('\\', '/').split("/"))
		{
			if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment))
			{
				continue;
			}
			plain.append('/').append(segment);
			encoded.append('/').append(encodeSegment(segment));
		}

		return isWebp(path) ? convert(base, plain.toString()) : encoded.toString();
	}

	/**
	 * The site endpoint that re-encodes WebP as PNG.
	 *
	 * The JDK has no WebP reader, so those tiles decoded to null and drew as empty
	 * cells. Carrying a Java decoder meant shipping a third-party dependency, which
	 * the Plugin Hub bundles, hash-verifies and explicitly asks submitters to avoid;
	 * converting on the server keeps this plugin dependency-free. If the site cannot
	 * convert either, it returns the original and the tile is blank as it was before.
	 */
	private static final String CONVERTER = "/plugin_img.php?src=";

	private static boolean isWebp(String path)
	{
		return path.length() > 5 && path.regionMatches(true, path.length() - 5, ".webp", 0, 5);
	}

	/** Whole path in one query value, so the slashes are encoded along with it. */
	private static String convert(String base, String sitePath)
	{
		return base + CONVERTER + encodeSegment(sitePath);
	}

	/** Percent-encodes one path segment; gallery filenames contain spaces and quotes. */
	private static String encodeSegment(String segment)
	{
		try
		{
			return URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			return segment;
		}
	}
}
