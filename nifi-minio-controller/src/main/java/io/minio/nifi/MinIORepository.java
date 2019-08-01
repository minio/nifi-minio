/*
 * MinIO Object Storage (C) 2019 MinIO, Inc.
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
 */
package io.minio.nifi;

import java.net.URI;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.DirectoryStream;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.controller.repository.ContentNotFoundException;
import org.apache.nifi.controller.repository.ContentRepository;
import org.apache.nifi.controller.repository.RepositoryPurgeException;
import org.apache.nifi.controller.repository.claim.ContentClaim;
import org.apache.nifi.controller.repository.claim.ResourceClaim;
import org.apache.nifi.controller.repository.claim.ResourceClaimManager;
import org.apache.nifi.controller.repository.claim.StandardContentClaim;
import org.apache.nifi.engine.FlowEngine;
import org.apache.nifi.processor.DataUnit;
import org.apache.nifi.stream.io.ByteCountingOutputStream;
import org.apache.nifi.stream.io.SynchronizedByteCountingOutputStream;
import org.apache.nifi.stream.io.StreamUtils;
import org.apache.nifi.util.FormatUtils;
import org.apache.nifi.util.NiFiProperties;
import org.apache.nifi.util.StopWatch;
import org.apache.nifi.util.file.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableMap;

import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.GetObjectRequest;

import io.minio.nifi.s3fs.S3Path;

/**
 * Is thread safe
 *
 */
public class MinIORepository implements ContentRepository {

    /**
     * The default buffer size ({@value}) to use in copy methods.
     */
    private static final int DEFAULT_BUFFER_SIZE = 1024 * 32;

    public static final int SECTIONS_PER_CONTAINER = 1024;
    public static final long MIN_CLEANUP_INTERVAL_MILLIS = 1000;
    public static final String ARCHIVE_DIR_NAME = "archive";

    // 100 MB cap for the configurable NiFiProperties.MAX_APPENDABLE_CLAIM_SIZE property to prevent
    // unnecessarily large resource claim files
    public static final String APPENDABLE_CLAIM_LENGTH_CAP = "100 MB";
    public static final Pattern MAX_ARCHIVE_SIZE_PATTERN = Pattern.compile("\\d{1,2}%");
    private static final Logger LOG = LoggerFactory.getLogger(MinIORepository.class);

    private final Logger archiveExpirationLog = LoggerFactory.getLogger(MinIORepository.class.getName() + ".archive.expiration");

    private final Map<String, Path> containers;
    private final List<String> containerNames;
    private final AtomicLong index;

    private final ScheduledExecutorService executor = new FlowEngine(4, "MinIORepository Workers", true);
    private final ConcurrentMap<String, BlockingQueue<ResourceClaim>> reclaimable = new ConcurrentHashMap<>();
    private final Map<String, ContainerState> containerStateMap = new HashMap<>();

    // Queue for claims that are kept open for writing. Ideally, this will be at
    // least as large as the number of threads that will be updating the repository simultaneously but we don't want
    // to get too large because it will hold open up to this many FileOutputStreams.
    // The queue is used to determine which claim to write to and then the corresponding Map can be used to obtain
    // the OutputStream that we can use for writing to the claim.
    private final BlockingQueue<ClaimLengthPair> writableClaimQueue;
    private final ConcurrentMap<ResourceClaim, ByteCountingOutputStream> writableClaimStreams = new ConcurrentHashMap<>(100);

    private final boolean archiveData;
    // 1 MB default, as it means that we won't continually write to one
    // file that keeps growing but gives us a chance to bunch together a lot of small files. Before, we had issues
    // with creating and deleting too many files, as we had to delete 100's of thousands of files every 2 minutes
    // in order to avoid backpressure on session commits. With 1 MB as the target file size, 100's of thousands of
    // files would mean that we are writing gigabytes per second - quite a bit faster than any disks can handle now.
    private final long maxAppendableClaimLength;
    private final int maxFlowFilesPerClaim;
    private final long maxArchiveMillis;
    private final Map<String, Long> minUsableContainerBytesForArchive = new HashMap<>();
    private final ScheduledExecutorService containerCleanupExecutor;

    private ResourceClaimManager resourceClaimManager; // effectively final

    // Map of container to archived files that should be deleted next.
    private final Map<String, BlockingQueue<ArchiveInfo>> archivedFiles = new HashMap<>();

    // guarded by synchronizing on this
    private final AtomicLong oldestArchiveDate = new AtomicLong(0L);

    private final FileSystem s3fs;
    private final NiFiProperties nifiProperties;

    /**
     * NiFi MinIO specific settings
     */
    private final String NIFI_S3_ENDPOINT = "nifi.content.repository.s3_endpoint";
    private final String NIFI_S3_ACCESS_KEY = "nifi.content.repository.s3_access_key";
    private final String NIFI_S3_SECRET_KEY = "nifi.content.repository.s3_secret_key";
    private final String NIFI_S3_SSL_ENABLED = "nifi.content.repository.s3_ssl_enabled";
    private final String NIFI_S3_PATH_STYLE_ACCESS = "nifi.content.repository.s3_path_style_access";

    /**
     * Default no args constructor for service loading only
     */
    public MinIORepository() throws IOException {
        containers = null;
        containerNames = null;
        index = null;
        archiveData = false;
        maxArchiveMillis = 0;
        containerCleanupExecutor = null;
        nifiProperties = null;
        maxAppendableClaimLength = 0;
        maxFlowFilesPerClaim = 0;
        writableClaimQueue = null;
        s3fs = null;
    }

    public MinIORepository(final NiFiProperties nifiProperties) throws IOException {
        this.nifiProperties = nifiProperties;

        String protocol = "HTTP";
        String enableSSL = nifiProperties.getProperty(NIFI_S3_SSL_ENABLED);
        if ("true".equalsIgnoreCase(enableSSL)) {
            protocol = "HTTPS";
        }

        Map<String, ?> s3Env = ImmutableMap.<String, Object> builder()
            .put(io.minio.nifi.s3fs.AmazonS3Factory.ACCESS_KEY,
                 nifiProperties.getProperty(NIFI_S3_ACCESS_KEY))
            .put(io.minio.nifi.s3fs.AmazonS3Factory.SECRET_KEY,
                 nifiProperties.getProperty(NIFI_S3_SECRET_KEY))
            .put(io.minio.nifi.s3fs.AmazonS3Factory.PROTOCOL, protocol)
            .put(io.minio.nifi.s3fs.AmazonS3Factory.PATH_STYLE_ACCESS,
                 nifiProperties.getProperty(NIFI_S3_PATH_STYLE_ACCESS)).build();

        URI s3Uri = URI.create(nifiProperties.getProperty(NIFI_S3_ENDPOINT));
        this.s3fs = FileSystems.newFileSystem(s3Uri, s3Env, Thread.currentThread().getContextClassLoader());

        // determine the file repository paths and ensure they exist
        final Map<String, Path> fileRepositoryPaths = new HashMap<>();
        for (final Map.Entry<String, Path> entry : nifiProperties.getContentRepositoryPaths().entrySet()) {
            Path path = this.s3fs.getPath(entry.getValue().normalize().toString());
            fileRepositoryPaths.put(entry.toString(), path);
            Files.createDirectories(path);
        }

        this.maxFlowFilesPerClaim = nifiProperties.getMaxFlowFilesPerClaim();
        this.writableClaimQueue  = new LinkedBlockingQueue<>(maxFlowFilesPerClaim);
        final long configuredAppendableClaimLength = DataUnit.parseDataSize(nifiProperties.getMaxAppendableClaimSize(),
                                                                            DataUnit.B).longValue();
        final long appendableClaimLengthCap = DataUnit.parseDataSize(APPENDABLE_CLAIM_LENGTH_CAP, DataUnit.B).longValue();
        if (configuredAppendableClaimLength > appendableClaimLengthCap) {
            LOG.warn("Configured property '{}' with value {} exceeds cap of {}. Setting value to {}",
                    NiFiProperties.MAX_APPENDABLE_CLAIM_SIZE,
                    configuredAppendableClaimLength,
                    APPENDABLE_CLAIM_LENGTH_CAP,
                    APPENDABLE_CLAIM_LENGTH_CAP);
            this.maxAppendableClaimLength = appendableClaimLengthCap;
        } else {
            this.maxAppendableClaimLength = configuredAppendableClaimLength;
        }

        this.containers = new HashMap<>(fileRepositoryPaths);
        this.containerNames = new ArrayList<>(containers.keySet());
        index = new AtomicLong(0L);

        for (final String containerName : containerNames) {
            reclaimable.put(containerName, new LinkedBlockingQueue<>(10000));
            archivedFiles.put(containerName, new LinkedBlockingQueue<>(100000));
        }

        final String enableArchiving = nifiProperties.getProperty(NiFiProperties.CONTENT_ARCHIVE_ENABLED);
        if ("true".equalsIgnoreCase(enableArchiving)) {
            archiveData = true;
        } else if ("false".equalsIgnoreCase(enableArchiving)) {
            archiveData = false;
        } else {
            LOG.warn("No property set for '{}'; will not archive content", NiFiProperties.CONTENT_ARCHIVE_ENABLED);
            archiveData = false;
        }

        double maxArchiveRatio = 0D;
        double archiveBackPressureRatio = 0.01D;

        for (final String containerName : containerNames) {
            containerStateMap.put(containerName, new ContainerState(containerName,
                                                                    false,
                                                                    Long.MAX_VALUE,
                                                                    Long.MAX_VALUE));
        }

        maxArchiveMillis = 0L;
        containerCleanupExecutor = new FlowEngine(containers.size(), "Cleanup MinIORepository Container", true);
    }

    @Override
    public void initialize(final ResourceClaimManager claimManager) {
        this.resourceClaimManager = claimManager;

        executor.scheduleWithFixedDelay(new BinDestructableClaims(), 1, 1, TimeUnit.SECONDS);
        for (int i = 0; i < containers.size(); i++) {
            executor.scheduleWithFixedDelay(new ArchiveOrDestroyDestructableClaims(), 1, 1, TimeUnit.SECONDS);
        }

    }

    @Override
    public void shutdown() {
        executor.shutdown();
        containerCleanupExecutor.shutdown();

        // Close any of the writable claim streams that are currently open.
        // Other threads may be writing to these streams, and that's okay.
        // If that happens, we will simply close the stream, resulting in an
        // IOException that will roll back the session. Since this is called
        // only on shutdown of the application, we don't have to worry about
        // partially written files - on restart, we will simply start writing
        // to new files and leave those trailing bytes alone.
        for (final OutputStream out : writableClaimStreams.values()) {
            try {
                out.close();
            } catch (final IOException ioe) {
            }
        }
    }

    private static double getRatio(final String value) {
        final String trimmed = value.trim();
        final String percentage = trimmed.substring(0, trimmed.length() - 1);
        return Integer.parseInt(percentage) / 100D;
    }

    @Override
    public Set<String> getContainerNames() {
        return new HashSet<>(containerNames);
    }

    @Override
    public long getContainerCapacity(final String containerName) throws IOException {
        final Path path = containers.get(containerName);

        if (path == null) {
            throw new IllegalArgumentException("No container exists with name " + containerName);
        }

        long capacity = FileUtils.getContainerCapacity(path);
        if (capacity==0) {
            throw new IOException("System returned total space of the partition for " + containerName + " is zero byte. "
                    + "Nifi can not create a zero sized MinIORepository.");
        }

        return capacity;
    }

    @Override
    public long getContainerUsableSpace(String containerName) throws IOException {
        return -1L;
    }

    @Override
    public String getContainerFileStoreName(final String containerName) {
        final Path path = containers.get(containerName);
        try {
            return Files.getFileStore(path).name();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void cleanup() {
    }

    private Path getPath(final ContentClaim claim) {
        final ResourceClaim resourceClaim = claim.getResourceClaim();
        return getPath(resourceClaim);
    }

    private Path getPath(final ResourceClaim resourceClaim) {
        final Path containerPath = containers.get(resourceClaim.getContainer());
        if (containerPath == null) {
            return null;
        }
        return containerPath.resolve(resourceClaim.getSection()).resolve(resourceClaim.getId());
    }

    public Path getPath(final ContentClaim claim, final boolean verifyExists) throws ContentNotFoundException {
        final ResourceClaim resourceClaim = claim.getResourceClaim();
        final Path containerPath = containers.get(resourceClaim.getContainer());
        if (containerPath == null) {
            if (verifyExists) {
                throw new ContentNotFoundException(claim);
            } else {
                return null;
            }
        }

        // Create the Path that points to the data
        return containerPath.resolve(resourceClaim.getSection()).resolve(resourceClaim.getId());
    }

    @Override
    public ContentClaim create(final boolean lossTolerant) throws IOException {
        ResourceClaim resourceClaim;

        final long resourceOffset;
        final ClaimLengthPair pair = writableClaimQueue.poll();
        if (pair == null) {
            final long currentIndex = index.incrementAndGet();

            String containerName = null;
            boolean waitRequired = true;
            ContainerState containerState = null;
            for (long containerIndex = currentIndex; containerIndex < currentIndex + containers.size(); containerIndex++) {
                final long modulatedContainerIndex = containerIndex % containers.size();
                containerName = containerNames.get((int) modulatedContainerIndex);

                containerState = containerStateMap.get(containerName);
                if (!containerState.isWaitRequired()) {
                    waitRequired = false;
                    break;
                }
            }

            if (waitRequired) {
                containerState.waitForArchiveExpiration();
            }

            final long modulatedSectionIndex = currentIndex % SECTIONS_PER_CONTAINER;
            final String section = String.valueOf(modulatedSectionIndex).intern();
            final String claimId = System.currentTimeMillis() + "-" + currentIndex;

            resourceClaim = resourceClaimManager.newResourceClaim(containerName, section, claimId, lossTolerant, true);
            resourceOffset = 0L;
            LOG.debug("Creating new Resource Claim {}", resourceClaim);

            // we always append because there may be another ContentClaim using the same resource claim.
            // However, we know that we will never write to the same claim from two different threads
            // at the same time because we will call create() to get the claim before we write to it,
            // and when we call create(), it will remove it from the Queue, which means that no other
            // thread will get the same Claim until we've finished writing to it.
            final OutputStream os = Files.newOutputStream(getPath(resourceClaim));
            ByteCountingOutputStream claimStream = new SynchronizedByteCountingOutputStream(os);
            writableClaimStreams.put(resourceClaim, claimStream);

            incrementClaimantCount(resourceClaim, true);
        } else {
            resourceClaim = pair.getClaim();
            resourceOffset = pair.getLength();
            LOG.debug("Reusing Resource Claim {}", resourceClaim);

            incrementClaimantCount(resourceClaim, false);
        }

        final StandardContentClaim scc = new StandardContentClaim(resourceClaim, resourceOffset);
        return scc;
    }

    @Override
    public int incrementClaimaintCount(final ContentClaim claim) {
        return incrementClaimantCount(claim == null ? null : claim.getResourceClaim(), false);
    }

    protected int incrementClaimantCount(final ResourceClaim resourceClaim, final boolean newClaim) {
        if (resourceClaim == null) {
            return 0;
        }

        return resourceClaimManager.incrementClaimantCount(resourceClaim, newClaim);
    }

    @Override
    public int getClaimantCount(final ContentClaim claim) {
        if (claim == null) {
            return 0;
        }

        return resourceClaimManager.getClaimantCount(claim.getResourceClaim());
    }

    @Override
    public int decrementClaimantCount(final ContentClaim claim) {
        if (claim == null) {
            return 0;
        }

        return resourceClaimManager.decrementClaimantCount(claim.getResourceClaim());
    }

    @Override
    public boolean remove(final ContentClaim claim) {
        if (claim == null) {
            return false;
        }

        return remove(claim.getResourceClaim());
    }

    private boolean remove(final ResourceClaim claim) {
        if (claim == null) {
            return false;
        }

        // If the claim is still in use, we won't remove it.
        if (claim.isInUse()) {
            return false;
        }

        // Ensure that we have no writable claim streams for this resource claim
        final ByteCountingOutputStream bcos = writableClaimStreams.remove(claim);

        if (bcos != null) {
            try {
                bcos.close();
            } catch (final IOException e) {
                LOG.warn("Failed to close Output Stream for {} due to {}", claim, e);
            }
        }

        try {
            Files.deleteIfExists(getPath(claim));
        } catch (final IOException e) {
        }
        return true;
    }

    @Override
    public ContentClaim clone(final ContentClaim original, final boolean lossTolerant) throws IOException {
        if (original == null) {
            return null;
        }

        final ContentClaim newClaim = create(lossTolerant);
        final InputStream in = read(original);
        final OutputStream os = write(newClaim);
        try {
            IOUtils.copy(in, os, DEFAULT_BUFFER_SIZE);
        } catch (final IOException ioe) {
            decrementClaimantCount(newClaim);
            remove(newClaim);
            throw ioe;
        } finally {
            IOUtils.closeQuietly(in, os);
        }
        return newClaim;
    }

    @Override
    public long merge(final Collection<ContentClaim> claims, final ContentClaim destination, final byte[] header, final byte[] footer, final byte[] demarcator) throws IOException {
        if (claims.contains(destination)) {
            throw new IllegalArgumentException("destination cannot be within claims");
        }

        try (final ByteCountingOutputStream out = new ByteCountingOutputStream(write(destination))) {
            if (header != null) {
                out.write(header);
            }

            int i = 0;
            for (final ContentClaim claim : claims) {
                final InputStream in = read(claim);
                try {
                    IOUtils.copy(in, out, DEFAULT_BUFFER_SIZE);
                } catch (IOException e) {
                    throw e;
                } finally {
                    IOUtils.closeQuietly(in, null);
                }
                if (++i < claims.size() && demarcator != null) {
                    out.write(demarcator);
                }
            }

            if (footer != null) {
                out.write(footer);
            }

            return out.getBytesWritten();
        }
    }

    @Override
    public long importFrom(final Path content, final ContentClaim claim) throws IOException {
        try (final InputStream in = Files.newInputStream(content, StandardOpenOption.READ)) {
            return importFrom(in, claim);
        }
    }

    @Override
    public long importFrom(final InputStream content, final ContentClaim claim) throws IOException {
        try (final OutputStream out = write(claim, false)) {
            return IOUtils.copy(content, out, DEFAULT_BUFFER_SIZE);
        }
    }

    @Override
    public long exportTo(final ContentClaim claim, final Path destination, final boolean append) throws IOException {
        if (claim == null) {
            if (append) {
                return 0L;
            }
            Files.createFile(destination);
            return 0L;
        }

        final InputStream in = read(claim);
        final OutputStream os = Files.newOutputStream(destination);
        try {
            return IOUtils.copy(in, os, DEFAULT_BUFFER_SIZE);
        } catch (IOException e) {
            throw e;
        } finally {
            IOUtils.closeQuietly(in, os);
        }
    }

    @Override
    public long exportTo(final ContentClaim claim, final Path destination, final boolean append, final long offset, final long length) throws IOException {
        if (claim == null && offset > 0) {
            throw new IllegalArgumentException("Cannot specify an offset of " + offset + " for a null claim");
        }
        if (claim == null) {
            if (append) {
                return 0L;
            }
            Files.createFile(destination);
            return 0L;
        }

        final InputStream in = read(claim, offset, length);
        final OutputStream os = Files.newOutputStream(destination);
        try {
            return IOUtils.copy(in, os, DEFAULT_BUFFER_SIZE);
        } catch (IOException e) {
            throw e;
        } finally {
            IOUtils.closeQuietly(in, os);
        }
    }

    @Override
    public long exportTo(final ContentClaim claim, final OutputStream destination) throws IOException {
        if (claim == null) {
            return 0L;
        }

        final InputStream in = read(claim);
        try {
            return IOUtils.copy(in, destination, DEFAULT_BUFFER_SIZE);
        } catch (IOException e) {
            throw e;
        } finally {
            IOUtils.closeQuietly(in, null);
        }
    }

    @Override
    public long exportTo(final ContentClaim claim, final OutputStream destination, final long offset, final long length) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("offset cannot be negative");
        }
        final InputStream in = read(claim);
        try {
            return IOUtils.copy(in, destination, DEFAULT_BUFFER_SIZE);
        } catch (IOException e) {
            throw e;
        } finally {
            IOUtils.closeQuietly(in, null);
        }
    }

    @Override
    public long size(final ContentClaim claim) throws IOException {
        if (claim == null) {
            return 0L;
        }

        // see javadocs for claim.getLength() as to why we do this.
        if (claim.getLength() < 0) {
            return Files.size(getPath(claim, true)) - claim.getOffset();
        }

        return claim.getLength();
    }

    private InputStream readObject(final ContentClaim claim, long offset, long length)
        throws IOException, NoSuchFileException {

        Path path = getPath(claim, true);
        S3Path s3Path = (S3Path) path;
        String key = s3Path.getKey();
        GetObjectRequest req = new GetObjectRequest(s3Path.getFileStore().name(), key)
            .withRange(offset, offset+length-1);
        S3Object object = s3Path.getFileSystem().getClient().getObject(req);
        try {
            InputStream res = object.getObjectContent();
            if (res == null)
                throw new IOException(String.format("The specified path is a directory: %s", path));

            return res;
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() != 404) {
                throw new IOException(String.format("Cannot access file: %s", path), e);
            }
        }

        object.close();

        // getPath failed look for archivePath.
        path = getArchivePath(claim.getResourceClaim());
        s3Path = (S3Path) path;
        key = s3Path.getKey();
        req = new GetObjectRequest(s3Path.getFileStore().name(), key)
            .withRange(offset, offset+length-1);
        object = s3Path.getFileSystem().getClient().getObject(req);
        try {
            InputStream res = object.getObjectContent();
            if (res == null)
                throw new IOException(String.format("The specified path is a directory: %s", path));

            return res;
        } catch (AmazonS3Exception e) {
            if (e.getStatusCode() == 404)
                throw new NoSuchFileException(path.toString());
            throw new IOException(String.format("Cannot access file: %s", path), e);
        }
    }

    public InputStream read(final ContentClaim claim, long offset, long length) throws IOException, NoSuchFileException {
        if (claim == null) {
            return new ByteArrayInputStream(new byte[0]);
        }

        return readObject(claim, offset, length);
    }

    @Override
    public InputStream read(final ContentClaim claim) throws IOException, NoSuchFileException {
        if (claim == null) {
            return new ByteArrayInputStream(new byte[0]);
        }

        return readObject(claim, claim.getOffset(), claim.getLength());
    }

    @Override
    public OutputStream write(final ContentClaim claim) throws IOException {
        return write(claim, false);
    }

    private OutputStream write(final ContentClaim claim, final boolean append) throws IOException {
        if (claim == null) {
            throw new NullPointerException("ContentClaim cannot be null");
        }

        if (!(claim instanceof StandardContentClaim)) {
            // we know that we will only create Content Claims that are of type StandardContentClaim, so if we get anything
            // else, just throw an Exception because it is not valid for this Repository
            throw new IllegalArgumentException("Cannot write to " + claim + " because that Content Claim does belong to this Content Repository");
        }

        final StandardContentClaim scc = (StandardContentClaim) claim;
        if (claim.getLength() > 0) {
            throw new IllegalArgumentException("Cannot write to " + claim + " because it has already been written to.");
        }

        ByteCountingOutputStream claimStream = writableClaimStreams.get(scc.getResourceClaim());
        final int initialLength = append ? (int) Math.max(0, scc.getLength()) : 0;

        final ByteCountingOutputStream bcos = claimStream;
        final OutputStream out = new OutputStream() {
            private long bytesWritten = 0L;
            private boolean recycle = true;
            private boolean closed = false;

            @Override
            public String toString() {
                return "MinIORepository Stream [" + scc + "]";
            }

            @Override
            public synchronized void write(final int b) throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }

                try {
                    bcos.write(b);
                } catch (final IOException ioe) {
                    recycle = false;
                    throw new IOException("Failed to write to " + this, ioe);
                }

                bytesWritten++;
                scc.setLength(bytesWritten + initialLength);
            }

            @Override
            public synchronized void write(final byte[] b) throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }

                try {
                    bcos.write(b);
                } catch (final IOException ioe) {
                    recycle = false;
                    throw new IOException("Failed to write to " + this, ioe);
                }

                bytesWritten += b.length;
                scc.setLength(bytesWritten + initialLength);
            }

            @Override
            public synchronized void write(final byte[] b, final int off, final int len) throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }

                try {
                    bcos.write(b, off, len);
                } catch (final IOException ioe) {
                    recycle = false;
                    throw new IOException("Failed to write to " + this, ioe);
                }

                bytesWritten += len;

                scc.setLength(bytesWritten + initialLength);
            }

            @Override
            public synchronized void flush() throws IOException {
                if (closed) {
                    throw new IOException("Stream is closed");
                }

                bcos.flush();
            }

            @Override
            public synchronized void close() throws IOException {
                closed = true;

                if (scc.getLength() < 0) {
                    // If claim was not written to, set length to 0
                    scc.setLength(0L);
                }

                // if we've not yet hit the threshold for appending to a resource claim, add the claim
                // to the writableClaimQueue so that the Resource Claim can be used again when create()
                // is called. In this case, we don't have to actually close the file stream. Instead, we
                // can just add it onto the queue and continue to use it for the next content claim.
                final long resourceClaimLength = scc.getOffset() + scc.getLength();
                if (recycle && resourceClaimLength < maxAppendableClaimLength) {
                    final ClaimLengthPair pair = new ClaimLengthPair(scc.getResourceClaim(), resourceClaimLength);

                    // We are checking that writableClaimStreams contains the resource claim as a key, as a sanity check.
                    // It should always be there. However, we have encountered a bug before where we archived content before
                    // we should have. As a result, the Resource Claim and the associated OutputStream were removed from the
                    // writableClaimStreams map, and this caused a NullPointerException. Worse, the call here to
                    // writableClaimQueue.offer() means that the ResourceClaim was then reused, which resulted in an endless
                    // loop of NullPointerException's being thrown. As a result, we simply ensure that the Resource Claim does
                    // in fact have an OutputStream associated with it before adding it back to the writableClaimQueue.
                    final boolean enqueued = (writableClaimStreams.get(scc.getResourceClaim()) != null &&
                                              writableClaimQueue.offer(pair));

                    if (enqueued) {
                        LOG.debug("Claim length less than max; Adding {} back to Writable Claim Queue", this);
                    } else {
                        writableClaimStreams.remove(scc.getResourceClaim());
                        resourceClaimManager.freeze(scc.getResourceClaim());

                        bcos.close();

                        LOG.debug("Claim length less than max; Closing {} because could not add back to queue", this);
                        if (LOG.isTraceEnabled()) {
                            LOG.trace("Stack trace: ", new RuntimeException("Stack Trace for closing " + this));
                        }
                    }
                } else {
                    // we've reached the limit for this claim. Don't add it back to our queue.
                    // Instead, just remove it and move on.

                    // Mark the claim as no longer being able to be written to
                    resourceClaimManager.freeze(scc.getResourceClaim());

                    // ensure that the claim is no longer on the queue
                    writableClaimQueue.remove(new ClaimLengthPair(scc.getResourceClaim(), resourceClaimLength));

                    bcos.close();
                    LOG.debug("Claim lenth >= max; Closing {}", this);
                    if (LOG.isTraceEnabled()) {
                        LOG.trace("Stack trace: ", new RuntimeException("Stack Trace for closing " + this));
                    }
                }
            }
        };

        LOG.debug("Writing to {}", out);
        if (LOG.isTraceEnabled()) {
            LOG.trace("Stack trace: ", new RuntimeException("Stack Trace for writing to " + out));
        }

        return out;
    }

    @Override
    public void purge() {
        // delete all content from repositories
        for (final Path path : containers.values()) {
            FileUtils.deleteFilesInDir(path.toFile(), null, LOG, true);
        }

        resourceClaimManager.purge();
    }

    private class BinDestructableClaims implements Runnable {

        @Override
        public void run() {
            try {
                // Get all of the Destructable Claims and bin them based on their Container. We do this
                // because the Container generally maps to a physical partition on the disk, so we want a few
                // different threads hitting the different partitions but don't want multiple threads hitting
                // the same partition.
                final List<ResourceClaim> toDestroy = new ArrayList<>();
                while (true) {
                    toDestroy.clear();
                    resourceClaimManager.drainDestructableClaims(toDestroy, 10000);
                    if (toDestroy.isEmpty()) {
                        return;
                    }

                    for (final ResourceClaim claim : toDestroy) {
                        final String container = claim.getContainer();
                        final BlockingQueue<ResourceClaim> claimQueue = reclaimable.get(container);

                        try {
                            while (true) {
                                if (claimQueue == null) {
                                    break;
                                }
                                if (claimQueue.offer(claim, 10, TimeUnit.MINUTES)) {
                                    break;
                                } else {
                                    LOG.warn("Failed to clean up {} because old claims aren't being cleaned up fast enough. "
                                            + "This Content Claim will remain in the Content Repository until NiFi is restarted, at which point it will be cleaned up", claim);
                                }
                            }
                        } catch (final InterruptedException ie) {
                            LOG.warn("Failed to clean up {} because thread was interrupted", claim);
                        }
                    }
                }
            } catch (final Throwable t) {
                LOG.error("Failed to cleanup content claims due to {}", t);
            }
        }
    }

    public static Path getArchivePath(final Path contentClaimPath) {
        final Path sectionPath = contentClaimPath.getParent();
        final String claimId = contentClaimPath.toFile().getName();
        return sectionPath.resolve(ARCHIVE_DIR_NAME).resolve(claimId);
    }

    private Path getArchivePath(final ResourceClaim claim) {
        final String claimId = claim.getId();
        final Path containerPath = containers.get(claim.getContainer());
        final Path archivePath = containerPath.resolve(claim.getSection()).resolve(ARCHIVE_DIR_NAME).resolve(claimId);
        return archivePath;
    }

    @Override
    public boolean isAccessible(final ContentClaim contentClaim) throws IOException {
        if (contentClaim == null) {
            return false;
        }
        final Path path = getPath(contentClaim);
        if (path == null) {
            return false;
        }

        if (Files.exists(path)) {
            return true;
        }

        return Files.exists(getArchivePath(contentClaim.getResourceClaim()));
    }

    // visible for testing
    boolean archive(final ResourceClaim claim) throws IOException {
        if (!archiveData) {
            return false;
        }

        if (claim.isInUse()) {
            return false;
        }

        // If the claim count is decremented to 0 (<= 0 as a 'defensive programming' strategy), ensure that
        // we close the stream if there is one. There may be a stream open if create() is called and then
        // claimant count is removed without writing to the claim (or more specifically, without closing the
        // OutputStream that is returned when calling write() ).
        final OutputStream out = writableClaimStreams.remove(claim);

        if (out != null) {
            try {
                out.close();
            } catch (final IOException ioe) {
                LOG.warn("Unable to close Output Stream for " + claim, ioe);
            }
        }

        final Path curPath = getPath(claim);
        if (curPath == null) {
            return false;
        }

        final boolean archived = archive(curPath);
        LOG.debug("Successfully moved {} to archive", claim);
        return archived;
    }

    protected int getOpenStreamCount() {
        return writableClaimStreams.size();
    }

    // marked protected for visibility and ability to override for unit tests.
    protected boolean archive(final Path curPath) throws IOException {
        // check if already archived
        final boolean alreadyArchived = ARCHIVE_DIR_NAME.equals(curPath.getParent().toFile().getName());
        if (alreadyArchived) {
            return false;
        }

        final Path archivePath = getArchivePath(curPath);
        if (curPath.equals(archivePath)) {
            LOG.warn("Cannot archive {} because it is already archived", curPath);
            return false;
        }

        try {
            Files.move(curPath, archivePath);
            return true;
        } catch (final NoSuchFileException nsfee) {
            // If the current path exists, try to create archive path and do the move again.
            // Otherwise, either the content was removed or has already been archived. Either way,
            // there's nothing that can be done.
            if (Files.exists(curPath)) {
                // The archive directory doesn't exist. Create now and try operation again.
                // We do it this way, rather than ensuring that the directory exists ahead of time because
                // it will be rare for the directory not to exist and we would prefer to have the overhead
                // of the Exception being thrown in these cases, rather than have the overhead of checking
                // for the existence of the directory continually.
                Files.createDirectories(archivePath.getParent());
                Files.move(curPath, archivePath);
                return true;
            }

            return false;
        }
    }

    private long getLastModTime(final Path file) throws IOException {
        // the content claim identifier is created by concatenating System.currentTimeMillis(), "-", and a one-up number.
        // However, it used to be just a one-up number. As a result, we can check for the timestamp and if present use it.
        // If not present, we will use the last modified time.
        final String filename = file.toString();
        final int dashIndex = filename.indexOf("-");
        if (dashIndex > 0) {
            final String creationTimestamp = filename.substring(0, dashIndex);
            try {
                return Long.parseLong(creationTimestamp);
            } catch (final NumberFormatException nfe) {
            }
        }
        return Files.getLastModifiedTime(file).toMillis();
    }

    private class ArchiveOrDestroyDestructableClaims implements Runnable {

        @Override
        public void run() {
            try {
                // while there are claims waiting to be destroyed...
                while (true) {
                    // look through each of the binned queues of Content Claims
                    int successCount = 0;
                    final List<ResourceClaim> toRemove = new ArrayList<>();
                    for (final Map.Entry<String, BlockingQueue<ResourceClaim>> entry : reclaimable.entrySet()) {
                        // drain the queue of all ContentClaims that can be destroyed for the given container.
                        final String container = entry.toString();
                        final ContainerState containerState = containerStateMap.get(container);

                        toRemove.clear();
                        entry.getValue().drainTo(toRemove);
                        if (toRemove.isEmpty()) {
                            continue;
                        }

                        // destroy each claim for this container
                        final long start = System.nanoTime();
                        for (final ResourceClaim claim : toRemove) {
                            if (archiveData) {
                                try {
                                    if (archive(claim)) {
                                        containerState.incrementArchiveCount();
                                        successCount++;
                                    }
                                } catch (final Exception e) {
                                    LOG.warn("Failed to archive {} due to {}", claim, e.toString());
                                    if (LOG.isDebugEnabled()) {
                                        LOG.warn("", e);
                                    }
                                }
                            } else if (remove(claim)) {
                                successCount++;
                            }
                        }

                        final long nanos = System.nanoTime() - start;
                        final long millis = TimeUnit.NANOSECONDS.toMillis(nanos);

                        if (successCount == 0) {
                            LOG.debug("No ContentClaims archived/removed for Container {}", container);
                        } else {
                            LOG.info("Successfully {} {} Resource Claims for Container {} in {} millis", archiveData ? "archived" : "destroyed", successCount, container, millis);
                        }
                    }

                    // if we didn't destroy anything, we're done.
                    if (successCount == 0) {
                        return;
                    }
                }
            } catch (final Throwable t) {
                LOG.error("Failed to handle destructable claims due to {}", t.toString());
                if (LOG.isDebugEnabled()) {
                    LOG.error("", t);
                }
            }
        }
    }

    private static class ArchiveInfo {

        private final Path containerPath;
        private final String relativePath;
        private final String name;
        private final long size;
        private final long lastModTime;

        public ArchiveInfo(final Path containerPath, final Path path, final long size, final long lastModTime) {
            this.containerPath = containerPath;
            this.relativePath = containerPath.relativize(path).toString();
            this.name = path.toString();
            this.size = size;
            this.lastModTime = lastModTime;
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

        public long getLastModTime() {
            return lastModTime;
        }

        public Path toPath() {
            return containerPath.resolve(relativePath);
        }
    }

    private class ContainerState {

        private final String containerName;
        private final AtomicLong archivedFileCount = new AtomicLong(0L);
        private final long backPressureBytes;
        private final long capacity;
        private final boolean archiveEnabled;
        private final Lock lock = new ReentrantLock();
        private final Condition condition = lock.newCondition();

        private volatile long bytesUsed = 0L;

        public ContainerState(final String containerName, final boolean archiveEnabled, final long backPressureBytes, final long capacity) {
            this.containerName = containerName;
            this.archiveEnabled = archiveEnabled;
            this.backPressureBytes = backPressureBytes;
            this.capacity = capacity;
        }

        /**
         * @return {@code true} if wait is required to create claims against
         * this Container, based on whether or not the container has reached its
         * back pressure threshold
         */
        public boolean isWaitRequired() {
            if (!archiveEnabled) {
                return false;
            }

            long used = bytesUsed;

            if (used == 0L) {
                try {
                    final long free = getContainerUsableSpace(containerName);
                    used = capacity - free;
                    bytesUsed = used;
                } catch (final IOException e) {
                    return false;
                }
            }

            return used >= backPressureBytes && archivedFileCount.get() > 0;
        }

        public void waitForArchiveExpiration() {
            if (!archiveEnabled) {
                return;
            }

            lock.lock();
            try {
                while (isWaitRequired()) {
                    try {
                        LOG.info("Unable to write to container {} due to archive file size constraints; waiting for archive cleanup", containerName);
                        condition.await();
                    } catch (final InterruptedException e) {
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        public void signalCreationReady() {
            if (!archiveEnabled) {
                return;
            }

            lock.lock();
            try {
                try {
                    final long free = getContainerUsableSpace(containerName);
                    bytesUsed = capacity - free;
                } catch (final Exception e) {
                    bytesUsed = 0L;
                }

                LOG.debug("Container {} signaled to allow Content Claim Creation", containerName);
                condition.signal();
            } finally {
                lock.unlock();
            }
        }

        public void incrementArchiveCount() {
            archivedFileCount.incrementAndGet();
        }

        public void decrementArchiveCount() {
            archivedFileCount.decrementAndGet();
        }
    }

    private static class ClaimLengthPair {

        private final ResourceClaim claim;
        private final Long length;

        public ClaimLengthPair(final ResourceClaim claim, final Long length) {
            this.claim = claim;
            this.length = length;
        }

        public ResourceClaim getClaim() {
            return claim;
        }

        public Long getLength() {
            return length;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + (claim == null ? 0 : claim.hashCode());
            return result;
        }

        /**
         * Equality is determined purely by the ResourceClaim's equality
         *
         * @param obj the object to compare against
         * @return -1, 0, or +1 according to the contract of Object.equals
         */
        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }

            if (obj == null) {
                return false;
            }

            if (getClass() != obj.getClass()) {
                return false;
            }

            final ClaimLengthPair other = (ClaimLengthPair) obj;
            return claim.equals(other.getClaim());
        }
    }
}
