# 🔑 API Keys Setup Guide for SwarmAI Framework

This guide provides step-by-step instructions for setting up API keys to enable real integration testing for the WebSearchTool and other components.

## 📋 Required API Keys Overview

| API Provider | Purpose | Cost | Free Tier | Integration Impact |
|--------------|---------|------|-----------|-------------------|
| **Google Custom Search** | Web search results | Paid | 100 queries/day | High - Primary web search |
| **Bing Search API** | Alternative web search | Paid | 1000 queries/month | High - Backup web search |
| **NewsAPI** | Financial news aggregation | Freemium | 100 requests/day | Medium - News analysis |
| **Finnhub** | Stock market data | Freemium | 60 calls/minute | Medium - Financial data |
| **Polygon.io** | Stock market data | Freemium | 5 calls/minute | Medium - Market data |
| **Alpha Vantage** | Stock data (demo) | Freemium | Demo key included | Low - Basic stock info |

---

## 🚀 Quick Setup (15 minutes)

### Step 1: Create Environment File
Create a `.env` file in your project root:

```bash
# Create the environment file
touch .env

# Add to .gitignore (IMPORTANT - never commit API keys!)
echo ".env" >> .gitignore
```

### Step 2: Add Basic API Keys Template
Add this template to your `.env` file:

```bash
# Google Custom Search API (Recommended - High Priority)
GOOGLE_API_KEY=your_google_api_key_here
GOOGLE_SEARCH_ENGINE_ID=your_search_engine_id_here

# Bing Search API (Alternative web search)
BING_API_KEY=your_bing_api_key_here

# NewsAPI (Financial news)
NEWSAPI_KEY=your_newsapi_key_here

# Financial Data APIs
FINNHUB_API_KEY=your_finnhub_api_key_here
POLYGON_API_KEY=your_polygon_api_key_here

# Alpha Vantage (Optional - demo key works)
ALPHA_VANTAGE_API_KEY=demo
```

---

## 📝 Detailed Setup Instructions

### 1. Google Custom Search API (🔥 HIGHEST PRIORITY)

**Why needed:** Primary web search functionality, most comprehensive results

**Setup Steps:**
1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing one
3. Enable the "Custom Search API":
   - Go to APIs & Services → Library
   - Search for "Custom Search API"
   - Click Enable

4. Create API credentials:
   - Go to APIs & Services → Credentials  
   - Click "Create Credentials" → API Key
   - Copy the API key

5. Create Custom Search Engine:
   - Go to [Google Programmable Search](https://programmablesearchengine.google.com/cse/)
   - Click "Add" to create new search engine
   - For "Sites to search": Enter `*` (to search entire web)
   - Click "Create"
   - Copy the Search Engine ID

6. Add to `.env`:
```bash
GOOGLE_API_KEY=AIzaSyC-your-actual-api-key-here
GOOGLE_SEARCH_ENGINE_ID=017576662512468239146:omuauf_lfve
```

**Cost:** $5 per 1000 queries after free tier
**Free Tier:** 100 queries per day

---

### 2. Bing Search API (🔶 HIGH PRIORITY)

**Why needed:** Backup web search, different result perspective

**Setup Steps:**
1. Go to [Azure Portal](https://portal.azure.com/)
2. Create "Bing Search v7" resource:
   - Search for "Bing Search v7"
   - Click Create
   - Choose pricing tier (F1 is free)
   - Create the resource

3. Get API key:
   - Go to your Bing Search resource
   - Click "Keys and Endpoint"
   - Copy Key 1

4. Add to `.env`:
```bash
BING_API_KEY=your-32-character-bing-api-key-here
```

**Cost:** Pay-per-use after free tier
**Free Tier:** 1000 queries per month

---

### 3. NewsAPI (🔶 MEDIUM PRIORITY)

**Why needed:** Financial news aggregation and analysis

**Setup Steps:**
1. Go to [NewsAPI.org](https://newsapi.org/)
2. Click "Get API Key"
3. Sign up with email
4. Verify email and get API key
5. Add to `.env`:
```bash
NEWSAPI_KEY=your-newsapi-key-here
```

**Cost:** $449/month for commercial use  
**Free Tier:** 100 requests per day (development only)

---

### 4. Finnhub API (🔶 MEDIUM PRIORITY)

**Why needed:** Real-time stock market data and financial metrics

**Setup Steps:**
1. Go to [Finnhub.io](https://finnhub.io/)
2. Click "Get free API key"
3. Sign up with email
4. Verify email
5. Copy API key from dashboard
6. Add to `.env`:
```bash
FINNHUB_API_KEY=your-finnhub-api-key-here
```

**Cost:** $7.99+/month for higher tiers  
**Free Tier:** 60 API calls per minute

---

### 5. Polygon.io API (🔶 MEDIUM PRIORITY)

**Why needed:** Stock market data, real-time quotes, financial metrics

**Setup Steps:**
1. Go to [Polygon.io](https://polygon.io/)
2. Click "Sign Up" 
3. Choose "Starter" plan (free)
4. Verify email
5. Get API key from dashboard
6. Add to `.env`:
```bash
POLYGON_API_KEY=your-polygon-api-key-here
```

**Cost:** $199+/month for higher tiers  
**Free Tier:** 5 API calls per minute

---

## 🔧 Environment Setup Methods

### Method 1: Using .env File (Recommended for Development)

1. Install dotenv for Java (add to `pom.xml`):
```xml
<dependency>
    <groupId>io.github.cdimascio</groupId>
    <artifactId>java-dotenv</artifactId>
    <version>5.2.2</version>
</dependency>
```

2. Load in your application:
```java
import io.github.cdimascio.dotenv.Dotenv;

Dotenv dotenv = Dotenv.load();
System.setProperty("GOOGLE_API_KEY", dotenv.get("GOOGLE_API_KEY"));
```

### Method 2: Direct Environment Variables (Recommended for Testing)

**Linux/Mac:**
```bash
export GOOGLE_API_KEY=your_key_here
export GOOGLE_SEARCH_ENGINE_ID=your_id_here
export BING_API_KEY=your_key_here
export NEWSAPI_KEY=your_key_here
export FINNHUB_API_KEY=your_key_here  
export POLYGON_API_KEY=your_key_here
```

**Windows (PowerShell):**
```powershell
$env:GOOGLE_API_KEY="your_key_here"
$env:GOOGLE_SEARCH_ENGINE_ID="your_id_here"
$env:BING_API_KEY="your_key_here"
$env:NEWSAPI_KEY="your_key_here"
$env:FINNHUB_API_KEY="your_key_here"
$env:POLYGON_API_KEY="your_key_here"
```

**Windows (Command Prompt):**
```cmd
set GOOGLE_API_KEY=your_key_here
set GOOGLE_SEARCH_ENGINE_ID=your_id_here
set BING_API_KEY=your_key_here
set NEWSAPI_KEY=your_key_here
set FINNHUB_API_KEY=your_key_here
set POLYGON_API_KEY=your_key_here
```

---

## 🧪 Testing Your Setup

### 1. Run Integration Tests
```bash
# Run all integration tests
mvn test -Dtest=*IntegrationTest

# Run only WebSearch integration tests  
mvn test -Dtest=WebSearchToolIntegrationTest

# Check which API keys are configured
mvn test -Dtest=WebSearchToolIntegrationTest#testBasicSearchWithAlphaVantage
```

### 2. Check Output Files
After running tests, check for output files in:
```
target/integration-test-outputs/WEB_*.md
```

### 3. Verify API Responses
Look for these indicators in test output:
- ✅ **Google**: Rich web search results with snippets
- ✅ **Bing**: Alternative web search results  
- ✅ **NewsAPI**: Recent financial news articles
- ✅ **Finnhub**: Stock price and company data
- ✅ **Polygon**: Market data and financial metrics

---

## 🚨 Security Best Practices

### ⚠️ NEVER Commit API Keys
```bash
# Add to .gitignore immediately
echo "# API Keys - DO NOT COMMIT" >> .gitignore
echo ".env" >> .gitignore  
echo "*.env" >> .gitignore
echo "api-keys.txt" >> .gitignore
```

### 🔒 Key Rotation
- Rotate API keys monthly
- Use separate keys for development/production
- Monitor usage in each provider's dashboard

### 🛡️ Rate Limiting
- Implement exponential backoff
- Cache responses when appropriate
- Monitor usage to avoid overage charges

---

## 🐛 Troubleshooting

### Common Issues:

**1. "API key not configured" errors:**
- Verify environment variables are set: `echo $GOOGLE_API_KEY`
- Check spelling of environment variable names
- Restart your IDE/terminal after setting variables

**2. "Invalid API key" errors:**
- Verify key is copied correctly (no extra spaces)
- Check if API service is enabled in provider dashboard
- Verify billing is set up (some APIs require it)

**3. "Rate limit exceeded" errors:**
- Check your usage in provider dashboards
- Implement retry logic with delays
- Consider upgrading to paid tiers

**4. Empty or "fallback" results:**
- Check if API keys are actually being used
- Look at network logs for API calls
- Verify API endpoints are reachable

### Debug Commands:
```bash
# Check environment variables
env | grep -E "(GOOGLE|BING|NEWS|FINNHUB|POLYGON)_API"

# Test single API key
curl -X GET "https://api.bing.microsoft.com/v7.0/search?q=test" \
  -H "Ocp-Apim-Subscription-Key: $BING_API_KEY"

# Run single test with debug logging
mvn test -Dtest=WebSearchToolIntegrationTest#testGoogleSearchIntegration -X
```

---

## 💰 Cost Optimization Tips

1. **Start with Free Tiers**: Test with free tiers first
2. **Use Caching**: Cache API responses to reduce calls
3. **Batch Requests**: Group related queries when possible  
4. **Monitor Usage**: Set up usage alerts in provider dashboards
5. **Prioritize APIs**: Focus on Google Custom Search as primary, others as fallbacks

---

## ✅ Verification Checklist

- [ ] Created `.env` file and added to `.gitignore`
- [ ] Obtained Google Custom Search API key and Engine ID
- [ ] Obtained Bing Search API key  
- [ ] Obtained NewsAPI key
- [ ] Obtained Finnhub API key
- [ ] Obtained Polygon.io API key
- [ ] Set environment variables
- [ ] Ran integration tests successfully
- [ ] Verified output files contain real API results
- [ ] Checked API usage in provider dashboards

**Ready to test!** 🚀

Run: `mvn test -Dtest=WebSearchToolIntegrationTest` and check the output files in `target/integration-test-outputs/`.