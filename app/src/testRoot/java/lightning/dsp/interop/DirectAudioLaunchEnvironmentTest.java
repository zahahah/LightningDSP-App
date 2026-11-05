package lightning.dsp.interop;

import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

public class DirectAudioLaunchEnvironmentTest {
    private static Map<String, String> environment(String performance) {
        Map<String, String> env = new HashMap<>();
        if (performance != null) env.put(DirectAudioLaunchEnvironment.PERFORMANCE, performance);
        env.put("UNRELATED", "preserved");
        return env;
    }

    private static Map<String, String> adapt(Map<String, String> original, boolean requested) {
        return DirectAudioLaunchEnvironment.adapt(original, requested, () -> new ProcessBuilder().environment());
    }

    @Test public void onlyExistingLowLatencyValueIsCopiedAndAmended() {
        for (String value : new String[]{null, "", "0", "2", "12", "unknown", "1"}) {
            Map<String, String> original = environment(value);
            Map<String, String> before = new HashMap<>(original);
            Map<String, String> adapted = adapt(original, true);
            if ("1".equals(value)) {
                assertNotSame(original, adapted);
                assertEquals("0", adapted.get(DirectAudioLaunchEnvironment.PERFORMANCE));
                assertEquals("preserved", adapted.get("UNRELATED"));
                assertEquals(before.size(), adapted.size());
                assertEquals(new ProcessBuilder().environment().getClass(), adapted.getClass());
            } else assertSame(original, adapted);
            assertEquals(before, original);
        }
    }

    @Test public void disabledOrInheritedEnvironmentDoesNotAllocateOrReadMaps() {
        Map<String, String> original = environment("1");
        assertSame(original, DirectAudioLaunchEnvironment.adapt(original, false, () -> {
            throw new AssertionError("disabled allocation");
        }));
        assertNull(DirectAudioLaunchEnvironment.adapt(null, true, () -> {
            throw new AssertionError("inherited environment allocation");
        }));
    }

    @Test public void captureEnabledOrUnknownIsNeverAmended() {
        for (String microphone : new String[]{null, "", "1", "true", "unknown", "0"}) {
            Map<String, String> original = environment("1");
            if (microphone != null) original.put(DirectAudioLaunchEnvironment.MICROPHONE, microphone);
            Map<String, String> before = new HashMap<>(original);
            Map<String, String> adapted = adapt(original, true);
            if (microphone == null || "0".equals(microphone)) assertNotSame(original, adapted);
            else assertSame(original, adapted);
            assertEquals(before, original);
        }
        Map<String, String> unknown = environment("1");
        unknown.put(DirectAudioLaunchEnvironment.MICROPHONE, null);
        assertSame(unknown, adapt(unknown, true));
    }

    @Test public void launchFailurePreservesOriginalExceptionAndCallerEnvironment() {
        Map<String, String> original = environment("1");
        Map<String, String> adapted = adapt(original, true);
        IOException expected = new IOException("fake original launch failure");
        try {
            assertEquals("0", adapted.get(DirectAudioLaunchEnvironment.PERFORMANCE));
            throw expected;
        } catch (IOException actual) { assertSame(expected, actual); }
        assertEquals("1", original.get(DirectAudioLaunchEnvironment.PERFORMANCE));
    }

    @Test public void inaccessibleOrUnsupportedCopiesFallBackToOriginalMap() {
        Map<String, String> original = environment("1");
        assertSame(original, DirectAudioLaunchEnvironment.adapt(original, true, () -> {
            throw new SecurityException("denied");
        }));
        assertSame(original, DirectAudioLaunchEnvironment.adapt(original, true, () -> null));
        assertSame(original, DirectAudioLaunchEnvironment.adapt(original, true, () -> original));
        assertSame(original, DirectAudioLaunchEnvironment.adapt(original, true,
                () -> Collections.unmodifiableMap(new HashMap<>())));
        assertEquals("1", original.get(DirectAudioLaunchEnvironment.PERFORMANCE));
    }

    @Test public void inheritedFactoryValuesDoNotLeakIntoExplicitEnvironment() {
        Map<String, String> original = environment("1");
        Map<String, String> copy = DirectAudioLaunchEnvironment.adapt(original, true, () -> {
            Map<String, String> inherited = new HashMap<>(); inherited.put("UNWANTED_INHERITED", "value"); return inherited;
        });
        assertFalse(copy.containsKey("UNWANTED_INHERITED"));
        assertEquals(original.size(), copy.size());
    }

    @Test public void disabledLaunchDoesNotInheritEarlierOrNestedAmendment() {
        Map<String, String> original = environment("1");
        Map<String, String> active = adapt(original, true);
        assertEquals("0", active.get(DirectAudioLaunchEnvironment.PERFORMANCE));
        assertSame(original, adapt(original, false));
        assertEquals("1", original.get(DirectAudioLaunchEnvironment.PERFORMANCE));
        assertEquals("1", adapt(original, false).get(DirectAudioLaunchEnvironment.PERFORMANCE));
    }

    @Test public void simultaneousEnabledAndDisabledLaunchesUseIndependentSnapshots() throws Exception {
        Map<String, String> original = Collections.unmodifiableMap(environment("1"));
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread[] threads = new Thread[2];
        for (int i = 0; i < threads.length; ++i) {
            final boolean enabled = i == 0;
            threads[i] = new Thread(() -> {
                try {
                    ready.countDown(); assertTrue(start.await(2, TimeUnit.SECONDS));
                    for (int n = 0; n < 100; ++n) {
                        Map<String, String> adapted = adapt(original, enabled);
                        assertEquals(enabled ? "0" : "1", adapted.get(DirectAudioLaunchEnvironment.PERFORMANCE));
                        assertEquals("1", original.get(DirectAudioLaunchEnvironment.PERFORMANCE));
                    }
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            threads[i].start();
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS)); start.countDown();
        for (Thread thread : threads) { thread.join(3_000); assertFalse(thread.isAlive()); }
        assertNull(failure.get());
    }
}
