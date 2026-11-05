package dev.akai.toolbox.core;

import java.util.List;

/** Explicit allowlists: never infer permission to disable an app from a name prefix. */
public final class PackageCatalog {
    private PackageCatalog() {}
    public static final List<String> PRIMARY = List.of(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending");
    // Matches the Google-service list reviewed in this phone's SecurityCenter 13.6.5.
    public static final List<String> CORE = List.of(
        "com.google.android.gms", "com.google.android.gsf", "com.android.vending",
        "com.google.android.syncadapters.contacts", "com.google.android.backuptransport",
        "com.google.android.onetimeinitializer", "com.google.android.partnersetup",
        "com.google.android.configupdater", "com.google.android.ext.shared",
        "com.google.android.printservice.recommendation");
    public static boolean allowed(String name) {
        return CORE.contains(name);
    }
}

