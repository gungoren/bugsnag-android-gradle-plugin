package com.bugsnag.android.gradle

import org.apache.http.HttpResponse
import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpPost
import org.apache.http.entity.mime.MultipartEntity
import org.apache.http.entity.mime.content.StringBody
import org.apache.http.impl.client.DefaultHttpClient
import org.apache.http.params.HttpConnectionParams
import org.apache.http.params.HttpParams
import org.apache.http.util.EntityUtils
/**
 Task to upload ProGuard mapping files to Bugsnag.

 Reads meta-data tags from the project's AndroidManifest.xml to extract a
 build UUID (injected by BugsnagManifestTask) and a Bugsnag API Key:

 https://developer.android.com/guide/topics/manifest/manifest-intro.html
 https://developer.android.com/guide/topics/manifest/meta-data-element.html

 This task must be called after ProGuard mapping files are generated, so
 it is usually safe to have this be the absolute last task executed during
 a build.
 */
abstract class BugsnagMultiPartUploadTask extends BugsnagVariantOutputTask {

    static final int MAX_RETRY_COUNT = 5
    static final int TIMEOUT_MILLIS = 60000 // 60 seconds

    String applicationId

    BugsnagMultiPartUploadTask() {
        super()
    }

    def uploadMultipartEntity(MultipartEntity mpEntity) {
        if (apiKey == null) {
            project.logger.warn("Skipping upload due to invalid parameters")
            return
        }

        addPropertiesToMultipartEntity(mpEntity)

        boolean uploadSuccessful = uploadToServer(mpEntity)

        def maxRetryCount = getRetryCount()
        def retryCount = maxRetryCount
        while (!uploadSuccessful && retryCount > 0) {
            project.logger.warn(String.format("Retrying Bugsnag upload (%d/%d) ...",
                maxRetryCount - retryCount + 1, maxRetryCount))
            uploadSuccessful = uploadToServer(mpEntity)
            retryCount--
        }
    }

    def addPropertiesToMultipartEntity(MultipartEntity mpEntity) {
        mpEntity.addPart("apiKey", new StringBody(apiKey))
        mpEntity.addPart("appId", new StringBody(applicationId))
        mpEntity.addPart("versionCode", new StringBody(versionCode))

        if (buildUUID != null) {
            mpEntity.addPart("buildUUID", new StringBody(buildUUID))
        }

        if (versionName != null) {
            mpEntity.addPart("versionName", new StringBody(versionName))
        }

        if (project.bugsnag.overwrite || System.properties['bugsnag.overwrite']) {
            mpEntity.addPart("overwrite", new StringBody("true"))
        }

        project.logger.debug("apiKey: ${apiKey}")
        project.logger.debug("appId: ${applicationId}")
        project.logger.debug("versionCode: ${versionCode}")
        project.logger.debug("buildUUID: ${buildUUID}")
        project.logger.debug("versionName: ${versionName}")
        project.logger.debug("overwrite: ${project.bugsnag.overwrite}")
    }

    boolean uploadToServer(mpEntity) {
        project.logger.lifecycle("Attempting upload of mapping file to Bugsnag")

        // Make the request
        HttpPost httpPost = new HttpPost(project.bugsnag.endpoint)
        httpPost.setEntity(mpEntity)

        HttpClient httpClient = new DefaultHttpClient()
        HttpParams params = httpClient.getParams()
        HttpConnectionParams.setConnectionTimeout(params, TIMEOUT_MILLIS)
        HttpConnectionParams.setSoTimeout(params, TIMEOUT_MILLIS)

        int statusCode
        def responseEntity
        try {
            HttpResponse response = httpClient.execute(httpPost)
            statusCode = response.getStatusLine().getStatusCode()
            responseEntity = EntityUtils.toString(response.getEntity(), "utf-8")
        } catch (Exception e) {
            project.logger.error(String.format("Bugsnag upload failed: %s", e))
            return false
        }

        if (statusCode == 200) {
            project.logger.lifecycle("Bugsnag upload successful")
            return true
        }

        project.logger.error(String.format("Bugsnag upload failed with code %d: %s",
            statusCode, responseEntity))
        return false
    }

    /**
     * Get the retry count defined by the user. If none is set the default is 0 (zero).
     * Also to avoid too much retries the max value is 5 (five).
     *
     * @return the retry count
     */
    int getRetryCount() {
        return project.bugsnag.retryCount >= MAX_RETRY_COUNT ? MAX_RETRY_COUNT : project.bugsnag.retryCount
    }

}
