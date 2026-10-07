package fr.redteams.archi.mcp;

/**
 * Author and project links, shown in the preference page footer and the About dialog.
 */
public final class AboutInfo {

    public static final String PRODUCT = "Archi MCP Server";
    public static final String AUTHOR = "Eric RICHARD";
    public static final String EMAIL = "contact@redteams.fr";
    public static final String GITHUB_REPO = "redteams-fr/archi-mcp";
    public static final String GITHUB_URL = "https://github.com/" + GITHUB_REPO;
    public static final String LINKEDIN_URL = "https://www.linkedin.com/in/richard-eric";

    private AboutInfo() {}

    /**
     * Release version from the OSGi bundle version built by build.gradle:
     * {@code 0.2.0.202610071403} gives {@code 0.2.0}, {@code 1.2.0.202610071403-rc1} gives {@code 1.2.0-rc1}.
     */
    public static String releaseVersion(String bundleVersion) {
        String[] parts = bundleVersion.split("\\.", 4);
        if (parts.length < 3) {
            return bundleVersion;
        }
        String base = parts[0] + "." + parts[1] + "." + parts[2];
        if (parts.length == 4) {
            int dash = parts[3].indexOf('-');
            if (dash >= 0) {
                return base + parts[3].substring(dash);
            }
        }
        return base;
    }
}
