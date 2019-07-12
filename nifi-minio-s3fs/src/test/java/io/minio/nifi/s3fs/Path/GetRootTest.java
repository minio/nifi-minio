package io.minio.nifi.s3fs.Path;

import io.minio.nifi.s3fs.S3FileSystemProvider;
import io.minio.nifi.s3fs.S3Path;
import io.minio.nifi.s3fs.S3UnitTestBase;
import io.minio.nifi.s3fs.util.S3EndpointConstant;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.FileSystems;

import static io.minio.nifi.s3fs.util.S3EndpointConstant.S3_GLOBAL_URI_TEST;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class GetRootTest extends S3UnitTestBase {

    private S3FileSystemProvider s3fsProvider;

    private S3Path getPath(String path) {
        return s3fsProvider.getFileSystem(S3_GLOBAL_URI_TEST).getPath(path);
    }

    @Before
    public void setup() throws IOException {
        s3fsProvider = getS3fsProvider();
        s3fsProvider.newFileSystem(S3EndpointConstant.S3_GLOBAL_URI_TEST, null);
    }

    @Test
    public void getRootReturnBucket() {
        assertEquals(getPath("/bucketA/"), getPath("/bucketA/dir/file").getRoot());
    }

    @Test
    public void getRootRelativeReturnNull() {
        assertNull(getPath("dir/file").getRoot());
    }

}
