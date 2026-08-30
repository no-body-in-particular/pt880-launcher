import org.watchlauncher.Route;

import java.lang.reflect.Method;

/**
 * What gets said at a roundabout, and when.
 *
 * A roundabout is the one manoeuvre where the direction says nothing on its own: "at the
 * roundabout" is true of every exit from it. The number is the instruction, and it has to arrive
 * early enough to be counted against - a driver cannot start counting exits after the entry has
 * gone past.
 */
public class RoundaboutTest {

    static int failures = 0;

    static void check(String what, boolean ok, String saw) {
        if (!ok) { System.out.println("FAIL " + what + ": " + saw); failures++; }
        else System.out.println("ok   " + what + " (" + saw + ")");
    }

    public static void main(String[] a) throws Exception {
        Method phrase = Route.class.getDeclaredMethod(
                "phrase", int.class, int.class, String.class, int.class);
        phrase.setAccessible(true);
        Method trigger = Route.class.getDeclaredMethod("triggerAt", int.class, float.class);
        trigger.setAccessible(true);

        // --- the number is what a driver needs -------------------------------------
        String second = (String) phrase.invoke(null, Route.ROUNDABOUT, 300, null, 2);
        check("the exit is spoken", second.contains("second exit"), second);
        check("the distance survives", second.startsWith("in "), second);

        String first = (String) phrase.invoke(null, Route.ROUNDABOUT, 0, null, 1);
        check("first exit", first.contains("first exit"), first);

        // Without a number, say the plain thing rather than invent one: "take the seventh exit"
        // said wrongly is worse than "at the roundabout" said vaguely, because it gets acted on.
        String none = (String) phrase.invoke(null, Route.ROUNDABOUT, 0, null, 0);
        check("no number invented when the server did not say",
              !none.contains("exit") && none.contains("roundabout"), none);

        String far = (String) phrase.invoke(null, Route.ROUNDABOUT, 0, null, 97);
        check("an uncountable exit falls back to the plain form",
              !far.contains("exit"), far);

        // An ordinary turn is untouched by any of this.
        String left = (String) phrase.invoke(null, Route.LEFT, 200, null, 0);
        check("an ordinary turn is unchanged",
              left.contains("turn left") && !left.contains("exit"), left);

        // --- and it comes earlier than an ordinary turn -----------------------------
        // Same speed, same stage: the roundabout notice must be further out, because the
        // sentence is longer and the counting has to start before the entry.
        for (double ms : new double[] {8.3, 13.9, 25.0}) {
            int plain = (Integer) trigger.invoke(null, 25, (float) ms);
            int round = (Integer) trigger.invoke(null, 25 + 12, (float) ms);
            check(String.format("%.0f km/h: the roundabout is announced further out", ms * 3.6),
                  round > plain, plain + " m -> " + round + " m");
        }

        System.out.println(failures == 0 ? "roundabouts: all checks passed"
                                         : failures + " FAILURES");
        if (failures != 0) System.exit(1);
    }
}
