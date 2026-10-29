package org.allaymc.server;

/**
 * Central branding for the Engine distribution. Legacy Allay names stay in
 * Java packages and public APIs so existing plugins can link unchanged.
 */
public final class EngineIdentity {
    public static final String NAME = "Engine";
    /** Keep the legacy value for plugins that detect Allay implementations. */
    public static final String LEGACY_CORE_NAME = "allay";
    public static final String DEFAULT_MOTD = "Engine Bedrock Server";
    public static final String DEFAULT_SUB_MOTD = "Engine by RyzeMCBE";

    private EngineIdentity() {
    }
}
