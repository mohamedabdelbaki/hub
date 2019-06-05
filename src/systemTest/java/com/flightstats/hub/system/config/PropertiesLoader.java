package com.flightstats.hub.system.config;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static com.flightstats.hub.util.StringUtils.randomAlphaNumeric;

@Slf4j
public class PropertiesLoader {
    public Properties loadProperties(String propertiesFileName) {
        Properties properties = new Properties();
        try (InputStream inputStream = this.getClass().getClassLoader().getResourceAsStream(propertiesFileName)) {
            properties.load(inputStream);

            properties.setProperty(PropertyNames.HELM_RELEASE_NAME, getHelmReleaseName(properties));
            properties.setProperty(PropertyNames.HELM_RELEASE_DELETE, isHelmReleaseDeletable(properties));

        } catch (IOException e) {
            log.error("Property file {} not found", propertiesFileName, e);
        }
        return properties;
    }

    private String getHelmReleaseName(Properties properties) {
        String randomReleaseName = "ddt-" + System.getProperty("user.name") + "-" + randomAlphaNumeric(4).toLowerCase();
        return properties.getProperty(PropertyNames.HELM_RELEASE_NAME, randomReleaseName);
    }

    private String isHelmReleaseDeletable(Properties properties) {
        return properties.getProperty(PropertyNames.HELM_RELEASE_DELETE, "true");
    }

}
