using System.Collections.Generic;
using System.Linq;

namespace KidLock.Launcher
{
    enum WarningKind { Remind, CountdownStart, CountdownTick, TimesUp, Hide }

    /// <summary>One thing for the screen to do: show a reminder, start or tick a countdown, "Time's up!", or hide.</summary>
    struct WarningAction
    {
        public WarningKind Kind;
        public string Id;
        /// <summary>Remind: 10 or 5.</summary>
        public int Minutes;
        /// <summary>Countdown: the number to show.</summary>
        public int Seconds;
        /// <summary>CountdownStart: play the chime (a countdown for a different app than before).</summary>
        public bool Chime;

        public override string ToString() => Kind switch
        {
            WarningKind.Remind => $"remind {Id} {Minutes} min",
            WarningKind.CountdownStart => $"countdown {Id} {Seconds}{(Chime ? " chime" : "")}",
            WarningKind.CountdownTick => $"tick {Id} {Seconds}",
            WarningKind.TimesUp => $"time's up {Id}",
            _ => "hide",
        };
    }

    /// <summary>
    /// When to warn, exactly as on the phone (app/…/kiosk/TimeWarnings.kt): "10 minutes left" and
    /// "5 minutes left" for 7 seconds each, then for the last 30 seconds a countdown, then "Time's up!"
    /// for 3 seconds. A reminder missed by more than a minute is skipped, and so is one that would come
    /// as the time starts (10 minutes given: no "10 minutes left"). Any change of an end time counts as
    /// a new time (the agent already smooths out tiny differences). No screen in here: Step() says what
    /// to do, so it can be tested on its own (--selftest).
    /// </summary>
    sealed class WarningRules
    {
        public static readonly int[] ReminderMinutes = { 10, 5 };
        public const int CountdownSeconds = 30;
        const long Minute = 60_000;
        public const long ReminderShowsMs = 7_000;
        public const long TimesUpShowsMs = 3_000;
        /// <summary>A reminder due this soon after a time was first heard of would only repeat it: skipped.</summary>
        const long JustStartedMs = 30_000;

        /// <summary>When each end time ("id@end") was first heard of.</summary>
        readonly Dictionary<string, long> firstHeard = new Dictionary<string, long>();
        /// <summary>Reminders given or skipped, as "id@end@minutes".</summary>
        readonly HashSet<string> given = new HashSet<string>();

        /// <summary>The app whose countdown is on screen, and when its time is up.</summary>
        string countdownOf;
        long countdownEnd;
        int countdownShown;
        /// <summary>A reminder or "Time's up!" card is on screen until hideAt.</summary>
        bool cardShown;
        long hideAt;

        /// <summary>
        /// What to do now. [ends]: each app on for a while whose time isn't over yet → when it ends.
        /// [canWarn]: false while the PC is locked (any card goes, nothing new comes).
        /// </summary>
        public List<WarningAction> Step(long now, IReadOnlyDictionary<string, long> ends, bool canWarn)
        {
            var actions = new List<WarningAction>();
            var heard = new HashSet<string>(ends.Select(e => e.Key + "@" + e.Value));
            foreach (var key in heard) if (!firstHeard.ContainsKey(key)) firstHeard[key] = now;
            foreach (var key in firstHeard.Keys.Where(k => !heard.Contains(k)).ToList()) firstHeard.Remove(key);
            given.RemoveWhere(key => !heard.Contains(key.Substring(0, key.LastIndexOf('@'))));

            if (!canWarn)
            {
                if (countdownOf != null || cardShown) actions.Add(Hide());
                countdownOf = null;
                cardShown = false;
                return actions;
            }

            // A countdown that is no longer running: its time is up, or a parent changed it.
            if (countdownOf != null && (!ends.TryGetValue(countdownOf, out var end) || end != countdownEnd))
            {
                if (!ends.ContainsKey(countdownOf) && now >= countdownEnd - 1_000)
                {
                    actions.Add(new WarningAction { Kind = WarningKind.TimesUp, Id = countdownOf });
                    cardShown = true;
                    hideAt = now + TimesUpShowsMs;
                }
                else
                {
                    actions.Add(Hide());
                    cardShown = false;
                }
                countdownOf = null;
            }
            if (cardShown && countdownOf == null && now >= hideAt)
            {
                actions.Add(Hide());
                cardShown = false;
            }

            // The last seconds of the app that runs out first.
            if (ends.Count > 0)
            {
                var soonest = ends.OrderBy(e => e.Value).ThenBy(e => e.Key).First();
                long left = soonest.Value - now;
                if (left <= CountdownSeconds * 1000L)
                {
                    int seconds = (int)System.Math.Max(0, (left + 999) / 1000);
                    if (countdownOf != soonest.Key || countdownEnd != soonest.Value)
                    {
                        actions.Add(new WarningAction { Kind = WarningKind.CountdownStart, Id = soonest.Key, Seconds = seconds, Chime = countdownOf != soonest.Key });
                        countdownOf = soonest.Key;
                        countdownEnd = soonest.Value;
                        cardShown = false;
                    }
                    else if (seconds != countdownShown)
                    {
                        actions.Add(new WarningAction { Kind = WarningKind.CountdownTick, Id = soonest.Key, Seconds = seconds });
                    }
                    countdownShown = seconds;
                    return actions;
                }
            }

            foreach (var e in ends.OrderBy(e => e.Key))
            {
                foreach (int minutes in ReminderMinutes)
                {
                    long at = e.Value - minutes * Minute;
                    string key = $"{e.Key}@{e.Value}@{minutes}";
                    if (now < at || given.Contains(key)) continue;
                    given.Add(key);
                    long first = firstHeard.TryGetValue($"{e.Key}@{e.Value}", out var f) ? f : now;
                    // Not more than a minute late, and not as the time starts.
                    if (now - at < Minute && at - first >= JustStartedMs)
                    {
                        actions.Add(new WarningAction { Kind = WarningKind.Remind, Id = e.Key, Minutes = minutes });
                        cardShown = true;
                        hideAt = now + ReminderShowsMs;
                    }
                }
            }
            return actions;
        }

        static WarningAction Hide() => new WarningAction { Kind = WarningKind.Hide };
    }
}
