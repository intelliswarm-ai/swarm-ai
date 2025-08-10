package ai.intelliswarm.swarmai.examples.stock.tools.sec;

import java.util.Arrays;
import java.util.List;

/**
 * Handles filing type classification and metadata
 */
public class FilingTypeHandler {
    
    private static final List<String> PRIORITY_ORDER = Arrays.asList(
        "10-K", "20-F", "10-Q", "6-K", "8-K", "4",
        "SCHEDULE 13G", "SCHEDULE 13D", "SCHEDULE 13D/A", "SC 13G", "SC 13D/A",
        "424B3", "424B4", "424B5", "F-1", "F-3", "S-1", "S-3", "S-8", "POS AM", "EFFECT"
    );
    
    /**
     * Determines if a filing type is important for analysis
     */
    public static boolean isImportantFilingType(String formType) {
        if (formType == null) return false;
        
        // Remove any whitespace and convert to uppercase
        formType = formType.trim().toUpperCase();
        
        // Standard domestic issuer forms
        if (formType.equals("10-K") || formType.equals("10-Q") || formType.equals("8-K")) {
            return true;
        }
        
        // Foreign private issuer forms
        if (formType.equals("20-F") || formType.equals("6-K")) {
            return true;
        }
        
        // Schedule filings (beneficial ownership)
        if (formType.startsWith("SCHEDULE 13") || formType.startsWith("SC 13")) {
            return true;
        }
        
        // Proxy statements and insider trading forms
        if (formType.equals("DEF 14A") || formType.equals("4")) {
            return true;
        }
        
        // Registration statements and prospectuses
        if (formType.startsWith("S-") || formType.startsWith("424B") || formType.startsWith("F-")) {
            return true;
        }
        
        return false;
    }
    
    /**
     * Gets the display limit for a specific filing type
     */
    public static int getFilingLimit(String formType) {
        return switch (formType) {
            case "10-K", "20-F" -> 2;  // Annual reports - show up to 2
            case "10-Q", "6-K" -> 4;   // Quarterly/foreign reports - show up to 4
            case "8-K" -> 5;           // Current reports - show up to 5
            default -> 3;              // Others - show up to 3
        };
    }
    
    /**
     * Gets a user-friendly description for filing types
     */
    public static String getFilingTypeDescription(String formType) {
        return switch (formType) {
            case "10-K" -> "Annual Report (10-K)";
            case "10-Q" -> "Quarterly Report (10-Q)";
            case "8-K" -> "Current Report (8-K)";
            case "4" -> "Statement of Changes in Beneficial Ownership (Form 4)";
            case "20-F" -> "Annual Report - Foreign Private Issuer (20-F)";
            case "6-K" -> "Report of Foreign Private Issuer (6-K)";
            case "SCHEDULE 13G" -> "Beneficial Ownership Report (Schedule 13G)";
            case "SCHEDULE 13D" -> "Beneficial Ownership Report (Schedule 13D)";
            case "SCHEDULE 13D/A" -> "Amended Beneficial Ownership Report (Schedule 13D/A)";
            case "SC 13G" -> "Beneficial Ownership Statement (SC 13G)";
            case "SC 13G/A" -> "Amended Beneficial Ownership Statement (SC 13G/A)";
            case "SC 13D/A" -> "Amended Beneficial Ownership Statement (SC 13D/A)";
            case "424B3", "424B4", "424B5" -> "Prospectus (" + formType + ")";
            case "F-1" -> "Registration Statement - Foreign Private Issuer (F-1)";
            case "F-3" -> "Registration Statement - Foreign Private Issuer (F-3)";
            case "S-1" -> "Registration Statement (S-1)";
            case "S-3" -> "Registration Statement (S-3)";
            case "S-8" -> "Securities Registration - Employee Plans (S-8)";
            case "POS AM" -> "Post-Effective Amendment (POS AM)";
            case "EFFECT" -> "Notice of Effectiveness (EFFECT)";
            default -> formType + " Filings";
        };
    }
    
    /**
     * Gets the priority order for display
     */
    public static List<String> getPriorityOrder() {
        return PRIORITY_ORDER;
    }
    
    /**
     * Gets the priority score for a filing type (lower = higher priority)
     */
    public static int getPriorityScore(String formType) {
        int index = PRIORITY_ORDER.indexOf(formType);
        return index == -1 ? Integer.MAX_VALUE : index;
    }
}