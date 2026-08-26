import org.watchlauncher.Route;
import java.lang.reflect.Method;

/**
 * When a turn gets announced, at the speeds this watch is actually used at.
 *
 * The complaint these exist for: warnings that come far too early in town and
 * far too late on a motorway, because they were triggered at fixed distances.
 * Five hundred metres is fifteen seconds of motorway and a minute of town.
 */
public class TurnTimingTest {

    static int failures = 0;

    static void check(String what, boolean ok, String saw) {
        if (!ok) { System.out.println("FAIL " + what + ": " + saw); failures++; }
        else System.out.println("ok   " + what + " (" + saw + ")");
    }

    public static void main(String[] a) throws Exception {
        Method trigger = Route.class.getDeclaredMethod("triggerAt", int.class, float.class);
        trigger.setAccessible(true);

        // The first notice is a minute out, the second twenty five seconds.
        int[] secs = {60, 25};
        double[][] speeds = {
            {  1.4, 5.0 },      // walking
            {  8.3, 30.0 },     // a town
            { 13.9, 50.0 },     // a main road
            { 25.0, 90.0 },     // a motorway
        };
        for (double[] sp : speeds) {
            float ms = (float) sp[0];
            int first  = (Integer) trigger.invoke(null, secs[0], ms);
            int second = (Integer) trigger.invoke(null, secs[1], ms);
            double firstS  = first / ms;
            double secondS = second / ms;
            System.out.printf("     %5.0f km/h -> notices at %5d m (%4.0f s) and %4d m (%3.0f s)%n",
                    sp[1], first, firstS, second, secondS);
            // At every speed the first notice must come before the second,
            // and neither may be so close that it arrives at the junction.
            check(String.format("%.0f km/h: notices are ordered", sp[1]),
                  first >= second, first + " >= " + second);
            check(String.format("%.0f km/h: the last notice is not at the junction", sp[1]),
                  secondS >= 4.0 || second >= 150,
                  String.format("%.0f s / %d m", secondS, second));
        }

        // The point of the change: at 90 km/h the first notice must be much
        // further out than in town, which a fixed 500 m could not do.
        int town = (Integer) trigger.invoke(null, 60, 8.3f);
        int motorway = (Integer) trigger.invoke(null, 60, 25.0f);
        check("a motorway warning is further out than a town one",
              motorway > town * 2, town + " m vs " + motorway + " m");

        // And neither runs away: walking must not warn 150 m out for a
        // junction two minutes off, nor a fast road warn three km out.
        int walking = (Integer) trigger.invoke(null, 60, 1.4f);
        check("walking is floored, not absurd", walking == 150, walking + " m");
        int fast = (Integer) trigger.invoke(null, 60, 55.0f);   // 200 km/h
        check("very fast is capped", fast == 1500, fast + " m");

        System.out.println(failures == 0 ? "turn timing: all checks passed"
                                         : "turn timing: " + failures + " FAILED");
        if (failures > 0) System.exit(1);
    }
}
