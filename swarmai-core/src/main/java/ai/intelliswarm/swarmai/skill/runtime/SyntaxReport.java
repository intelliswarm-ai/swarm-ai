package ai.intelliswarm.swarmai.skill.runtime;

public record SyntaxReport(boolean ok, String errorMessage) {

    public static SyntaxReport passed() {
        return new SyntaxReport(true, null);
    }

    public static SyntaxReport failed(String errorMessage) {
        return new SyntaxReport(false, errorMessage);
    }
}
