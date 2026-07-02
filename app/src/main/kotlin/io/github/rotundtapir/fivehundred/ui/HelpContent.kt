// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
package io.github.rotundtapir.fivehundred.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.rotundtapir.cardkit.core.Rank
import io.github.rotundtapir.cardkit.core.Suit
import io.github.rotundtapir.cardkit.core.SuitedCard
import io.github.rotundtapir.cardkit.ui.PlayingCard
import io.github.rotundtapir.fivehundred.engine.Bid
import io.github.rotundtapir.fivehundred.engine.ScoreSchedule
import io.github.rotundtapir.fivehundred.engine.Trump

// ------------------------------------------------------------------------------------------------
// Walkthrough (home screen → "How to play")
// ------------------------------------------------------------------------------------------------

private data class WalkthroughPage(val title: String, val body: String, val showBowers: Boolean = false)

private val walkthroughPages = listOf(
    WalkthroughPage(
        "Welcome to 500",
        "500 is a trick-taking card game. You and the seat opposite you are a team (at 6 players, " +
            "every second seat; at 2 players it's every player for themselves).\n\n" +
            "Your team wins by being first to +500 points — but you can also LOSE by falling to " +
            "−500, so wild bidding has a price.",
    ),
    WalkthroughPage(
        "The deal",
        "Everyone receives 10 cards, dealt in packets of 3, 4, then 3, with one card set aside to " +
            "the kitty after each round — 3 kitty cards in all.\n\n" +
            "The 4-player deck has 43 cards: no 2s or 3s, no black 4s, plus the Joker. Six-handed " +
            "games add the 11s, 12s and red 13s (63 cards).",
    ),
    WalkthroughPage(
        "Bidding",
        "Starting left of the dealer, players bid the number of tricks their team will win (6–10) " +
            "and the trump suit — or no trumps. Each bid must outrank the last; suits rank " +
            "♠ ♣ ♦ ♥ NT from lowest to highest. Passing drops you out of the auction.\n\n" +
            "Misère (win NO tricks) and Open Misère are special bids — see the full rules for " +
            "where they slot in. If everyone passes, the hand is thrown in and redealt.",
    ),
    WalkthroughPage(
        "The kitty",
        "Whoever wins the auction becomes the declarer, picks up the 3 kitty cards, and discards " +
            "any 3 cards face down. Choose your discards to strengthen the trump suit you named.",
    ),
    WalkthroughPage(
        "Trick play",
        "The declarer leads the first trick. You must follow the led suit if you can; when you " +
            "can't, play anything — including a trump, which beats every plain card.\n\n" +
            "The two highest trumps are the JACKS: the Jack of trumps (right bower) and the Jack " +
            "of the same-colour suit (left bower — it counts as a trump, not its printed suit). " +
            "The Joker beats everything.",
        showBowers = true,
    ),
    WalkthroughPage(
        "Scoring",
        "Make your contract and your team scores its value; fail and you lose that value. The " +
            "defending team scores 10 points per trick they take either way.\n\n" +
            "Winning every one of the 10 tricks is worth at least 250. First team to +500 on " +
            "their own contract wins; hit −500 and you lose \"out the back door\".",
    ),
    WalkthroughPage(
        "Around the table",
        "During bidding each opponent's latest call is shown under their name. The kitty stays " +
            "visible in the centre until the auction ends, the completed trick lingers with its " +
            "winner named, and after every hand a summary shows the points.\n\n" +
            "Tap \"Sorted\" above your hand to toggle sorting, and use the ⚙ menu for speeds and " +
            "house rules. Good luck!",
    ),
)

/** A short paged introduction to the game, opened from the home screen. */
@Composable
fun WalkthroughDialog(onDismiss: () -> Unit) {
    var page by remember { mutableStateOf(0) }
    val last = walkthroughPages.lastIndex
    val current = walkthroughPages[page]

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(current.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.heightIn(min = 180.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(current.body)
                        if (current.showBowers) BowerExample()
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    repeat(walkthroughPages.size) { i ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = if (i == page) 1f else 0.3f),
                                    CircleShape,
                                ),
                        )
                    }
                }
            }
        },
        dismissButton = {
            if (page > 0) {
                TextButton(onClick = { page-- }) { Text("Back") }
            } else {
                TextButton(onClick = onDismiss) { Text("Skip") }
            }
        },
        confirmButton = {
            if (page < last) {
                TextButton(onClick = { page++ }, modifier = Modifier.testTag("walkthroughNext")) {
                    Text("Next")
                }
            } else {
                TextButton(onClick = onDismiss, modifier = Modifier.testTag("walkthroughDone")) {
                    Text("Done")
                }
            }
        },
    )
}

/** Hearts-trump example: the Joker and both bowers, strongest first. */
@Composable
private fun BowerExample() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PlayingCard(io.github.rotundtapir.cardkit.core.Joker, width = 52.dp)
            PlayingCard(SuitedCard(Rank.JACK, Suit.HEARTS), width = 52.dp)
            PlayingCard(SuitedCard(Rank.JACK, Suit.DIAMONDS), width = 52.dp)
        }
        Spacer(Modifier.height(4.dp))
        Text("Hearts trump: Joker, right bower, left bower", style = MaterialTheme.typography.labelSmall)
    }
}

// ------------------------------------------------------------------------------------------------
// Rules reference (settings → "Help")
// ------------------------------------------------------------------------------------------------

/** The comprehensive rules of 500 as implemented, opened from the settings dialog. */
@Composable
fun RulesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rules of 500") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                RuleSection(
                    "Objective",
                    "500 is a partnership trick-taking game. The first side to reach +500 points " +
                        "by making its own contract wins the game. A side that falls to −500 " +
                        "points loses immediately (\"out the back door\"). Points scored by the " +
                        "defenders alone never win the game — you must bid and make a contract.",
                )
                RuleSection(
                    "Players, teams and decks",
                    "• 4 players (standard): two teams of two, partners opposite. 43-card deck — " +
                        "A to 4 in the red suits, A to 5 in the black suits, plus one Joker.\n" +
                        "• 6 players: two teams of three, alternating seats. 63-card deck — the " +
                        "full 52 plus 11s and 12s of every suit, the red 13s, and the Joker. The " +
                        "11/12/13 rank above the 10 and below the Jack.\n" +
                        "• 2 players: no partners. The 43-card deck is used; after both hands and " +
                        "the kitty are dealt the remaining 20 cards are out of play, face down.",
                )
                RuleSection(
                    "The deal",
                    "Each player receives 10 cards, dealt in packets of 3, 4, then 3. One card is " +
                        "dealt to the kitty after each packet round, making a 3-card kitty. The " +
                        "deal rotates clockwise between hands. If every player passes the auction, " +
                        "the hand is thrown in and the next dealer deals.",
                )
                RuleSection(
                    "Bidding",
                    "Bidding starts left of the dealer and proceeds clockwise. A bid names a " +
                        "number of tricks (6–10) and a trump suit or no trumps; each bid must " +
                        "outrank the previous one. Once you pass you are out of the auction. The " +
                        "auction ends when only the highest bidder remains.\n\n" +
                        "Bids rank by point value (the Avondale schedule):",
                )
                BidTable()
                RuleSection(
                    "",
                    "Misère (win no tricks, played at no trumps, worth 250) ranks between 8♠ and " +
                        "8♣, but may only be called once the bidding has reached seven tricks — " +
                        "a bid of 7♠ or higher. Open Misère is the highest bid of all (500), " +
                        "gated the same way: the declarer's hand is exposed to the other players " +
                        "once play begins.\n\n" +
                        "On a Misère the declarer plays alone: their partner (both teammates at " +
                        "6 players) sits the hand out.",
                )
                RuleSection(
                    "The kitty",
                    "The auction winner (the declarer) takes the 3 kitty cards into hand and " +
                        "discards any 3 cards face down. The discards are out of play and score " +
                        "for nobody.",
                )
                RuleSection(
                    "Play",
                    "The declarer leads the first trick; the winner of each trick leads the next. " +
                        "You must follow the suit that was led if you hold one; otherwise you may " +
                        "play anything, including a trump. The highest trump wins the trick, or " +
                        "the highest card of the led suit if no trump was played.",
                )
                RuleSection(
                    "Trumps, bowers and the Joker",
                    "In a suit contract the trump order, highest first, is:\n" +
                        "Joker · right bower (Jack of trumps) · left bower (Jack of the other " +
                        "same-colour suit) · A · K · Q · (13 · 12 · 11) · 10 …\n\n" +
                        "The left bower counts as a trump, not as its printed suit — it must " +
                        "follow trumps, and cannot be played to follow its printed suit.\n\n" +
                        "At no trumps the Joker is the only trump and wins any trick it is played " +
                        "to. When the Joker is led at no trumps the other players may play " +
                        "anything.",
                )
                RuleSection(
                    "Scoring",
                    "If the declarer's side takes at least the bid number of tricks it scores the " +
                        "contract's value; otherwise it loses that value. Taking all 10 tricks is " +
                        "worth a minimum of 250. The defenders score 10 points per trick they " +
                        "take, made or not — except against a Misère, where the defenders score " +
                        "nothing.\n\n" +
                        "A Misère scores only if the declarer takes no tricks at all.",
                )
                RuleSection(
                    "Winning and losing",
                    "The game ends when the declaring side makes a contract that takes it to " +
                        "+500 or beyond — that side wins. If either side's score reaches −500, " +
                        "that side loses immediately and the other side wins.",
                )
                RuleSection(
                    "House rules",
                    "The ⚙ settings menu can disable Misère bids and/or no-trump bids for new " +
                        "games. Disabling no trumps does not affect Misère, which is still played " +
                        "without trumps.",
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("rulesClose")) { Text("Close") }
        },
    )
}

@Composable
private fun RuleSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (title.isNotEmpty()) {
            Text(title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium)
    }
}

/** The Avondale bid values, generated from the engine's actual schedule so it can never drift. */
@Composable
private fun BidTable() {
    val schedule = ScoreSchedule.Avondale
    val trumps = listOf(Trump.SPADES, Trump.CLUBS, Trump.DIAMONDS, Trump.HEARTS, Trump.NO_TRUMP)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth()) {
            TableCell("")
            trumps.forEach { TableCell(it.symbol, bold = true) }
        }
        for (level in 6..10) {
            Row(Modifier.fillMaxWidth()) {
                TableCell("$level", bold = true)
                trumps.forEach { trump ->
                    TableCell("${schedule.value(Bid.Named(level, trump))}")
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            TableCell("Misère ${schedule.value(Bid.Misere)} · Open Misère ${schedule.value(Bid.OpenMisere)}", span = 6)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TableCell(text: String, bold: Boolean = false, span: Int = 1) {
    Text(
        text,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.weight(span.toFloat()),
    )
}
