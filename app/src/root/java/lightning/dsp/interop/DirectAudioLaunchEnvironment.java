package lightning.dsp.interop;

import java.util.Map;
import java.util.function.Supplier;

/** A launch-only adapter for the documented DirectAudio performance variable. */
final class DirectAudioLaunchEnvironment {
    static final String PERFORMANCE = "BANNER_AUDIO_DIRECT_PERF";
    static final String MICROPHONE = "BANNER_AUDIO_DIRECT_MIC";

    private DirectAudioLaunchEnvironment() {}

    /**
     * Returns the original map unless a supported playback-only request can be copied.
     * The factory must supply a new ProcessBuilder environment, not an arbitrary map:
     * Android ProcessImpl requires its own ProcessEnvironment.StringEnvironment type.
     */
    static Map<String, String> adapt(Map<String, String> original, boolean requested,
            Supplier<Map<String, String>> environmentFactory) {
        if (!requested || original == null) return original;
        try {
            // DirectAudio shares performance configuration with capture. Preserve
            // enabled or unknown microphone settings instead of changing recording.
            if (original.containsKey(MICROPHONE) && !"0".equals(original.get(MICROPHONE))) return original;
            if (!"1".equals(original.get(PERFORMANCE))) return original;
            Map<String, String> copy = environmentFactory.get();
            if (copy == null || copy == original) return original;
            copy.clear();
            copy.putAll(original);
            copy.put(PERFORMANCE, "0");
            return copy;
        } catch (RuntimeException unavailable) {
            // Copying denied, malformed, or unsupported maps must not block launch.
            return original;
        }
    }
}
