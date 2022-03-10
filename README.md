# PASS Indexer Checker

If the PASS indexer is unhealthy, we may experience problems if an index search for a PASS entity returns null when in fact the entity is present in the repository. If the index is down when running a loader, this can cause duplicate objects to be created in the repository. When the indexer is restarted, subsequent searches using the java client's fidByAttribute() method will fail if duplicates exist. The short term fix is to run a simple check of the indexer
before kicking off a push for a loader. This check consists of first verifying that the index is not empty by checking that there are at least ten users with role type Role.SUBMITTER, and next creating and then deleting a User, verifying that the index reflects these changes.

## Configuration
The configuration file needed is `system.properties` files as used for the grant loader. The application will look for these files in the directory from which the index checker is being run. The log file will be written there as well.

### Fedora and Elasticsearch configuration (system.properties)
This file contains parameters which must be set as system properties so that the java PASS client can configure itself to attach to its storage and its search endpoint - in our case, a Fedora instance and an Elasticsearch instance. The base URL must contain the port number and path to the base container (for example, http://localhost:8080/fcrepo/rest/)

`pass.fedora.user=`\
`pass.fedora.password=`\
`pass.fedora.baseurl=`\
`pass.elasticsearch.url=`\
`pass.elasticsearch.limit=`

