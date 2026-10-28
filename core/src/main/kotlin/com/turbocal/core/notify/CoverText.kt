package com.turbocal.core.notify

/*
 * Text helpers for the Kyocera outer display (sub-LCD) card, ported unchanged
 * from MatChat's CoverScreenNotifier (guide: docs/platform.md "Outer display").
 * The card draws one line beside an icon, so long text scrolls by reposting.
 */

/** Whether the cover turning on should bring the card back: something is still
 *  owed, and the last showing has run its course (our own post also wakes the
 *  cover; this keeps that from looping). */
fun shouldReshow(hasPending: Boolean, msSinceLastShown: Long, cardDurationMs: Long): Boolean =
    hasPending && msSinceLastShown >= cardDurationMs

/** [text] unchanged up to [maxChars], otherwise cut to fit including "…". */
fun coverDisplayText(text: String, maxChars: Int): String =
    if (text.length <= maxChars) text else text.take(maxChars - 1).trimEnd() + "…"

/** The cover's one line: [time], then [text] — e.g. "9:25 AM · Dentist in 5 min". */
fun coverLine(time: String, text: String, maxChars: Int): String =
    "$time · ${coverDisplayText(text, maxChars)}"

/** Frames to post so a scroll runs at least [minDurationMs] and stops only at
 *  the end of a pass: the fewest whole passes that cover the minimum, at least one. */
fun tickerFrameCount(passLength: Int, stepMs: Long, minDurationMs: Long): Int {
    val minFrames = ((minDurationMs + stepMs - 1) / stepMs).toInt()
    val passes = maxOf(1, (minFrames + passLength - 1) / passLength)
    return passes * passLength
}

/** One scroll pass over [text], [window] characters per frame: the start held
 *  [pauseSteps] frames, [step] characters per frame to the end (the last frame
 *  lands exactly on the end), the end held [pauseSteps] frames. Never wraps
 *  the end into the start. Text that fits is a single frame. */
fun tickerFrames(text: String, window: Int, pauseSteps: Int, step: Int = 1): List<String> {
    if (text.length <= window) return listOf(text)
    val lastStart = text.length - window
    val moving = (step until lastStart step step).toList()
    val starts = List(pauseSteps) { 0 } + moving + List(pauseSteps) { lastStart }
    return starts.map { text.substring(it, it + window) }
}
