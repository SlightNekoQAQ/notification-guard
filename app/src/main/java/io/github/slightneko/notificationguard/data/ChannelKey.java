package io.github.slightneko.notificationguard.data;

import java.util.Objects;

public record ChannelKey(int user, String pkg, String channel) {
    public ChannelKey {
        Objects.requireNonNull(pkg);
        channel = channel == null ? "" : channel;
    }
}
