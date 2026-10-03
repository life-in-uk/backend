package info.lifeinuk.backend.source;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/** Approved configuration identity, not a record of an observation. */
@Entity
@Table(name = "source")
public class Source {
    public static final String BANK_HOLIDAYS_KEY = "gov-uk-bank-holidays";
    public static final String BANK_HOLIDAYS_SCOPE = "UK_BANK_HOLIDAYS";
    public static final String TFL_KEY = "transport-for-london";
    public static final String TFL_UNDERGROUND_SCOPE = "TFL_UNDERGROUND_STATUS";

    @Id
    private UUID id;

    @NotBlank
    @Size(max = 100)
    @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*")
    @Column(name = "source_key", nullable = false, unique = true, updatable = false, length = 100)
    private String key;

    @NotBlank
    @Size(max = 200)
    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @NotBlank
    @Column(nullable = false, updatable = false, length = 40)
    private String scope;

    @Column(nullable = false)
    private boolean enabled;

    @Version
    @Column(nullable = false)
    private long version;

    protected Source() { }

    public Source(String key, String displayName) {
        this(key, displayName, BANK_HOLIDAYS_SCOPE);
    }

    public static Source tfl() {
        return new Source(TFL_KEY, "Transport for London", TFL_UNDERGROUND_SCOPE);
    }

    private Source(String key, String displayName, String scope) {
        if (key == null || key.length() > 100 || !key.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new IllegalArgumentException("A stable lower-case source key is required");
        }
        if (displayName == null || displayName.isBlank() || displayName.length() > 200) {
            throw new IllegalArgumentException("A source display name is required (max 200 characters)");
        }
        this.id = UUID.randomUUID();
        this.key = key;
        this.displayName = displayName;
        this.scope = scope;
        this.enabled = true;
    }

    public UUID getId() { return id; }
    public String getKey() { return key; }
    public String getDisplayName() { return displayName; }
    public String getScope() { return scope; }
    public boolean isEnabled() { return enabled; }
    public long getVersion() { return version; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}
