package raptor.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang.StringUtils;

import raptor.chess.Game;
import raptor.chess.Move;
import raptor.chess.Result;
import raptor.chess.Variant;
import raptor.chess.pgn.PgnHeader;
import raptor.chess.pgn.TimeTakenForMove;
import raptor.connector.Connector;

/**
 * Keeps track of statistics on games played by the user.
 */
public class PlayingStatisticsService {
	private static final PlayingStatisticsService singletonInstance = new PlayingStatisticsService();

	public static class VsStats {
		int gamesPlayed;
		double totalScore;
	}

	protected class PlayingGameResult {
		double score = -1.0;
		Variant variant;
		String opponentName;
		int opponentRating = -1;
	}

	protected Map<Connector, List<PlayingGameResult>> connectorToResultsList = new HashMap<Connector, List<PlayingGameResult>>();

	public static PlayingStatisticsService getInstance() {
		return singletonInstance;
	}

	private PlayingStatisticsService() {

	}

	public void addStatisticsForGameEnd(Connector connector, Game game,
			boolean isUserWhite) {
		if (game.getVariant() != Variant.bughouse
				&& game.getVariant() != Variant.fischerRandomBughouse) {
			double score = -1.0;

			if (game.getResult() == Result.BLACK_WON) {
				score = isUserWhite ? 0.0 : 1.0;
			} else if (game.getResult() == Result.WHITE_WON) {
				score = isUserWhite ? 1.0 : 0.0;
			} else if (game.getResult() == Result.DRAW) {
				score = .5;
			}

			if (score != -1.0) {
				PlayingGameResult gameResult = new PlayingGameResult();
				gameResult.score = score;
				gameResult.variant = game.getVariant();
				gameResult.opponentName = isUserWhite ? game
						.getHeader(PgnHeader.Black) : game
						.getHeader(PgnHeader.White);
				String opponentRating = isUserWhite ? game
						.getHeader(PgnHeader.BlackElo) : game
						.getHeader(PgnHeader.WhiteElo);
				if (opponentRating.contains("E")) {
					gameResult.opponentRating = 1600;

				} else if (StringUtils.isNumeric(opponentRating)) {
					gameResult.opponentRating = Integer
							.parseInt(opponentRating);
				}

				List<PlayingGameResult> results = connectorToResultsList
						.get(connector);
				if (results == null) {
					results = new ArrayList<PlayingGameResult>(20);
					connectorToResultsList.put(connector, results);
				}
				results.add(gameResult);
			}
		}
	}

	/**
	 * Returns -1 if there is currently no performance rating.
	 */
	public int getPreformanceRating(Connector connector, Variant variant) {
		int n = 0;
		int totalScore = 0;

		List<PlayingGameResult> results = connectorToResultsList.get(connector);

		if (results != null) {
			for (PlayingGameResult gameResult : results) {
				if (gameResult.variant == variant && gameResult.score != -1.0
						&& gameResult.opponentRating >= 0) {
					n++;
					if (gameResult.score == .5) {
						totalScore += gameResult.opponentRating;
					} else if (gameResult.score == 0) {
						totalScore += Math.max(gameResult.opponentRating - 400,
								100);
					} else {
						totalScore += gameResult.opponentRating + 400;
					}
				}
			}
		}

		if (n > 0) {
			return totalScore / n;
		} else {
			return -1;
		}
	}

	/**
	 * Returns null if there are no game statistics, otherwise a string
	 * containing the statistics for the game.
	 * 
	 * Assumes addStatisticsForGameEnd has already been invoked.
	 */
	public String getStatisticsString(Connector connector, Game game,
			boolean isUserWhite) {
		Result result = game.getResult();
		if (game.isInState(Game.PLAYING_STATE) && result == Result.BLACK_WON
				|| result == Result.WHITE_WON || result == Result.DRAW
				&& game.getHalfMoveCount() > 1) {
			int playerPremoves = 0;
			int opponentPremoves = 0;

			int totalPlayerMovesTime = 0;
			int totalOpponentMovesTime = 0;

			int numPlayerMoves = 0;
			int numOpponentMoves = 0;

			int movesProcessed = 0;

			for (Move move : game.getMoveList().asArray()) {
				if (movesProcessed > 1) {
					TimeTakenForMove[] moveTime = move.getTimeTakenForMove();
					if (moveTime != null && moveTime.length > 0) {
						if (isUserWhite && move.isWhitesMove() || !isUserWhite
								&& !move.isWhitesMove()) {
							if (moveTime[0].getMilliseconds() <= 100) {
								playerPremoves++;
							}
							numPlayerMoves++;
							totalPlayerMovesTime += moveTime[0]
									.getMilliseconds();

						} else {
							if (moveTime[0].getMilliseconds() <= 100) {
								opponentPremoves++;
							}
							numOpponentMoves++;
							totalOpponentMovesTime += moveTime[0]
									.getMilliseconds();
						}
					}
				}
				movesProcessed++;
			}

			int performanceRating = getPreformanceRating(connector, game
					.getVariant());
			String opponentName = isUserWhite ? game.getHeader(PgnHeader.Black)
					: game.getHeader(PgnHeader.White);
			VsStats vsStats = getVsStats(connector, opponentName);

			String yourAvgMoveTime = numPlayerMoves == 0 ? "UNKNOWN"
					: new BigDecimal(totalPlayerMovesTime / numPlayerMoves
							/ 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP)
							.toString()
							+ "sec";

			String oppAvgMoveTime = numOpponentMoves == 0 ? "UNKNOWN"
					: new BigDecimal(totalOpponentMovesTime / numOpponentMoves
							/ 1000.0).setScale(1, BigDecimal.ROUND_HALF_UP)
							.toString()
							+ "sec";

			String performance = performanceRating != -1 ? "Performance("
					+ game.getVariant().name() + "): " + performanceRating
					+ "\n" : "";

			String vsStat = vsStats.gamesPlayed > 0 ? "Series(" + opponentName
					+ "): " + vsStats.totalScore + "/" + vsStats.gamesPlayed
					+ "\n" : "";

			return performance + vsStat + "Average Move Time(you/opponent): "
					+ yourAvgMoveTime + "/" + oppAvgMoveTime + ""
					+ "\nPremoves(you/opp): " + playerPremoves + "/"
					+ opponentPremoves;

		}
		return null;
	}

	/**
	 * Returns the vs statistics for the specified player.
	 * 
	 * @param playerName
	 *            The player name.
	 */
	public VsStats getVsStats(Connector connector, String playerName) {
		VsStats result = new VsStats();
		List<PlayingGameResult> results = connectorToResultsList.get(connector);
		if (results != null) {
			for (PlayingGameResult gameResult : results) {
				if (gameResult.opponentName.equalsIgnoreCase(playerName)) {
					if (gameResult.score != -1.0) {
						result.gamesPlayed++;
						result.totalScore += gameResult.score;
					}
				}
			}
		}
		return result;
	}
}