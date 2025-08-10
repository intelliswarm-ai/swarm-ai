# Release Process for SwarmAI Framework

This document describes how to release the SwarmAI Framework to Maven Central.

## Prerequisites

Before you can release to Maven Central, you need to:

1. **Create a Sonatype JIRA account**
   - Go to https://issues.sonatype.org and create an account
   - Create a New Project ticket to request access to publish under `ai.intelliswarm` groupId
   - Wait for approval (usually takes 1-2 business days)

2. **Generate a GPG key pair**
   ```bash
   # Generate GPG key
   gpg --gen-key
   
   # List keys to get the key ID
   gpg --list-secret-keys --keyid-format LONG
   
   # Export the private key (for GitHub secrets)
   gpg --armor --export-secret-keys YOUR_KEY_ID > private.key
   
   # Upload public key to key servers
   gpg --keyserver keyserver.ubuntu.com --send-keys YOUR_KEY_ID
   gpg --keyserver keys.openpgp.org --send-keys YOUR_KEY_ID
   ```

3. **Configure GitHub Secrets**
   
   Go to your repository Settings → Secrets and variables → Actions, and add:
   
   - `OSSRH_USERNAME`: Your Sonatype JIRA username
   - `OSSRH_TOKEN`: Your Sonatype JIRA password or token
   - `MAVEN_GPG_PRIVATE_KEY`: Content of your exported private key
   - `MAVEN_GPG_PASSPHRASE`: Passphrase for your GPG key

## Automated Release Process

The release is automated via GitHub Actions. To release a new version:

1. **Update the version in pom.xml**
   ```xml
   <version>1.0.0</version>  <!-- Remove -SNAPSHOT -->
   ```

2. **Commit and push the changes**
   ```bash
   git add pom.xml
   git commit -m "Release version 1.0.0"
   git push origin main
   ```

3. **Create a GitHub Release**
   - Go to your repository on GitHub
   - Click on "Releases" → "Create a new release"
   - Create a new tag (e.g., `v1.0.0`)
   - Add release notes
   - Click "Publish release"

4. **The GitHub Action will automatically:**
   - Build the project
   - Generate source and javadoc JARs
   - Sign all artifacts with GPG
   - Deploy to Maven Central
   - Artifacts will be available in Maven Central within 2-4 hours

## Manual Release Process

If you need to release manually from your local machine:

1. **Configure settings.xml**
   
   Add to `~/.m2/settings.xml`:
   ```xml
   <settings>
     <servers>
       <server>
         <id>ossrh</id>
         <username>your-jira-username</username>
         <password>your-jira-password</password>
       </server>
     </servers>
     <profiles>
       <profile>
         <id>ossrh</id>
         <activation>
           <activeByDefault>true</activeByDefault>
         </activation>
         <properties>
           <gpg.executable>gpg</gpg.executable>
           <gpg.passphrase>your-gpg-passphrase</gpg.passphrase>
         </properties>
       </profile>
     </profiles>
   </settings>
   ```

2. **Deploy to Maven Central**
   ```bash
   mvn clean deploy -Prelease
   ```

## After Release

1. **Update version for next development cycle**
   ```xml
   <version>1.0.1-SNAPSHOT</version>
   ```

2. **Commit and push**
   ```bash
   git add pom.xml
   git commit -m "Prepare for next development iteration"
   git push origin main
   ```

## Verify Release

After release, verify your artifact is available:

- **Sonatype Repository**: https://s01.oss.sonatype.org/content/groups/public/ai/intelliswarm/
- **Maven Central** (after sync, ~2-4 hours): https://central.sonatype.com/artifact/ai.intelliswarm/swarmai-framework

## Using the Released Artifact

Once published, users can add SwarmAI to their projects:

### Maven
```xml
<dependency>
    <groupId>ai.intelliswarm</groupId>
    <artifactId>swarmai-framework</artifactId>
    <version>1.0.0</version>
</dependency>
```

### Gradle
```gradle
implementation 'ai.intelliswarm:swarmai-framework:1.0.0'
```

## Troubleshooting

- **GPG signing fails**: Ensure your GPG key is properly configured and the passphrase is correct
- **Authentication fails**: Verify your OSSRH credentials in GitHub secrets
- **Deployment fails**: Check that your groupId is approved in Sonatype JIRA
- **Artifacts not appearing**: It can take 2-4 hours for artifacts to appear in Maven Central after deployment

## Additional Resources

- [Sonatype OSSRH Guide](https://central.sonatype.org/publish/publish-guide/)
- [Maven Central Requirements](https://central.sonatype.org/publish/requirements/)
- [GPG Signing Guide](https://central.sonatype.org/publish/requirements/gpg/)