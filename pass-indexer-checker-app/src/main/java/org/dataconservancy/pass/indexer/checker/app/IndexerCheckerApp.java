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

import org.dataconservancy.pass.client.PassClient;
import org.dataconservancy.pass.client.PassClientFactory;
import org.dataconservancy.pass.model.User;

import java.io.*;
import java.net.*;
import java.nio.charset.Charset;
import java.util.*;
import java.util.concurrent.Callable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.*;

import static java.lang.String.format;
import static org.junit.Assert.*;

public class IndexerCheckerApp {
    private static final Logger LOG = LoggerFactory.getLogger(IndexerCheckerApp.class);
    private final boolean email;
    private EmailService emailService;
    private static final int RETRIES = 50;

    /**
     * Constructor for this class
     * @param email - a boolean which indicates whether or not to send email notification of the result of the current run
     */
    public IndexerCheckerApp(boolean email) {
        this.email = email;
    }

    /**
     * The orchestration method for everything. This is called by the CLI which only manages the
     * command line interaction.
     *
     * @throws PassCliException if there was any error occurring during the grant loading or updating processes
     */
    public void run() throws PassCliException {
        String mailPropertiesFileName = "mail.properties";
        File mailPropertiesFile = new File(mailPropertiesFileName);
        String systemPropertiesFileName = "system.properties";
        File systemPropertiesFile = new File(systemPropertiesFileName);

        //let's be careful about overwriting system properties
        String[] systemProperties = {"pass.fedora.user", "pass.fedora.password", "pass.fedora.baseurl",
                "pass.elasticsearch.url", "pass.elasticsearch.limit"};

        Properties mailProperties;

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

        //create mail properties and instantiate email service if we are using the service
        if (email) {
            if (!mailPropertiesFile.exists()) {
                throw processException(format(IndexerCheckerErrors.ERR_REQUIRED_CONFIGURATION_FILE_MISSING, mailPropertiesFileName), null);
            }
            try {
                mailProperties = loadProperties(mailPropertiesFile);
                emailService = new EmailService(mailProperties);
            } catch (RuntimeException e) {
                throw processException(IndexerCheckerErrors.ERR_COULD_NOT_OPEN_CONFIGURATION_FILE, e);
            }
        }

        LOG.info("Starting indexer end-to-end check.");
        runCheck();
    }


    private void runCheck() throws PassCliException {
        try {
            runConfigCheck();
            LOG.info("indexer configuration passed");
        } catch (IOException e) {
            LOG.error("Error running configuration check" , e);
            throw new PassCliException("Error running configuration check" , e);
        }


        PassClient passClient = PassClientFactory.getPassClient();

        //check that there are at least ten users with Role = SUBMITTER
        //this is basically a test for a non-empty index
        Set<URI> submitters = passClient.findAllByAttribute(User.class, "roles", User.Role.SUBMITTER);
        assertTrue(submitters.size() >=10);

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
        if (emptyUri != null ) {
            passClient.deleteResource( emptyUri );

            // ... and wait for it to disappear from the index
            attempt(RETRIES, () -> { // check the record does not exist before continuing
                        final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);
            assertNull(emptyUri);
            });
        }
        assertNull(emptyUri);

        //now create the user ...
        final URI returnedUri = passClient.createResource(testUser);
        assertNotNull( returnedUri );

        // ... and wait for it to show up in the index, and find it
        attempt(RETRIES, () -> { // check the record exists before continuing
            final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);
            assertEquals(returnedUri, uri);
        });

        // once found, delete the user ...
        passClient.deleteResource( returnedUri );

        // ... and wait for it to disappear from the index
        attempt(RETRIES, () -> { // check the record does not exist before continuing
            final URI uri = passClient.findByAttribute(User.class, "locatorIds", businessId);

            assertNull(uri);
        });

    }

    private void runConfigCheck() throws IOException {
        URL url = new URL(System.getProperty("pass.elasticsearch.url")+"/pass");
        LOG.info("Checking index configuration at "  + url);
        HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
        urlConnection.setRequestMethod("GET");
        urlConnection.setRequestProperty("Content-Type", "application/json");

        BufferedReader bufferedReader = new BufferedReader(
                new InputStreamReader(urlConnection.getInputStream(), Charset.forName("UTF-8")));
        String inputLine;
        StringBuffer content = new StringBuffer();
        while ((inputLine = bufferedReader.readLine()) != null) {
            content.append(inputLine);
        }
        bufferedReader.close();
        urlConnection.disconnect();

        String jsonString = content.toString();

        JsonReader jsonReader = Json.createReader(new StringReader(jsonString));
        JsonObject passJsonObject = jsonReader.readObject();
        JsonObject properties = passJsonObject.getJsonObject("pass")
                .getJsonObject("mappings")
                        .getJsonObject("_doc")
                                .getJsonObject("properties");
        assertTrue(properties.entrySet().size() > 10); //should be 80 or more
    }

    /**
     * Try invoking a runnable until it succeeds.
     *
     * @param times The number of times to run
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
     * @param it the thing to call.
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
     * @param propertiesFile - the properties {@code File} to be read
     * @return the Properties object derived from the supplied {@code File}
     * @throws PassCliException if the properties file could not be accessed.
     */
    private Properties loadProperties(File propertiesFile) throws PassCliException {
        Properties properties = new Properties();
        String resource;
        try{
            resource = propertiesFile.getCanonicalPath();
        } catch (IOException e) {
            throw processException(IndexerCheckerErrors.ERR_COULD_NOT_OPEN_CONFIGURATION_FILE, e);
        }
        try(InputStream resourceStream = new FileInputStream(resource)){
            properties.load(resourceStream);
        } catch (IOException e) {
            throw processException(IndexerCheckerErrors.ERR_COULD_NOT_OPEN_CONFIGURATION_FILE, e);
        }
        return properties;
    }


    /**
     * This method logs the supplied message and exception, reports the {@code Exception} to STDOUT, and
     * optionally causes an email regarding this {@code Exception} to be sent to the address configured
     * in the mail properties file
     * @param message - the error message
     * @param e - the Exception
     * @return = the {@code PassCliException} wrapper
     */
    private PassCliException processException (String message, Exception e){
        PassCliException clie;

        String errorSubject = "Indexer Checker ERROR";
        if(e != null) {
            clie = new PassCliException(message, e);
            LOG.error(message, e);
            e.printStackTrace();
            if (email) {
                emailService.sendEmailMessage(errorSubject, clie.getMessage());
            }
        } else {
            clie = new PassCliException(message);
            LOG.error(message);
            System.err.println(message);
        }
        return clie;
    }

}
