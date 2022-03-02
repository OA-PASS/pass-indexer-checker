# PASS Indexer Checker

If the PASS indexer is unhealthy, we may experience problems if an index search for a PASS entity returns null when in fact the entity is present in the repository. If the index is down when running a loader, this can cause duplicate objects to be created in the repository. When the indexer is restarted, subsequent searches using the java client's fidByAttribute() method will fail if duplicates exist. The short term fix is to run a simple check of the indexer
before kicking off a push for a loader. This check consists of first verifying that the index is not empty by checking that there are at least ten users with role type Role.SUBMITTER, and next creating and then deleting a User, verifying that the index reflects these changes.

##Configuration
The configuration files needed are the `mail.properties` and `system.properties` files as used for the grant loader. The application will look for these files in the directory from which the index checker is being run. The log file will be written there as well.

###Mail server properties file (mail.properties)
The use of the mail server is enabled by supplying the command line option -e. This configuration file contains values for parameters needed to send mail out from the application. These values suggest using a gmail server for example.

`mail.transport.protocol=SMTPS`\
`mail.smtp.starttls.enable=true`\
`mail.smtp.host=smtp.gmail.com`\
`mail.smtp.port=587`\
`mail.smtp.auth=true`\
`mail.smtp.user=`\
`mail.smtp.password=`\
`mail.from=`\
`mail.to=`

###Fedora and Elasticsearch configuration (system.properties)
This file contains parameters which must be set as system properties so that the java PASS client can configure itself to attach to its storage and its search endpoint - in our case, a Fedora instance and an Elasticsearch instance. The base URL must contain the port number and path to the base container (for example, http://localhost:8080/fcrepo/rest/)

`pass.fedora.user=`\
`pass.fedora.password=`\
`pass.fedora.baseurl=`\
`pass.elasticsearch.url=`\
`pass.elasticsearch.limit=`

