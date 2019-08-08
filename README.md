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
nifi.content.claim.max.appendable.size=1 MB
nifi.content.claim.max.flow.files=1000
# S3 specific settings
nifi.content.repository.s3_endpoint=s3://play.min.io/
nifi.content.repository.s3_access_key=Q3AM3UQ867SPQQA43P2F
nifi.content.repository.s3_secret_key=zuf+tfteSlswRu7BJ86wekitnifILbZam1KYY3TG
nifi.content.repository.s3_ssl_enabled=true
nifi.content.repository.s3_path_style_access=true
nifi.content.repository.s3_cache=10000
nifi.content.repository.directory.default=/nifiminio
nifi.content.repository.archive.enabled=true
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
