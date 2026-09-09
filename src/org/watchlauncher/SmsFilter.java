package org.watchlauncher;

/**
 * Telling a control command from a message somebody sent.
 *
 * The remote-control channel arrives as SMS - see {@link SmsControl} - and its own notes say a
 * control channel that fills the message list is one nobody leaves enabled. It aborts those
 * broadcasts so they never reach the inbox, but the abort only runs on an ordered broadcast and
 * SMS_RECEIVED is not ordered on this watch. So they accumulate: of the forty-nine texts in the
 * inbox, forty are commands and nine are from a person.
 *
 * Nothing of Android in here, so the rule can be driven from a test against the bodies that are
 * actually in that inbox rather than against invented ones.
 */
public final class SmsFilter {

    private SmsFilter() { }

    /**
     * Is this a control command rather than correspondence?
     *
     * The shape is a password, then a hash, then a command word, then a hash, and optionally an
     * equals and a value: {@code lfjmm#reboot#} and {@code lfjmm#usb#=mtp} are both commands.
     * Requiring the body to end in a hash catches the first and misses the second, which is how
     * one of them was still in the list after the first attempt at this.
     *
     * Matched by shape and not against the configured password, deliberately. A command sent with
     * the wrong password is exactly what an attempt on this watch looks like, and it should not be
     * the only kind of message that shows up in the list. It is also the reason this does not just
     * compare the sender against the tracker's number - a stranger guessing is still a command.
     *
     * The prefix has to be a single alphanumeric word for this to fire, so an ordinary message
     * carrying a hash does not qualify: "your code is #1234" has a space in the prefix, and
     * "#1 fan" has nothing before the hash at all.
     */
    public static boolean isCommand(String body) {
        if (body == null) return false;
        String s = body.trim();
        int first = s.indexOf('#');
        if (first <= 0) return false;
        if (s.indexOf('#', first + 1) < 0) return false;   // a command has at least two
        for (int i = 0; i < first; i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) return false;
        }
        return true;
    }
}
