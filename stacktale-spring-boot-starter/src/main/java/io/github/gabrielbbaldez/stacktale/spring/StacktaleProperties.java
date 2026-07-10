package io.github.gabrielbbaldez.stacktale.spring;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** {@code stacktale.*} settings — every property mirrors the appender's defaults. */
@ConfigurationProperties(prefix = "stacktale")
public class StacktaleProperties {

    /** Master switch for the auto-configured appender. */
    private boolean enabled = true;

    /** Where reports go. */
    private String file = "errors-ai.log";

    /** Package roots marked "← YOUR CODE"; defaults to the @SpringBootApplication package. */
    private String appPackages = "";

    /** Events kept per context for the story. */
    private int storySize = 15;

    /** Max age of story events, seconds. */
    private int storyWindowSeconds = 60;

    /** One full report per error fingerprint per window, seconds. */
    private int dedupWindowSeconds = 300;

    /** Size-based rotation threshold, MB. */
    private int maxFileSizeMb = 5;

    /** Rotated backups kept (0 = start fresh on rotation). */
    private int maxBackups = 1;

    /** Drop the previous session's reports on startup. */
    private boolean truncateOnStart = false;

    /** Report uncaught exceptions too. */
    private boolean installUncaughtHandler = true;

    /** log.error(...) without an exception still produces a report. */
    private boolean reportErrorsWithoutThrowable = true;

    /** Read state from the root-cause exception's getters into a fields: section. */
    private boolean captureExceptionFields = true;

    /** Mask secrets/PII (JWTs, bearer tokens, key=value secrets, emails, cards). */
    private boolean redactionEnabled = true;

    /** Extra redaction regexes applied on top of the built-ins. */
    private List<String> redactPatterns = new ArrayList<>();

    /** MDC keys that group the story per request. */
    private String correlationMdcKeys = "traceId,correlationId,requestId";

    /** Timezone for report timestamps (empty = system default). */
    private String zone = "";

    /** Log one line per HTTP request into the story (never into your console). */
    private boolean requestLogging = true;

    /** Suppress container re-logs of a failure this thread just reported (0 = off). */
    private long echoSuppressionMillis = 2000;

    /** Extra logger prefixes treated as container echoes (added to the defaults). */
    private List<String> containerLoggers = new ArrayList<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getFile() { return file; }
    public void setFile(String file) { this.file = file; }
    public String getAppPackages() { return appPackages; }
    public void setAppPackages(String appPackages) { this.appPackages = appPackages; }
    public int getStorySize() { return storySize; }
    public void setStorySize(int storySize) { this.storySize = storySize; }
    public int getStoryWindowSeconds() { return storyWindowSeconds; }
    public void setStoryWindowSeconds(int storyWindowSeconds) { this.storyWindowSeconds = storyWindowSeconds; }
    public int getDedupWindowSeconds() { return dedupWindowSeconds; }
    public void setDedupWindowSeconds(int dedupWindowSeconds) { this.dedupWindowSeconds = dedupWindowSeconds; }
    public int getMaxFileSizeMb() { return maxFileSizeMb; }
    public void setMaxFileSizeMb(int maxFileSizeMb) { this.maxFileSizeMb = maxFileSizeMb; }
    public int getMaxBackups() { return maxBackups; }
    public void setMaxBackups(int maxBackups) { this.maxBackups = maxBackups; }
    public boolean isTruncateOnStart() { return truncateOnStart; }
    public void setTruncateOnStart(boolean truncateOnStart) { this.truncateOnStart = truncateOnStart; }
    public boolean isInstallUncaughtHandler() { return installUncaughtHandler; }
    public void setInstallUncaughtHandler(boolean installUncaughtHandler) { this.installUncaughtHandler = installUncaughtHandler; }
    public boolean isReportErrorsWithoutThrowable() { return reportErrorsWithoutThrowable; }
    public void setReportErrorsWithoutThrowable(boolean reportErrorsWithoutThrowable) { this.reportErrorsWithoutThrowable = reportErrorsWithoutThrowable; }
    public boolean isCaptureExceptionFields() { return captureExceptionFields; }
    public void setCaptureExceptionFields(boolean captureExceptionFields) { this.captureExceptionFields = captureExceptionFields; }
    public boolean isRedactionEnabled() { return redactionEnabled; }
    public void setRedactionEnabled(boolean redactionEnabled) { this.redactionEnabled = redactionEnabled; }
    public List<String> getRedactPatterns() { return redactPatterns; }
    public void setRedactPatterns(List<String> redactPatterns) { this.redactPatterns = redactPatterns; }
    public String getCorrelationMdcKeys() { return correlationMdcKeys; }
    public void setCorrelationMdcKeys(String correlationMdcKeys) { this.correlationMdcKeys = correlationMdcKeys; }
    public String getZone() { return zone; }
    public void setZone(String zone) { this.zone = zone; }
    public boolean isRequestLogging() { return requestLogging; }
    public void setRequestLogging(boolean requestLogging) { this.requestLogging = requestLogging; }
    public long getEchoSuppressionMillis() { return echoSuppressionMillis; }
    public void setEchoSuppressionMillis(long echoSuppressionMillis) { this.echoSuppressionMillis = echoSuppressionMillis; }
    public List<String> getContainerLoggers() { return containerLoggers; }
    public void setContainerLoggers(List<String> containerLoggers) { this.containerLoggers = containerLoggers; }
}
