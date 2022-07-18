/*
 *
 * Copyright 2022 The Johns Hopkins University
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  ee the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package org.dataconservancy.pass.indexer.checker.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.Callable;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonReader;

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassClientFactory;
import org.dataconservancy.pass.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IndexerCheckerApp {
    private static final Logger LOG = LoggerFactory.getLogger(IndexerCheckerApp.class);
    private static final int RETRIES = 50;

    /**
     * Constructor for this class
     */
    public IndexerCheckerApp() {
    }

    /**
     * The orchestration method for everything. This is called by the CLI which only manages the
     * command line interaction.
     *
     * @throws PassCliException if there was any error occurring during the grant loading or updating processes
     */
    public void run() throws PassCliException {
        String systemPropertiesFileName = "system.properties";
        File systemPropertiesFile = new File(systemPropertiesFileName);

        //let's be careful about overwriting system properties
        String[] systemProperties = {"pass.fedora.user", "pass.fedora.password", "pass.fedora.baseurl",
                                     "pass.elasticsearch.url", "pass.elasticsearch.limit"};

        //add new system properties if we have any
        if (systemPropertiesFile.exists() && systemPropertiesFile.canRead()) {
            Properties sysProps = loadProperties(systemPropertiesFile);
            for (String key : systemProperties) {
                String value = sysProps.getProperty(key);
                if (value != null) {
                    System.setProperty(key, value);
                }
            }
        }

        LOG.info("Starting indexer end-to-end check.");
        runCheck();
    }

    private void runCheck() throws PassCliException {
        runConfigCheck();
        LOG.info("indexer configuration passed");

        PassClient passClient = PassClientFactory.getPassClient();

        //check that there are at least ten users with Role = SUBMITTER
        //this is basically a test for a non-empty index
        Set<URI> submitters = passClient.findAllByAttribute(User.class, "roles", User.Role.SUBMITTER);
        assertTrue(submitters.size() >= 10);

        User testUser = new User();
        testUser.setFirstName("BeSsIe");
        testUser.setLastName("MoOcOw");
        String businessId = "infinity";
        List<String> locatorIds = new ArrayList<>();
        locatorIds.add(businessId);
        testUser.setLocatorIds(locatorIds);

        //this user should not be in the index
        URI emptyUri = passClient.findByAttribute(User.class, "locatorIds", businessId);
        //but if it is, it is due to a previous failed run, so let's fix that
        if (emptyUri != null) {
            passClient.deleteResource(emptyUri);

            // ... and wait for it to disappear from the index
            attempt(RETRIES, () -> { // check the record does not exist before continuing
                final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);
                assertNull(emptyUri);
            });
        }

        if (emptyUri != null) {
            throw new PassCliException("Unable to delete test resource from Fedora from previous run of checker");
        }

        //now create the user ...
        final URI returnedUri = passClient.createResource(testUser);
        if (returnedUri == null) {
            throw new PassCliException("Unable to create test resource.");
        }

        // ... and wait for it to show up in the index, and find it
        attempt(RETRIES, () -> { // check the record exists before continuing
            final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);
            assertEquals(returnedUri, uri);
        });

        // once found, delete the user ...
        passClient.deleteResource(returnedUri);

        // ... and wait for it to disappear from the index
        attempt(RETRIES, () -> { // check the record does not exist before continuing
            final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);
            assertNull(uri);
        });

        LOG.info("Index passes check.");
    }

    private void runConfigCheck() throws PassCliException {
        URL url = null;
        try {
            url = new URL(System.getProperty("pass.elasticsearch.url") + "/pass");
        } catch (MalformedURLException e) {
            throw new PassCliException("PASS index URL is malformed", e);
        }
        LOG.info("Checking index configuration at " + url);
        StringBuffer content = new StringBuffer();

        try {
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            urlConnection.setRequestMethod("GET");
            urlConnection.setRequestProperty("Content-Type", "application/json");

            BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream(), StandardCharsets.UTF_8));
            String inputLine;
            while ((inputLine = bufferedReader.readLine()) != null) {
                content.append(inputLine);
            }
            bufferedReader.close();
            urlConnection.disconnect();
        } catch (IOException e) {
            throw new PassCliException("Error connecting to PASS index", e);
        }

        String jsonString = content.toString();

        JsonReader jsonReader = Json.createReader(new StringReader(jsonString));
        JsonObject passJsonObject = jsonReader.readObject();
        JsonObject properties = passJsonObject.getJsonObject("pass")
                                              .getJsonObject("mappings")
                                              .getJsonObject("_doc")
                                              .getJsonObject("properties");
        if (properties == null || properties.entrySet().size() < 10) {
            throw new PassCliException("Index appears to have too few objects in it", null);
        }
    }

    /**
     * Try invoking a runnable until it succeeds.
     *
     * @param times  The number of times to run
     * @param thingy The runnable.
     */
    void attempt(final int times, final Runnable thingy) {
        attempt(times, () -> {
            thingy.run();
            return null;
        });
    }

    /**
     * Try invoking a callable until it succeeds.
     *
     * @param times Number of times to try
     * @param it    the thing to call.
     * @return the result from the callable, when successful.
     */
    <T> T attempt(final int times, final Callable<T> it) {

        Throwable caught = null;

        for (int tries = 0; tries < times; tries++) {
            try {
                return it.call();
            } catch (final Throwable e) {
                caught = e;
                try {
                    Thread.sleep(3000);
                    System.out.println("... waiting for index to update");
                } catch (final InterruptedException i) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        throw new RuntimeException("Failed executing task", caught);
    }

    /**
     * This method processes a plain text properties file and returns a {@code Properties} object
     *
     * @param propertiesFile - the properties {@code File} to be read
     * @return the Properties object derived from the supplied {@code File}
     * @throws {@code PassCliException} if the properties file could not be accessed.
     */
    private Properties loadProperties(File propertiesFile) throws PassCliException {
        Properties properties = new Properties();
        String resource;
        try {
            resource = propertiesFile.getCanonicalPath();
        } catch (IOException e) {
            throw processException("Could not open configuration file", e);
        }
        try (InputStream resourceStream = new FileInputStream(resource)) {
            properties.load(resourceStream);
        } catch (IOException e) {
            throw processException("Could not open configuration file", e);
        }
        return properties;
    }

    /**
     * This method logs the supplied message and exception
     *
     * @param message - the error message
     * @param e       - the Exception
     * @return = the {@code PassCliException} wrapper
     */
    private PassCliException processException(String message, Exception e) {
        PassCliException clie;
        if (e != null) {
            clie = new PassCliException(message, e);
            LOG.error(message, e);
        } else {
            clie = new PassCliException(message);
            LOG.error(message);
        }
        return clie;
    }

}
