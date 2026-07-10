package io.github.gabrielbbaldez.stacktale;

import java.util.List;

/**
 * The events leading up to (and including) an error, plus where they came from.
 * {@code omittedByAge} counts earlier events dropped because they fell outside the story
 * time window — surfaced so the reader knows context was cut, not simply never logged
 * (matters for batch jobs / consumers where the opening line can be minutes old).
 */
public record Story(List<StoryEntry> entries, String contextLabel, int omittedByAge) {

    public Story(List<StoryEntry> entries, String contextLabel) {
        this(entries, contextLabel, 0);
    }
}
