package com.bugsnag.android.gradle

import org.gradle.api.tasks.TaskAction
import org.gradle.process.internal.ExecException
import org.json.simple.JSONObject

import java.nio.charset.Charset

class BugsnagReleasesTask extends BugsnagVariantOutputTask {

    private static final Collection<String> VALID_VCS_PROVIDERS =
        Arrays.asList("github-enterprise", "bitbucket-server", "gitlab-onpremise", "bitbucket",
            "github", "gitlab")

    private static final String MK_OS_ARCH = "os.arch"
    private static final String MK_OS_NAME = "os.name"
    private static final String MK_OS_VERSION = "os.version"
    private static final String MK_JAVA_VERSION = "java.version"
    private static final String MK_GRADLE_VERSION = "gradle.version"
    private static final String MK_GIT_VERSION = "git.version"

    BugsnagReleasesTask() {
        super()
        this.description = "Assembles information about the build that will be sent to the releases API"
    }

    @TaskAction
    void fetchReleaseInfo() {
        super.readManifestFile()

        if (!isValidPayload(apiKey, versionName)) {
            project.logger.warn("Must supply api key and version name for release task")
            return
        }

        // TODO change to debug level
        JSONObject payload = generateJsonPayload()
        String json = payload.toString()
        project.logger.lifecycle("Releases Payload:\n${json}")

    }

    JSONObject generateJsonPayload() {
        JSONObject root = new JSONObject()

        if (project.bugsnag.versionName != null) {
            versionName = project.bugsnag.versionName
        }
        if (project.bugsnag.versionCode != null) {
            versionCode = project.bugsnag.versionCode
        }
        if (project.bugsnag.releaseStage != null) {
            releaseStage = project.bugsnag.releaseStage
        }

        root.put("appVersion", versionName)
        root.put("appVersionCode", versionCode)
        root.put("releaseStage", releaseStage)
        root.put("autoAssignRelease", project.bugsnag.autoAssignRelease)

        if (project.bugsnag.builderName != null) {
            root.put("builderName", project.bugsnag.builderName)
        }
        root.put("metadata", generateMetadataJson())
        root.put("sourceControl", generateVcsJson())
        root
    }

    private JSONObject generateVcsJson() {
        String vcsUrl = project.bugsnag.vcsRepository
        String commitHash = project.bugsnag.vcsRevision
        String vcsProvider = project.bugsnag.vcsProvider

        if (vcsUrl == null) {
            vcsUrl = runCmd("git", "config", "--get", "remote.origin.url")
        }
        if (commitHash == null) {
            commitHash = runCmd("git", "rev-parse", "HEAD")
        }
        if (vcsProvider == null) {
            vcsProvider = parseProviderUrl(vcsUrl)
        }

        JSONObject sourceControl = new JSONObject()
        sourceControl.put("repository", vcsUrl)
        sourceControl.put("revision", commitHash)

        if (isValidVcsProvider(vcsProvider)) {
            sourceControl.put("provider", vcsProvider)
        }
        sourceControl
    }

    private JSONObject generateMetadataJson() {
        Map<String, String> metadata = collectDefaultMetaData()
        Map<String, String> userData = project.bugsnag.metadata

        if (userData != null) { // user values overwrite any defaults
            for (Map.Entry<String, String> entry : userData.entrySet()) {
                metadata.put(entry.key, entry.value)
            }
        }

        JSONObject additionalInfo = new JSONObject()

        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            additionalInfo.put(entry.key, entry.value)
        }
        additionalInfo
    }

    private Map<String, String> collectDefaultMetaData() {
        Map<String, String> metadata = new HashMap<>()
        metadata.put(MK_OS_ARCH, System.getProperty(MK_OS_ARCH))
        metadata.put(MK_OS_NAME, System.getProperty(MK_OS_NAME))
        metadata.put(MK_OS_VERSION, System.getProperty(MK_OS_VERSION))
        metadata.put(MK_JAVA_VERSION, System.getProperty(MK_JAVA_VERSION))
        metadata.put(MK_GRADLE_VERSION, project.gradle.gradleVersion)
        metadata.put(MK_GIT_VERSION, runCmd("git", "--version"))
        metadata
    }

    static boolean isValidPayload(String apiKey, String versionName) {
        return apiKey != null && versionName != null
    }

    static boolean isValidVcsProvider(String provider) {
        return provider == null || VALID_VCS_PROVIDERS.contains(provider)
    }

    static String parseProviderUrl(String url) {
        if (url != null) {
            for (String provider : VALID_VCS_PROVIDERS) {
                if (url.contains(provider)) {
                    return provider
                }
            }
        }
        null
    }

    /**
     * Runs a command on the shell
     * @param cmd the command (arguments must be separate strings)
     * @return the cmd output
     */
    private String runCmd(String... cmd) {
        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream()

            project.exec {
                commandLine cmd
                standardOutput = baos
            }
            new String(baos.toByteArray(), Charset.forName("UTF-8")).trim()
        } catch (ExecException e) {
            project.logger.warn("Command failed", e)
            null
        }
    }
}


