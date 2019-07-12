# nifi-minio
A custom ContentRepository implementation for NiFi to persist data to MinIO Object Storage

## Build
```
mvn clean; mvn package;
cp nifi-minio-nar/target/nifi-minio-nar-1.0.0.nar $NIFI_HOME/lib
```

## Configure Apache NiFi
Add following entries in `$NIFI_HOME/conf/nifi.properties`

```
...
# Content Repository
nifi.content.repository.implementation=io.minio.nifi.MinIORepository
nifi.content.claim.max.appendable.size=10 MB
nifi.content.claim.max.flow.files=100
nifi.content.repository.directory.default=/nifiminio
nifi.content.repository.archive.enabled=true
nifi.content.repository.always.sync=false
nifi.content.viewer.url=../nifi-content-viewer/
...
```

## Start Apache NiFi
```
$NIFI_HOME/nifi.sh run
...
2019-07-12 07:00:13,353 INFO [main] org.apache.nifi.web.server.JettyServer http://72.28.97.54:8080/nifi
2019-07-12 07:00:13,354 INFO [main] org.apache.nifi.web.server.JettyServer http://127.0.0.1:8080/nifi
2019-07-12 07:00:13,355 INFO [main] org.apache.nifi.BootstrapListener Successfully initiated communication with Bootstrap
2019-07-12 07:00:13,356 INFO [main] org.apache.nifi.NiFi Controller initialization took 15132490080 nanoseconds (15 seconds).
```

Now visit http://localhost:8080/nifi to start configuring data flows.

## TODO
- Implement `NiFiProperties.CONTENT_ARCHIVE_MAX_RETENTION_PERIOD` with bucket lifecycle policies.
- Implement `NiFiProperties.CONTENT_ARCHIVE_MAX_USAGE_PERCENTAGE` with bucket lifecycle policies.
