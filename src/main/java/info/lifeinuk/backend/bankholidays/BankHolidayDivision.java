package info.lifeinuk.backend.bankholidays;

public enum BankHolidayDivision {
    ENGLAND_AND_WALES("england-and-wales"),
    SCOTLAND("scotland"),
    NORTHERN_IRELAND("northern-ireland");

    private final String sourceIdentifier;

    BankHolidayDivision(String sourceIdentifier) {
        this.sourceIdentifier = sourceIdentifier;
    }

    public String sourceIdentifier() { return sourceIdentifier; }
}
