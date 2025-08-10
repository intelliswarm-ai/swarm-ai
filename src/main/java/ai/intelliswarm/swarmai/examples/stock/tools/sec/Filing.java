package ai.intelliswarm.swarmai.examples.stock.tools.sec;

/**
 * Represents a SEC filing document
 */
public class Filing {
    
    private String formType;
    private String filingDate;
    private String accessionNumber;
    private String primaryDocument;
    private String url;
    private String content;
    private String extractedText;
    private boolean contentFetched;
    private String contentError;
    
    // Constructors
    public Filing() {}
    
    public Filing(String formType, String filingDate, String accessionNumber, String primaryDocument, String url) {
        this.formType = formType;
        this.filingDate = filingDate;
        this.accessionNumber = accessionNumber;
        this.primaryDocument = primaryDocument;
        this.url = url;
        this.contentFetched = false;
    }
    
    // Getters and Setters
    public String getFormType() {
        return formType;
    }
    
    public void setFormType(String formType) {
        this.formType = formType;
    }
    
    public String getFilingDate() {
        return filingDate;
    }
    
    public void setFilingDate(String filingDate) {
        this.filingDate = filingDate;
    }
    
    public String getAccessionNumber() {
        return accessionNumber;
    }
    
    public void setAccessionNumber(String accessionNumber) {
        this.accessionNumber = accessionNumber;
    }
    
    public String getPrimaryDocument() {
        return primaryDocument;
    }
    
    public void setPrimaryDocument(String primaryDocument) {
        this.primaryDocument = primaryDocument;
    }
    
    public String getUrl() {
        return url;
    }
    
    public void setUrl(String url) {
        this.url = url;
    }
    
    public String getContent() {
        return content;
    }
    
    public void setContent(String content) {
        this.content = content;
    }
    
    public String getExtractedText() {
        return extractedText;
    }
    
    public void setExtractedText(String extractedText) {
        this.extractedText = extractedText;
    }
    
    public boolean isContentFetched() {
        return contentFetched;
    }
    
    public void setContentFetched(boolean contentFetched) {
        this.contentFetched = contentFetched;
    }
    
    public String getContentError() {
        return contentError;
    }
    
    public void setContentError(String contentError) {
        this.contentError = contentError;
    }
    
    // Helper methods
    public boolean hasContent() {
        return content != null && !content.trim().isEmpty();
    }
    
    public boolean hasExtractedText() {
        return extractedText != null && !extractedText.trim().isEmpty();
    }
    
    public boolean hasError() {
        return contentError != null;
    }
    
    @Override
    public String toString() {
        return String.format("Filing{formType='%s', filingDate='%s', accessionNumber='%s', contentFetched=%s}", 
            formType, filingDate, accessionNumber, contentFetched);
    }
}