/*
 * This file is part of Dependency-Track.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Copyright (c) Steve Springett. All Rights Reserved.
 */
package org.dependencytrack.integrations.kenna;

import alpine.crypto.DataEncryption;
import alpine.logging.Logger;
import alpine.model.ConfigProperty;
import org.apache.http.entity.ContentType;
import org.dependencytrack.integrations.AbstractIntegrationPoint;
import org.dependencytrack.integrations.PortfolioFindingUploader;
import org.dependencytrack.model.Project;
import org.dependencytrack.model.ProjectProperty;
import org.dependencytrack.util.HttpClientFactory;
import org.json.JSONObject;
import unirest.HttpRequestWithBody;
import unirest.HttpResponse;
import unirest.JsonNode;
import unirest.Unirest;
import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.dependencytrack.model.ConfigPropertyConstants.*;

public class KennaSecurityUploader extends AbstractIntegrationPoint implements PortfolioFindingUploader {

    private static final Logger LOGGER = Logger.getLogger(KennaSecurityUploader.class);
    private static final String ASSET_EXTID_PROPERTY = "kenna.asset.external_id";
    private static final String API_ROOT = "https://api.kennasecurity.com";
    private static final String CONNECTOR_UPLOAD_URL = API_ROOT + "/connectors/%s/data_file";

    private String connectorId;

    @Override
    public String name() {
        return "Kenna Security";
    }

    @Override
    public String description() {
        return "Pushes Dependency-Track findings to Kenna Security";
    }

    @Override
    public boolean isEnabled() {
        final ConfigProperty enabled = qm.getConfigProperty(KENNA_ENABLED.getGroupName(), KENNA_ENABLED.getPropertyName());
        final ConfigProperty connector = qm.getConfigProperty(KENNA_CONNECTOR_ID.getGroupName(), KENNA_CONNECTOR_ID.getPropertyName());
        if (enabled != null && Boolean.valueOf(enabled.getPropertyValue()) && connector != null && connector.getPropertyValue() != null) {
            connectorId = connector.getPropertyValue();
            return true;
        }
        return false;
    }

    @Override
    public InputStream process() {
        final KennaDataTransformer kdi = new KennaDataTransformer(qm);
        for (Project project: qm.getAllProjects()) {
            final ProjectProperty externalId = qm.getProjectProperty(project, KENNA_ENABLED.getGroupName(), ASSET_EXTID_PROPERTY);
            if (externalId != null && externalId.getPropertyValue() != null) {
                kdi.process(project, externalId.getPropertyValue());
            }
        }
        return new ByteArrayInputStream(kdi.generate().toString().getBytes());
    }

    @Override
    public void upload(InputStream payload) {
        final ConfigProperty tokenProperty = qm.getConfigProperty(KENNA_TOKEN.getGroupName(), KENNA_TOKEN.getPropertyName());
        try {
            final String token = DataEncryption.decryptAsString(tokenProperty.getPropertyValue());
            Unirest.config().httpClient(HttpClientFactory.createClient());
            final HttpRequestWithBody request = Unirest.post(String.format(CONNECTOR_UPLOAD_URL, connectorId));
            final HttpResponse<JsonNode> response = request
                    .header("X-Risk-Token", token)
                    .header("accept", "application/json")
                    .field("file", payload, ContentType.APPLICATION_JSON, "findings.json")
                    .field("run", "true")
                    .asJson();
            if (response.getStatus() == 200 && response.getBody() != null) {
                JSONObject root = response.getBody().getObject();
                if (root.getString("success").equals("true")) {
                    LOGGER.debug("Successfully uploaded KDI");
                    return;
                }
                LOGGER.warn("An unexpected response was received uploading findings to Kenna Security");
            } else {
                LOGGER.warn("Kenna uploader did not receive expected response while attempting to upload "
                        + "Dependency-Track findings. HTTP response code: "
                        + response.getStatus() + " - " + response.getStatusText());
                handleUnexpectedHttpResponse(LOGGER, request.getUrl(), response.getStatus(), response.getStatusText());
            }
        } catch (Exception e) {
            LOGGER.error("An error occurred attempting to upload findings to Kenna Security", e);
        }
    }
}
