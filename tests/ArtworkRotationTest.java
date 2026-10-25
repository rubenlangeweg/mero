package studio.invrse.mero;

public final class ArtworkRotationTest {
    public static void main(String[] args) {
        long minute = 60_000L;
        ArtworkRotation timer = new ArtworkRotation(ArtworkRotation.INTERVAL_MS);
        equal(30 * minute, timer.remaining(0), "initial interval");
        check(!timer.isRunning(), "starts paused");
        timer.resume(100);
        check(timer.isRunning(), "resume starts countdown");
        equal(1, timer.remaining(100 + 30 * minute - 1), "one millisecond before due");
        equal(0, timer.remaining(100 + 30 * minute), "due at thirty visible minutes");
        equal(0, timer.remaining(100 + 40 * minute), "stays due after interval");

        timer = new ArtworkRotation(30 * minute);
        timer.resume(0);
        timer.resume(5 * minute);
        equal(20 * minute, timer.pause(10 * minute), "repeated resume keeps original start");
        check(!timer.isRunning(), "pause stops countdown");
        equal(20 * minute, timer.pause(60 * minute), "repeated pause preserves remainder");
        equal(20 * minute, timer.remaining(90 * minute), "hidden time does not count");
        timer.resume(100 * minute);
        equal(1, timer.remaining(120 * minute - 1), "resumed countdown preserves remainder");
        equal(0, timer.remaining(120 * minute), "resumed countdown becomes due");

        timer.reset(120 * minute);
        check(timer.isRunning(), "running reset keeps countdown running");
        equal(30 * minute, timer.remaining(120 * minute), "running reset starts full interval");
        equal(10 * minute, timer.pause(140 * minute), "running reset uses new start");
        timer.reset(150 * minute);
        check(!timer.isRunning(), "paused reset stays paused");
        equal(30 * minute, timer.remaining(200 * minute), "paused reset does not consume time");
        timer.resume(200 * minute);
        equal(0, timer.remaining(230 * minute), "paused reset resumes full interval");

        timer = new ArtworkRotation(0);
        equal(0, timer.remaining(0), "persisted zero is already due");
        timer.resume(50);
        equal(0, timer.pause(51), "zero remains due when resumed");
        equal(0, new ArtworkRotation(-1).remaining(0), "negative persisted time clamps to due");
        equal(30 * minute, new ArtworkRotation(Long.MAX_VALUE).remaining(0), "oversized time clamps to interval");
        timer = new ArtworkRotation(Long.MAX_VALUE, 5 * minute);
        equal(5 * minute, timer.remaining(0), "custom interval clamps restored remainder");
        timer.resume(10 * minute);
        equal(3 * minute, timer.pause(12 * minute), "custom interval counts visible time");
        equal(3 * minute, timer.remaining(40 * minute), "custom interval pauses hidden time");
        timer.resume(40 * minute);
        equal(0, timer.remaining(43 * minute), "custom interval resumes remaining time");
        timer.reset(43 * minute);
        equal(5 * minute, timer.remaining(43 * minute), "reset uses custom interval");
        equal(0, timer.remaining(48 * minute), "custom reset starts a new full countdown");
        try {
            new ArtworkRotation(0, 0);
            throw new AssertionError("Zero rotation interval accepted");
        } catch (IllegalArgumentException expected) { }
        System.out.println("ArtworkRotationTest: 29 checks passed");
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
    }

    private static void equal(long expected, long actual, String message) {
        if (expected != actual) throw new AssertionError(message + ": expected " + expected + ", got " + actual);
    }
}
