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
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package org.dataconservancy.pass.indexer.checker.app;

import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import java.io.UnsupportedEncodingException;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;



    /**
     * An email service for reporting errors or results of running the App
     * @author jrm@jhu.edu
     */
    public class EmailService {
        private static final Logger LOG = LoggerFactory.getLogger(EmailService.class);
        private final Properties mailProperties;

        /**
         * The constructor
         * @param mailProperties - the mail properties
         */
        EmailService(Properties mailProperties){
            this.mailProperties = mailProperties;
        }

        /**
         * The method which sends the email
         * @param subject the email subject
         * @param message the body of the email
         */
        void sendEmailMessage(String subject, String message){

            try {
                Session session = Session.getInstance(mailProperties,
                        new Authenticator() {
                            @Override
                            protected PasswordAuthentication getPasswordAuthentication() {
                                return new PasswordAuthentication(mailProperties
                                        .getProperty("mail.smtp.user"), mailProperties
                                        .getProperty("mail.smtp.password"));
                            }
                        });
                Message msg = new MimeMessage(session);
                msg.setFrom(new InternetAddress((String) mailProperties.get("mail.from"), "Indexer Checkerr"));
                msg.addRecipient(Message.RecipientType.TO,
                        new InternetAddress(mailProperties.getProperty("mail.to"), "Indexer Checker Client" ));
                msg.setSubject(subject);
                msg.setText(message);
                Transport.send(msg);
            } catch (AddressException e) {
                e.printStackTrace();
                String ERR_ADDRESS = "Could not send email: an addressing exception occurred";
                LOG.error(ERR_ADDRESS, e);
            } catch (MessagingException e) {
                e.printStackTrace();
                String ERR_MESSAGING = "Could not send email: a messaging exception occurred";
                LOG.error(ERR_MESSAGING, e);
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                String ERR_ENCODING = "Could not send email; an encoding exception occurred";
                LOG.error(ERR_ENCODING, e);
            }

        }
}
