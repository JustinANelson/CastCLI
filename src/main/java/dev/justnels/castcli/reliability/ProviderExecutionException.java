package dev.justnels.castcli.reliability;

import dev.justnels.castcli.config.ProviderConfig;

@SuppressWarnings("serial")
public final class ProviderExecutionException extends RuntimeException {
    private final ProviderConfig provider;
    private final FailureKind kind;

    public ProviderExecutionException(ProviderConfig provider, FailureKind kind, Throwable cause) {
        super("Provider " + provider.id() + " failed (" + kind + "): " + cause.getMessage(), cause);
        this.provider = provider;
        this.kind = kind;
    }
    public ProviderConfig provider() { return provider; }
    public FailureKind kind() { return kind; }
}
