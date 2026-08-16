package com.rsbingo;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import java.io.IOException;
import java.util.function.Consumer;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches board state. Read-only: nothing here writes to the event, and no
 * credentials are sent — the event code alone is what grants a view, exactly as it
 * does for the website's board pages.
 */
@Slf4j
@Singleton
public class RsBingoApi
{
	private final OkHttpClient httpClient;
	private final Gson gson;

	// Package-private rather than private so PanelPreview can build one directly.
	@Inject
	RsBingoApi(OkHttpClient httpClient, Gson gson)
	{
		this.httpClient = httpClient;
		this.gson = gson;
	}

	public void fetchBoard(String baseUrl, String eventCode, String team,
						   Consumer<BoardModels.Board> onOk, Consumer<String> onError)
	{
		fetchBoard(baseUrl, eventCode, team, 0, onOk, onError);
	}

	/**
	 * @param team    null or empty for the event summary and team list only.
	 * @param tilePos board position whose player breakdown is wanted, or 0 for none.
	 *                Asking for it here rather than in a second call keeps a refresh
	 *                to one request whether or not a tile is open.
	 * @param onOk    called off the EDT — callers must marshal to Swing themselves.
	 */
	public void fetchBoard(String baseUrl, String eventCode, String team, int tilePos,
						   Consumer<BoardModels.Board> onOk, Consumer<String> onError)
	{
		final HttpUrl parsed = HttpUrl.parse(trimTrailingSlash(baseUrl) + "/plugin_board.php");
		if (parsed == null)
		{
			onError.accept("That site URL doesn't look right.");
			return;
		}

		final HttpUrl.Builder url = parsed.newBuilder().addQueryParameter("eventId", eventCode);
		if (team != null && !team.isEmpty())
		{
			url.addQueryParameter("team", team);
		}
		if (tilePos > 0)
		{
			url.addQueryParameter("tile", Integer.toString(tilePos));
		}

		final Request request = new Request.Builder().url(url.build()).get().build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("rs-bingo board fetch failed", e);
				onError.accept("Could not reach the site.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					final String body = res.body() == null ? "" : res.body().string();

					if (!res.isSuccessful())
					{
						// The endpoint reports its own reasons; surface them rather
						// than a bare status code.
						onError.accept(messageFor(res.code(), body));
						return;
					}

					final BoardModels.Board board = gson.fromJson(body, BoardModels.Board.class);
					if (board == null || board.eventId == null)
					{
						onError.accept("Unexpected response from the site.");
						return;
					}
					onOk.accept(board);
				}
				catch (JsonSyntaxException e)
				{
					onError.accept("Could not read the site's response.");
				}
				catch (IOException e)
				{
					onError.accept("Connection dropped while loading.");
				}
			}
		});
	}

	/**
	 * The site's colour themes. Failure is not surfaced to the user: the panel keeps
	 * the palette it ships with, which is the site's default anyway.
	 */
	public void fetchThemes(String baseUrl, Consumer<BoardModels.ThemeList> onOk)
	{
		final HttpUrl url = HttpUrl.parse(trimTrailingSlash(baseUrl) + "/plugin_themes.php");
		if (url == null)
		{
			return;
		}

		httpClient.newCall(new Request.Builder().url(url).get().build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("rs-bingo theme fetch failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					if (!res.isSuccessful() || res.body() == null)
					{
						return;
					}
					final BoardModels.ThemeList list =
						gson.fromJson(res.body().string(), BoardModels.ThemeList.class);
					if (list != null && !list.themes.isEmpty())
					{
						onOk.accept(list);
					}
				}
				catch (JsonSyntaxException | IOException e)
				{
					log.debug("rs-bingo theme response unreadable", e);
				}
			}
		});
	}

	/**
	 * The events a linked account belongs to.
	 *
	 * The token goes in a header rather than the query string so it stays out of
	 * server access logs and proxy caches.
	 */
	public void fetchMyEvents(String baseUrl, String token,
							  Consumer<BoardModels.EventList> onOk, Consumer<String> onError)
	{
		final HttpUrl url = HttpUrl.parse(trimTrailingSlash(baseUrl) + "/plugin_events.php");
		if (url == null || token == null || token.trim().isEmpty())
		{
			return;
		}

		final Request request = new Request.Builder()
			.url(url)
			.header("X-Bingo-Token", token.trim())
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("rs-bingo event list failed", e);
				onError.accept("Could not reach the site.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					final String body = res.body() == null ? "" : res.body().string();
					if (!res.isSuccessful())
					{
						onError.accept(messageFor(res.code(), body));
						return;
					}
					final BoardModels.EventList list = gson.fromJson(body, BoardModels.EventList.class);
					if (list != null)
					{
						onOk.accept(list);
					}
				}
				catch (JsonSyntaxException e)
				{
					onError.accept("Could not read the site's response.");
				}
				catch (IOException e)
				{
					onError.accept("Connection dropped while loading events.");
				}
			}
		});
	}

	/**
	 * File a submission. The plugin's only write.
	 *
	 * Authorised by roster membership: the server checks the named player is on the
	 * named team of this event. No codeword or session is sent — see the note at the
	 * top of submit_item.php. Everything files as pending, so this cannot score
	 * anything on its own.
	 */
	public void submitItem(String baseUrl, String eventId, String team,
						   String player, int tilePos, int tileRefId, BoardModels.SubmitOption option,
						   byte[] png, Runnable onOk, Consumer<String> onError)
	{
		final HttpUrl url = HttpUrl.parse(trimTrailingSlash(baseUrl) + "/submit_item.php");
		if (url == null)
		{
			onError.accept("That site URL doesn't look right.");
			return;
		}

		// Matches what game.html posts, indices included, so a plugin submission is
		// indistinguishable from a website one once it lands.
		final String itemData = gson.toJson(new SubmissionItem(option));

		final RequestBody body = new MultipartBody.Builder()
			.setType(MultipartBody.FORM)
			.addFormDataPart("eventId", eventId)
			// Routing, not a credential: it tells the endpoint this is a client
			// submission with no browser session to present.
			.addFormDataPart("source", "plugin")
			.addFormDataPart("team", team)
			.addFormDataPart("player", player)
			.addFormDataPart("tileId", Integer.toString(tilePos))
			.addFormDataPart("tileRefId", Integer.toString(tileRefId))
			.addFormDataPart("itemData", itemData)
			.addFormDataPart("image", "proof.png",
				RequestBody.create(MediaType.parse("image/png"), png))
			.build();

		httpClient.newCall(new Request.Builder().url(url).post(body).build()).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("rs-bingo submission failed", e);
				onError.accept("Could not reach the site.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response res = response)
				{
					final String text = res.body() == null ? "" : res.body().string();
					final SubmitResult result = gson.fromJson(text, SubmitResult.class);

					if (res.isSuccessful() && result != null && result.success)
					{
						onOk.run();
						return;
					}

					// The endpoint explains itself (wrong codeword, not on the team,
					// event not started); pass that through rather than a status code.
					String message = result == null ? null : (result.message != null ? result.message : result.error);
					if (message == null || message.isEmpty())
					{
						message = "Submission failed (" + res.code() + ").";
					}
					onError.accept(message);
				}
				catch (JsonSyntaxException e)
				{
					onError.accept("Could not read the site's response.");
				}
				catch (IOException e)
				{
					onError.accept("Connection dropped while submitting.");
				}
			}
		});
	}

	/** The itemData payload, shaped exactly as game.html's submission builds it. */
	private static class SubmissionItem
	{
		final String label;
		final int index;
		final String type;
		final int groupIndex;
		final int groupItemIndex;

		SubmissionItem(BoardModels.SubmitOption option)
		{
			this.label = option.label;
			this.index = option.index;
			this.type = option.type;
			this.groupIndex = option.groupIndex;
			this.groupItemIndex = option.itemIndex;
		}
	}

	private static class SubmitResult
	{
		boolean success;
		String message;
		String error;
	}

	private String messageFor(int code, String body)
	{
		try
		{
			final ApiError err = gson.fromJson(body, ApiError.class);
			if (err != null && err.error != null && !err.error.isEmpty())
			{
				return err.error;
			}
		}
		catch (JsonSyntaxException ignored)
		{
			// fall through to the generic wording
		}

		if (code == 401)
		{
			return "Account token not accepted - re-link from the site.";
		}
		if (code == 404)
		{
			return "No event with that code.";
		}
		if (code == 429)
		{
			return "Too many requests - try again shortly.";
		}
		return "Site returned an error (" + code + ").";
	}

	private static String trimTrailingSlash(String s)
	{
		final String v = s == null ? "" : s.trim();
		return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
	}

	private static class ApiError
	{
		String error;
	}
}
