package io.github.gabrielbbaldez.stacktale;

import java.util.List;
import java.util.Map;

/** Everything the renderer needs to produce one st/1 error report block. */
public record Report(
        String id,
        long epochMillis,
        String threadName,
        DistilledStack stack,
        String messagePattern,
        Object[] args,
        String loggerName,
        Map<String, String> mdc,
        Map<String, String> fields,
        List<String> captured,
        Story story,
        String envLine,
        int occurrences,
        long firstSeenMillis,
        ReproSeed repro,
        Provenance provenance
) {
    /** Without provenance — the ordinary case, since it is opt-in and needs a build identity. */
    public Report(String id, long epochMillis, String threadName, DistilledStack stack,
                  String messagePattern, Object[] args, String loggerName,
                  Map<String, String> mdc, Map<String, String> fields, List<String> captured,
                  Story story, String envLine, int occurrences, long firstSeenMillis,
                  ReproSeed repro) {
        this(id, epochMillis, threadName, stack, messagePattern, args, loggerName, mdc, fields,
                captured, story, envLine, occurrences, firstSeenMillis, repro, null);
    }

    /**
     * Without a seed — the ordinary case. Only a throw site instrumented by
     * {@code stacktale-agent}, with {@code repro} switched on, produces one.
     */
    public Report(String id, long epochMillis, String threadName, DistilledStack stack,
                  String messagePattern, Object[] args, String loggerName,
                  Map<String, String> mdc, Map<String, String> fields, List<String> captured,
                  Story story, String envLine, int occurrences, long firstSeenMillis) {
        this(id, epochMillis, threadName, stack, messagePattern, args, loggerName, mdc, fields,
                captured, story, envLine, occurrences, firstSeenMillis, null, null);
    }
}
