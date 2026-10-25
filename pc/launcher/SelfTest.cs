using System;
using System.Collections.Generic;
using System.Linq;

namespace KidLock.Launcher
{
    /// <summary>
    /// --selftest: plays made-up hours through WarningRules (no screen, no waiting) and checks that
    /// the warnings come when the phone's would. Prints PASS/FAIL; exit code 1 on any failure.
    /// </summary>
    static class SelfTest
    {
        const long T0 = 1_800_000_000_000;
        const long Min = 60_000, Sec = 1_000;

        /// <summary>An app on for a while: from when (offset) its end is [end] (absolute), or switched off.</summary>
        sealed class Plan
        {
            public readonly List<(long from, string id, long? end)> Changes = new List<(long, string, long?)>();
            public readonly List<(long from, long to)> Locked = new List<(long, long)>();

            public Plan Give(long at, string id, long? end)
            {
                Changes.Add((at, id, end));
                return this;
            }

            public Plan Lock(long from, long to)
            {
                Locked.Add((from, to));
                return this;
            }
        }

        /// <summary>Runs [plan] for [length] in steps of 200 ms; returns "mm:ss what" lines.</summary>
        static List<string> Play(Plan plan, long length)
        {
            var rules = new WarningRules();
            var lines = new List<string>();
            var current = new Dictionary<string, long?>();
            for (long t = 0; t <= length; t += 200)
            {
                foreach (var c in plan.Changes.Where(c => c.from == t)) current[c.id] = c.end;
                long now = T0 + t;
                var ends = current.Where(e => e.Value is long end && end > now).ToDictionary(e => e.Key, e => e.Value.Value);
                bool locked = plan.Locked.Any(l => t >= l.from && t < l.to);
                foreach (var a in rules.Step(now, ends, !locked))
                    if (a.Kind != WarningKind.CountdownTick || a.Seconds % 10 == 0 || a.Seconds <= 1) // keep it short
                        lines.Add($"{t / Min:00}:{t / Sec % 60:00}.{t % Sec / 100} {a}");
            }
            return lines;
        }

        static int failures;

        static void Expect(string name, List<string> got, params string[] want)
        {
            bool ok = got.SequenceEqual(want);
            Console.WriteLine($"{(ok ? "PASS" : "FAIL")}  {name}");
            if (!ok)
            {
                failures++;
                Console.WriteLine("  got:  " + string.Join(" | ", got));
                Console.WriteLine("  want: " + string.Join(" | ", want));
            }
        }

        public static int Run()
        {
            Expect("an hour: 10 and 5 minutes left, countdown, time's up",
                Play(new Plan().Give(0, "roblox", T0 + 60 * Min), 61 * Min),
                "50:00.0 remind roblox 10 min", "50:07.0 hide",
                "55:00.0 remind roblox 5 min", "55:07.0 hide",
                "59:30.0 countdown roblox 30 chime", "59:40.0 tick roblox 20", "59:50.0 tick roblox 10",
                "59:59.0 tick roblox 1", "60:00.0 time's up roblox", "60:03.0 hide");

            Expect("ten minutes given: no \"10 minutes left\"",
                Play(new Plan().Give(0, "roblox", T0 + 10 * Min), 11 * Min),
                "05:00.0 remind roblox 5 min", "05:07.0 hide",
                "09:30.0 countdown roblox 30 chime", "09:40.0 tick roblox 20", "09:50.0 tick roblox 10",
                "09:59.0 tick roblox 1", "10:00.0 time's up roblox", "10:03.0 hide");

            Expect("locked over the 10-minute mark: that reminder is skipped, not shown late",
                Play(new Plan().Give(0, "roblox", T0 + 60 * Min).Lock(49 * Min, 51 * Min + 30 * Sec), 56 * Min),
                "55:00.0 remind roblox 5 min", "55:07.0 hide");

            Expect("unlocked within a minute of a reminder: it still comes",
                Play(new Plan().Give(0, "roblox", T0 + 60 * Min).Lock(49 * Min, 50 * Min + 20 * Sec), 51 * Min),
                "50:20.0 remind roblox 10 min", "50:27.0 hide");

            Expect("more time during the countdown: the countdown goes, later warnings follow the new end",
                Play(new Plan().Give(0, "roblox", T0 + 60 * Min).Give(59 * Min + 40 * Sec, "roblox", T0 + 89 * Min + 40 * Sec), 80 * Min),
                "50:00.0 remind roblox 10 min", "50:07.0 hide",
                "55:00.0 remind roblox 5 min", "55:07.0 hide",
                "59:30.0 countdown roblox 30 chime", "59:40.0 hide",
                "79:40.0 remind roblox 10 min", "79:47.0 hide");

            Expect("switched off during the countdown: it goes, no \"Time's up!\"",
                Play(new Plan().Give(0, "roblox", T0 + 60 * Min).Give(59 * Min + 45 * Sec, "roblox", null), 61 * Min),
                "50:00.0 remind roblox 10 min", "50:07.0 hide",
                "55:00.0 remind roblox 5 min", "55:07.0 hide",
                "59:30.0 countdown roblox 30 chime", "59:40.0 tick roblox 20", "59:45.0 hide");

            Expect("started with 20 seconds left: straight into the countdown",
                Play(new Plan().Give(0, "roblox", T0 + 20 * Sec), 25 * Sec),
                "00:00.0 countdown roblox 20 chime", "00:10.0 tick roblox 10", "00:19.0 tick roblox 1",
                "00:20.0 time's up roblox", "00:23.0 hide");

            Expect("two apps: the one that ends first counts down",
                Play(new Plan().Give(0, "roblox", T0 + 2 * Min).Give(0, "chess", T0 + 3 * Min), 3 * Min + 4 * Sec),
                "01:30.0 countdown roblox 30 chime", "01:40.0 tick roblox 20", "01:50.0 tick roblox 10",
                "01:59.0 tick roblox 1", "02:00.0 time's up roblox", "02:03.0 hide",
                "02:30.0 countdown chess 30 chime", "02:40.0 tick chess 20", "02:50.0 tick chess 10",
                "02:59.0 tick chess 1", "03:00.0 time's up chess", "03:03.0 hide");

            Expect("no time limit: no warnings",
                Play(new Plan().Give(0, "roblox", null), 70 * Min));

            Console.WriteLine(failures == 0 ? "all passed" : $"{failures} failed");
            return failures == 0 ? 0 : 1;
        }
    }
}
