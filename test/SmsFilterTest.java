import org.watchlauncher.SmsFilter;

/**
 * Which texts are control commands, against the inbox that prompted the question.
 *
 * Every body below is a real one from the watch: forty of its forty-nine messages were commands
 * burying the nine a person had sent.
 */
public class SmsFilterTest {

    static int fails = 0;

    static void check(String what, boolean ok, String detail) {
        System.out.printf("  %-46s %s%s%n", what, ok ? "ok" : "FAILED",
                detail.isEmpty() ? "" : ("   " + detail));
        if (!ok) fails++;
    }

    /** Real command bodies from the inbox. */
    static final String[] COMMANDS = {
        "lfjmm#reboot#",
        "lfjmm#usb#=mtp",
        "lfjmm#ip#=193.24.208.184:9000#",
    };

    /** Real messages from the same inbox. */
    static final String[] MESSAGES = {
        "Your Discord verification code is: 887806",
        "Je Bonus beltegoed zal op over 5 dagen vervallen. Koop een nieuwe bundel.",
        "Hi",
        "{http://files.5gcity.com/photo_message/1736134295804.png}",
        "【网易】您的邮箱收到一封邮件",
    };

    public static void main(String[] args) {
        System.out.println("sms filter:");

        for (int i = 0; i < COMMANDS.length; i++) {
            check("a command is filtered", SmsFilter.isCommand(COMMANDS[i]), COMMANDS[i]);
        }
        for (int i = 0; i < MESSAGES.length; i++) {
            String m = MESSAGES[i];
            check("a message is kept", !SmsFilter.isCommand(m),
                    m.length() > 34 ? m.substring(0, 31) + "..." : m);
        }

        // The first attempt required the body to end in a hash, which kept lfjmm#usb#=mtp in the
        // list. Stated here so it cannot come back.
        check("a command with a trailing value is still a command",
                SmsFilter.isCommand("lfjmm#usb#=mtp"), "the one the first rule missed");

        // A hash in ordinary text is not a command: the prefix has to be one bare word.
        check("a hash mid-sentence is not a command",
                !SmsFilter.isCommand("your code is #1234 and #5678"), "spaces in the prefix");
        check("a leading hash is not a command",
                !SmsFilter.isCommand("#1 fan #always"), "nothing before the hash");
        check("one hash alone is not a command",
                !SmsFilter.isCommand("abc#def"), "a command has two");

        check("nothing is not a command", !SmsFilter.isCommand(null), "");
        check("empty is not a command", !SmsFilter.isCommand(""), "");

        System.out.println(fails == 0 ? "sms filter: all checks passed"
                                      : "sms filter: " + fails + " FAILED");
        if (fails > 0) System.exit(1);
    }
}
